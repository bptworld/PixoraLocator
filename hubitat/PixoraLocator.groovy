import groovy.json.JsonSlurper

metadata {
    definition(name: "Pixora Locator", namespace: "pixorahq", author: "PixoraHQ") {
        capability "Sensor"
        capability "PresenceSensor"
        capability "Battery"
        capability "Refresh"

        attribute "latitude", "string"
        attribute "longitude", "string"
        attribute "accuracy", "number"
        attribute "place", "string"
        attribute "lastLocationAt", "string"
        attribute "deliveryStatus", "string"
        attribute "distanceFromHome", "number"
        attribute "charging", "enum", ["charging", "not charging", "unknown"]
        attribute "speed", "number"

        command "updateLocation", [[name: "Location payload", type: "STRING", description: "Pixora Locator JSON payload"]]
        command "clearLocation"
    }
}

preferences {
    input name: "distanceUnits", type: "enum", title: "Distance and speed units", options: ["mi": "Miles / mph", "km": "Kilometers / km/h"], defaultValue: "mi"
    input name: "staleAfterMinutes", type: "number", title: "Mark delivery stale after this many minutes", defaultValue: 45, range: "15..1440"
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    sendEvent(name: "presence", value: device.currentValue("presence") ?: "not present")
    sendEvent(name: "deliveryStatus", value: device.currentValue("deliveryStatus") ?: "waiting")
    sendEvent(name: "charging", value: device.currentValue("charging") ?: "unknown")
    updateDistanceFromHome(device.currentValue("latitude"), device.currentValue("longitude"))
    updateSpeed(state.speedMetersPerSecond)
    unschedule()
    runEvery15Minutes("refresh")
}

def updateLocation(String payload) {
    Map locationData
    try {
        locationData = new JsonSlurper().parseText(payload ?: "{}") as Map
    } catch (Exception ignored) {
        sendEvent(name: "deliveryStatus", value: "invalid payload")
        return
    }
    if (!validCoordinate(locationData.latitude, 90) || !validCoordinate(locationData.longitude, 180)) {
        sendEvent(name: "deliveryStatus", value: "invalid coordinates")
        return
    }
    String capturedAt = (locationData.capturedAt ?: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone("UTC"))).toString()
    String displayedAt = capturedAt
    try {
        long capturedMillis = java.time.OffsetDateTime.parse(capturedAt).toInstant().toEpochMilli()
        TimeZone hubTimeZone = location?.timeZone ?: TimeZone.getDefault()
        displayedAt = new Date(capturedMillis).format("M-d-yyyy h:mm a", hubTimeZone).toLowerCase()
    } catch (Exception ignored) {
        // Keep the original timestamp if an older sender provides an unexpected format.
    }
    sendEvent(name: "latitude", value: locationData.latitude.toString())
    sendEvent(name: "longitude", value: locationData.longitude.toString())
    updateDistanceFromHome(locationData.latitude, locationData.longitude)
    state.speedMetersPerSecond = validSpeed(locationData.speed) ? locationData.speed : null
    updateSpeed(state.speedMetersPerSecond)
    sendEvent(name: "charging", value: locationData.charging instanceof Boolean ? (locationData.charging ? "charging" : "not charging") : "unknown")
    BigDecimal accuracy = locationData.accuracy instanceof Number ? new BigDecimal(locationData.accuracy.toString()) : BigDecimal.ZERO
    if (accuracy < BigDecimal.ZERO) accuracy = BigDecimal.ZERO
    sendEvent(name: "accuracy", value: accuracy, unit: "m")
    sendEvent(name: "place", value: (locationData.place ?: "not_home").toString())
    sendEvent(name: "lastLocationAt", value: displayedAt)
    sendEvent(name: "presence", value: locationData.presence == "present" ? "present" : "not present")
    if (locationData.battery instanceof Number) {
        int battery = (locationData.battery as Number).intValue()
        battery = Math.max(0, Math.min(100, battery))
        sendEvent(name: "battery", value: battery, unit: "%")
    }
    sendEvent(name: "deliveryStatus", value: "current")
    state.lastDelivery = now()
}

def refresh() {
    updateDistanceFromHome(device.currentValue("latitude"), device.currentValue("longitude"))
    updateSpeed(state.speedMetersPerSecond)
    long staleMinutes = settings.staleAfterMinutes instanceof Number ? (settings.staleAfterMinutes as Number).longValue() : 45L
    if (staleMinutes < 15L) staleMinutes = 15L
    long maximumAge = staleMinutes * 60_000L
    if (state.lastDelivery && now() - (state.lastDelivery as Long) > maximumAge) {
        sendEvent(name: "deliveryStatus", value: "stale")
    }
}

def clearLocation() {
    sendEvent(name: "distanceFromHome", value: "", unit: settings.distanceUnits == "km" ? "km" : "mi")
    sendEvent(name: "speed", value: "", unit: settings.distanceUnits == "km" ? "km/h" : "mph")
    sendEvent(name: "charging", value: "unknown")
    state.remove("speedMetersPerSecond")
    sendEvent(name: "latitude", value: "")
    sendEvent(name: "longitude", value: "")
    sendEvent(name: "accuracy", value: 0, unit: "m")
    sendEvent(name: "place", value: "unavailable")
    sendEvent(name: "lastLocationAt", value: "")
    sendEvent(name: "presence", value: "not present")
    sendEvent(name: "deliveryStatus", value: "disabled")
    state.remove("lastDelivery")
}

private boolean validCoordinate(value, int maximum) {
    try {
        double coordinate = Double.parseDouble(value.toString())
        return Double.isFinite(coordinate) && Math.abs(coordinate) <= maximum
    } catch (Exception ignored) { return false }
}

private boolean validSpeed(value) {
    return value instanceof Number && Double.isFinite(value.doubleValue()) && value >= 0 && value <= 100
}

private void updateSpeed(value) {
    String unit = settings.distanceUnits == "km" ? "km/h" : "mph"
    BigDecimal speed = validSpeed(value) ? new BigDecimal((value.doubleValue() * (unit == "km/h" ? 3.6d : 2.2369362921d)).toString()).setScale(1, java.math.RoundingMode.HALF_UP) : null
    sendEvent(name: "speed", value: speed == null ? "" : speed, unit: unit)
}

private void updateDistanceFromHome(latitude, longitude) {
    String unit = settings.distanceUnits == "km" ? "km" : "mi"
    // Home is the hub's configured coordinates, not whichever saved Place is occupied.
    if (!validCoordinate(latitude, 90) || !validCoordinate(longitude, 180) || !validCoordinate(location?.latitude, 90) || !validCoordinate(location?.longitude, 180)) {
        sendEvent(name: "distanceFromHome", value: "", unit: unit)
        return
    }
    double lat1 = Math.toRadians(Double.parseDouble(latitude.toString()))
    double lat2 = Math.toRadians(Double.parseDouble(location.latitude.toString()))
    double deltaLat = lat2 - lat1
    double deltaLon = Math.toRadians(Double.parseDouble(location.longitude.toString()) - Double.parseDouble(longitude.toString()))
    double a = Math.pow(Math.sin(deltaLat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2)
    a = Math.max(0d, Math.min(1d, a))
    double kilometers = 6371.0088d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a))
    BigDecimal distance = new BigDecimal((unit == "km" ? kilometers : kilometers / 1.609344d).toString()).setScale(2, java.math.RoundingMode.HALF_UP)
    sendEvent(name: "distanceFromHome", value: distance, unit: unit)
}

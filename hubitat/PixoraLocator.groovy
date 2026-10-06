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
        attribute "locationHealth", "string"
        attribute "movement", "string"
        attribute "atPlaceSince", "string"
        attribute "sharingMode", "string"
        attribute "batteryUnrestricted", "enum", ["yes", "no", "unknown"]
        attribute "locatorTile", "string"
        attribute "locatorDetails", "string"
        attribute "locatorMap", "string"
        attribute "locatorMember", "string"
        attribute "locatorAvatar", "string"

        command "updateLocation", [[name: "Location payload", type: "STRING", description: "Pixora Locator JSON payload"]]
        command "clearLocation"
    }
}

preferences {
    input name: "smallTileLine1", type: "enum", title: "Smaller tile - Line 1", options: [none: "None", since: "At Place since", battery: "Battery", unrestricted: "Battery unrestricted", power: "Charging status", distance: "Distance from Home", accuracy: "GPS accuracy", report: "Latest report", movement: "Movement", sharing: "Sharing mode", speed: "Speed"], defaultValue: "battery"
    input name: "smallTileLine2", type: "enum", title: "Smaller tile - Line 2", options: [none: "None", since: "At Place since", battery: "Battery", unrestricted: "Battery unrestricted", power: "Charging status", distance: "Distance from Home", accuracy: "GPS accuracy", report: "Latest report", movement: "Movement", sharing: "Sharing mode", speed: "Speed"], defaultValue: "distance"
    input name: "smallTileLine3", type: "enum", title: "Smaller tile - Line 3", options: [none: "None", since: "At Place since", battery: "Battery", unrestricted: "Battery unrestricted", power: "Charging status", distance: "Distance from Home", accuracy: "GPS accuracy", report: "Latest report", movement: "Movement", sharing: "Sharing mode", speed: "Speed"], defaultValue: "speed"
    input name: "smallTileAvatar", type: "bool", title: "Show avatar on smaller tile", defaultValue: true
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
    for (String name in ["movement", "sharingMode", "batteryUnrestricted", "locationHealth"]) {
        sendEvent(name: name, value: device.currentValue(name) ?: "unknown")
    }
    updateDistanceFromHome(device.currentValue("latitude"), device.currentValue("longitude"))
    updateSpeed(state.speedMetersPerSecond)
    String existingSince = (device.currentValue("atPlaceSince") ?: "").toString()
    if (existingSince ==~ /^\d{4}-\d{2}-\d{2}T.*/) {
        sendEvent(name: "atPlaceSince", value: readablePlaceTime(existingSince))
    }
    unschedule()
    runEvery1Minute("refresh")
    updateDashboardTiles()
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
    long reportAge = 0L
    try {
        long capturedMillis = java.time.OffsetDateTime.parse(capturedAt).toInstant().toEpochMilli()
        reportAge = Math.max(0L, now() - capturedMillis)
        TimeZone hubTimeZone = location?.timeZone ?: TimeZone.getDefault()
        displayedAt = new Date(capturedMillis).format("M-d-yyyy h:mm a", hubTimeZone).toLowerCase()
    } catch (Exception ignored) {
        // Keep the original timestamp if an older sender provides an unexpected format.
    }
    sendEvent(name: "latitude", value: locationData.latitude.toString())
    state.memberName = (locationData.member ?: device.displayName ?: "Locator").toString().take(40)
    String avatarUrl = (locationData.avatarUrl ?: "").toString()
    state.avatarUrl = avatarUrl ==~ /^https:\/\/planner\.pixorahq\.com\/api\/locator\/tile-avatar\?t=[A-Za-z0-9_.-]{1,650}$/ ? avatarUrl : ""
    state.avatarDeadline = state.avatarUrl ? now() + 23 * 60 * 60_000L : 0L
    sendEvent(name: "longitude", value: locationData.longitude.toString())
    updateDistanceFromHome(locationData.latitude, locationData.longitude)
    state.speedMetersPerSecond = validSpeed(locationData.speed) ? locationData.speed : null
    updateSpeed(state.speedMetersPerSecond)
    sendEvent(name: "charging", value: locationData.charging instanceof Boolean ? (locationData.charging ? "charging" : "not charging") : "unknown")
    sendEvent(name: "batteryUnrestricted", value: locationData.batteryUnrestricted instanceof Boolean ? (locationData.batteryUnrestricted ? "yes" : "no") : "unknown")
    sendEvent(name: "sharingMode", value: locationData.sharingMode in ["precise", "approximate", "off"] ? locationData.sharingMode : "unknown")
    sendEvent(name: "movement", value: (locationData.movement ?: "unknown").toString())
    sendEvent(name: "atPlaceSince", value: readablePlaceTime(locationData.atPlaceSince))
    sendEvent(name: "locationHealth", value: locationData.locationHealth in ["current", "delayed", "stale", "one-time"] ? locationData.locationHealth : "current")
    state.oneTimeReport = locationData.locationHealth == "one-time"
    state.healthAgeOffset = Math.max(reportAge, locationData.locationHealth == "stale" ? 30 * 60_000L + 1 : locationData.locationHealth == "delayed" ? 5 * 60_000L + 1 : 0L)
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
    refresh()
}

def refresh() {
    updateDistanceFromHome(device.currentValue("latitude"), device.currentValue("longitude"))
    updateSpeed(state.speedMetersPerSecond)
    long staleMinutes = settings.staleAfterMinutes instanceof Number ? (settings.staleAfterMinutes as Number).longValue() : 45L
    if (staleMinutes < 15L) staleMinutes = 15L
    long maximumAge = staleMinutes * 60_000L
    if (state.lastDelivery) {
        long age = now() - (state.lastDelivery as Long) + ((state.healthAgeOffset ?: 0L) as Long)
        sendEvent(name: "locationHealth", value: age > 30 * 60_000L ? "stale" : age > 5 * 60_000L ? "delayed" : state.oneTimeReport ? "one-time" : "current")
    }
    if (state.lastDelivery && now() - (state.lastDelivery as Long) > maximumAge) {
        sendEvent(name: "deliveryStatus", value: "stale")
    }
    updateDashboardTiles()
}

def clearLocation() {
    sendEvent(name: "locationHealth", value: "off")
    sendEvent(name: "sharingMode", value: "off")
    sendEvent(name: "movement", value: "unavailable")
    sendEvent(name: "atPlaceSince", value: "")
    sendEvent(name: "batteryUnrestricted", value: "unknown")
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
    state.remove("healthAgeOffset")
    state.remove("oneTimeReport")
    state.remove("avatarUrl")
    state.remove("avatarDeadline")
    updateDashboardTiles()
}

private String tileText(value, int maximum = 32) {
    String text = (value == null || value.toString() == "" ? "Unknown" : value.toString()).take(maximum)
    String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    while (escaped.getBytes("UTF-8").length > 96) {
        text = text.take(text.length() - 1)
        escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
    return escaped
}

private void updateDashboardTiles() {
    boolean active = device.currentValue("sharingMode") != "off" && device.currentValue("deliveryStatus") != "disabled"
    String name = tileText(state.memberName ?: device.displayName ?: "Locator", 24)
    sendEvent(name: 'locatorMember', value: (state.memberName ?: device.displayName ?: 'Locator').toString().take(40))
    String currentAvatar = active && state.avatarUrl && now() < ((state.avatarDeadline ?: 0L) as Long) ? state.avatarUrl.toString() : ''
    if (device.currentValue('locatorAvatar') != currentAvatar) sendEvent(name: 'locatorAvatar', value: currentAvatar)
    String place = "Sharing off"
    if (active) {
        String rawPlace = (device.currentValue('place') ?: '').toString()
        place = rawPlace in ['', 'not_home', 'unavailable', 'unknown'] ? (device.currentValue('movement') == 'moving' ? 'On the Move' : 'Unknown Place') : tileText(rawPlace, 32)
    }
    String health = tileText(device.currentValue("locationHealth"), 12)
    String battery = active && device.currentValue("battery") instanceof Number ? device.currentValue("battery").toString() + "%" : "Unknown"
    String charging = active ? tileText(device.currentValue("charging"), 12) : "Unknown"
    String units = settings.distanceUnits == "km" ? "km" : "mi"
    String distance = active ? tileText(device.currentValue("distanceFromHome"), 8) : "Unknown"
    String speed = active ? tileText(device.currentValue("speed"), 8) : "Unknown"
    String since = active ? tileText(device.currentValue("atPlaceSince"), 28) : "Unknown"
    String report = active ? tileText(device.currentValue("lastLocationAt"), 24) : "Unknown"
    String movement = active ? tileText(device.currentValue("movement"), 16) : "Unknown"
    String tone = health == "current" ? "#66ddbc" : health == "off" ? "#aab8ce" : "#ffcc72"
    String start = "<div class='pixora-locator' style='background:#10213b;color:#f5f8ff;padding:12px;text-align:left;font:14px Arial'>"
    String header = "<b style='font-size:22px'>${name}</b><br><span style='color:${tone}'>${place} &middot; ${health}</span>"
    // A background initial remains visible if the browser cannot load the private photo.
    String photo = "<span style='float:right;position:relative;background:#29496b;border-radius:50%;width:48px;height:48px;text-align:center;line-height:48px'>${tileText((state.memberName ?: device.displayName ?: 'L').toString().take(1), 1)}"
    if (active && state.avatarUrl && now() < ((state.avatarDeadline ?: 0L) as Long)) {
        photo += "<img src='${state.avatarUrl}' width='48' height='48' alt='' style='position:absolute;left:0;top:0;border-radius:50%'>"
    }
    photo += "</span>"
    String table = "<table style='width:100%;font:inherit;line-height:1.6;margin-top:12px'>"
    String accuracy = active && device.currentValue('accuracy') instanceof Number ? new BigDecimal(device.currentValue('accuracy').toString()).setScale(1, java.math.RoundingMode.HALF_UP).toString() : "Unknown"
    List selected = [settings.smallTileLine1 ?: 'battery', settings.smallTileLine2 ?: 'distance', settings.smallTileLine3 ?: 'speed']
    Map rows = [battery: ["Battery", battery + (charging == 'charging' ? ' + power' : '')], distance: ["From home", "${distance} ${units}"], speed: ["Speed", "${speed} ${units == 'km' ? 'km/h' : 'mph'}"], power: ["Power", charging], movement: ["Movement", movement], since: ["Since", since], report: ["Updated", report], accuracy: ["GPS", "${accuracy} m"], sharing: ["Sharing", tileText(device.currentValue('sharingMode'), 12)], unrestricted: ["Unrestricted", active ? tileText(device.currentValue('batteryUnrestricted'), 7) : "Unknown"]]
    List orderedRows = rows.values().sort { a, b -> a[0].toString().compareToIgnoreCase(b[0].toString()) }
    List smallRows = selected.findAll { rows.containsKey(it) }.collect { key -> rows[key] }.collect { row -> "<tr><td>${row[0]}</td><td><b>${row[1]}</b></td></tr>" }
    String smallPhoto = settings.smallTileAvatar == false ? "" : photo
    String tile = start + smallPhoto + header + table + smallRows.join('') + "</table></div>"
    if (tile.getBytes("UTF-8").length > 1024) tile = start + smallPhoto + "<b>${name}</b><br>${place} &middot; ${health}" + table + smallRows.join('') + "</table></div>"
    // Preserve chosen stats ahead of the photo when an image link consumes the budget.
    if (tile.getBytes("UTF-8").length > 1024) {
        smallPhoto = ""
        tile = start + header + table + smallRows.join('') + "</table></div>"
    }
    int omitted = 0
    while (tile.getBytes("UTF-8").length > 1024 && smallRows) {
        smallRows.remove(smallRows.size() - 1)
        omitted++
        tile = start + header + table + smallRows.join('') + "</table><small>+${omitted} in details</small></div>"
    }
    // HTML permits omitted cell/row end tags; keep room for the private avatar.
    List detailRows = orderedRows.collect { row -> "<tr><td>${row[0]}<td>${row[1]}" }
    String detailPhoto = currentAvatar ? "<img src='${currentAvatar}' width='48' height='48' alt='' style='float:right;border-radius:50%'>" : ""
    String detailHeader = "<b>${name}</b><br><span style='color:${tone}'>${place} &middot; ${health}</span>"
    String details = start + detailPhoto + detailHeader + table + detailRows.join('') + "</table></div>"
    // Keep all stats ahead of an unusually long image ticket.
    if (details.getBytes("UTF-8").length > 1024) {
        detailPhoto = ""
        details = start + detailHeader + table + detailRows.join('') + "</table></div>"
    }
    int hiddenDetails = 0
    while (details.getBytes("UTF-8").length > 1024 && detailRows) {
        detailRows.remove(detailRows.size() - 1)
        hiddenDetails++
        details = start + detailPhoto + detailHeader + table + detailRows.join('') + "</table><small>+${hiddenDetails} omitted: tile limit</small></div>"
    }
    if (device.currentValue("locatorTile") != tile) sendEvent(name: "locatorTile", value: tile)
    if (device.currentValue("locatorDetails") != details) sendEvent(name: "locatorDetails", value: details)
    updateDashboardMap(active, name, place, health)
}

private String mapCoordinate(double value) {
    return String.format(java.util.Locale.US, "%.6f", value)
}

private void updateDashboardMap(boolean active, String name, String place, String health) {
    String mapTile = "<div class='pixora-locator pixora-map' style='background:#10213b;color:#f5f8ff;padding:10px;text-align:left;font:14px Arial'><div><b>${name}</b><br>${place} &middot; ${health}</div>"
    def latitude = device.currentValue('latitude')
    def longitude = device.currentValue('longitude')
    if (active && validCoordinate(latitude, 90) && validCoordinate(longitude, 180)) {
        double lat = Double.parseDouble(latitude.toString())
        double lon = Double.parseDouble(longitude.toString())
        // Web Mercator cannot display the poles; don't silently move the person's pin.
        if (Math.abs(lat) <= 85.051128) {
            boolean approximate = device.currentValue('sharingMode') == 'approximate'
            double span = approximate ? 0.04d : 0.008d
            double lonSpan = span * 1.6d / Math.max(0.1d, Math.cos(Math.toRadians(lat)))
            String bbox = [Math.max(-180d, lon - lonSpan), Math.max(-85.051128d, lat - span), Math.min(180d, lon + lonSpan), Math.min(85.051128d, lat + span)].collect { mapCoordinate(it as double) }.join(',')
            String marker = mapCoordinate(lat) + ',' + mapCoordinate(lon)
            String url = "https://www.openstreetmap.org/export/embed.html?bbox=${bbox}&amp;layer=mapnik&amp;marker=${marker}"
            mapTile += "<iframe src='${url}' title='Shared location map' referrerpolicy='no-referrer' loading='lazy' style='width:100%;height:220px;border:0;border-radius:10px;margin-top:8px'></iframe><small>${approximate ? 'Approximate area center' : 'Last reported position'}</small>"
        } else {
            mapTile += '<p>Map unavailable near the poles.</p>'
        }
    } else {
        mapTile += '<p>No shared location available.</p>'
    }
    mapTile += '</div>'
    if (device.currentValue('locatorMap') != mapTile) sendEvent(name: 'locatorMap', value: mapTile)
}

private String readablePlaceTime(value) {
    if (!value) return ""
    try {
        long timestamp = java.time.OffsetDateTime.parse(value.toString()).toInstant().toEpochMilli()
        TimeZone hubTimeZone = location?.timeZone ?: TimeZone.getDefault()
        java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.US)
        formatter.setTimeZone(hubTimeZone)
        return formatter.format(new Date(timestamp))
    } catch (Exception ignored) {
        return "Unknown"
    }
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

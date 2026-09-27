import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest

definition(
    name: "Pixora Locator OwnTracks Importer",
    namespace: "pixorahq",
    author: "PixoraHQ",
    description: "Privately receives an OwnTracks Hubitat region list and creates a one-time Pixora Locator migration file.",
    category: "Convenience",
    oauth: true,
    singleInstance: true
)

preferences {
    page(name: "mainPage", title: "Pixora Locator OwnTracks Importer", install: true, uninstall: true)
}

@Field static final Integer MAX_CAPTURED_PLACES = 75
@Field static final Integer MAX_EXPORTED_PLACES = 50
@Field static final Integer CAPTURE_SECONDS = 600
@Field static final Integer RETENTION_SECONDS = 3600
@Field static final Integer MAX_BODY_CHARACTERS = 262144

def mainPage() {
    ensureIdentity()
    clearExpiredCapture()
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true, refreshInterval: 10) {
        section("One-time OwnTracks Place transfer") {
            paragraph "This separate Pixora app uses OwnTracks' existing Secondary Hub transfer. It does not change OwnTracks and never reads another app's private data. Live location messages are rejected and never stored."
            if (!state.accessToken) {
                paragraph "Enable OAuth for this app in Apps Code, then reopen it so Hubitat can create the private receiver URL."
            } else {
                paragraph "1. Select Arm receiver below.\n2. In OwnTracks, open Link Secondary Hub, paste the receiver URL, and enable the secondary hub.\n3. Open Configure Regions and select Send Region List to Secondary Hub.\n4. Disable the OwnTracks secondary hub and clear its URL immediately after the list is received."
                paragraph "Receiver URL (keep private):\n${captureUrl()}"
                input name: "armCaptureButton", type: "button", title: captureArmed() ? "Receiver armed — waiting for Places" : "Arm receiver for 10 minutes"
            }
        }
        section("Captured Places") {
            List places = currentPlaces()
            if (!places) {
                paragraph state.captureMessage ?: "No OwnTracks Places have been captured."
            } else {
                paragraph "${places.size()} usable ${places.size() == 1 ? 'Place' : 'Places'} received. The temporary copy expires automatically one hour after capture."
                Map choices = places.collectEntries { [(it.waypointId.toString()): "${it.name} (${Math.round((it.radiusMeters as Number).doubleValue())} m)"] }
                input name: "selectedWaypointIds", type: "enum", title: "Choose up to ${MAX_EXPORTED_PLACES} Places", options: choices, multiple: true, required: true, submitOnChange: true
                List selected = selectedIds()
                if (selected && selected.size() <= MAX_EXPORTED_PLACES) {
                    href url: exportUrl(), title: "Download Locator import file", description: "Creates a checksummed .pixora file containing only the selected Place definitions.", style: "external"
                } else if (selected?.size() > MAX_EXPORTED_PLACES) {
                    paragraph "Choose no more than ${MAX_EXPORTED_PLACES} Places."
                } else {
                    paragraph "Choose at least one Place before downloading."
                }
                input name: "clearCaptureButton", type: "button", title: "Clear temporary capture"
            }
        }
        section("Privacy") {
            paragraph "The importer accepts only an OwnTracks waypoint list while manually armed. It excludes +follow regions, invalid coordinates, pending deletions, and unsupported fields. It never stores member locations, history, alerts, credentials, or phone details."
        }
    }
}

def installed() {
    ensureIdentity()
}

def updated() {
    ensureIdentity()
}

def uninstalled() {
    try {
        revokeAccessToken()
    } catch (Exception ignored) {
    }
}

def appButtonHandler(String buttonName) {
    switch (buttonName) {
        case "armCaptureButton":
            state.armedUntil = now() + (CAPTURE_SECONDS * 1000L)
            state.captureMessage = "Receiver armed for 10 minutes."
            break
        case "clearCaptureButton":
            clearCapture()
            break
    }
}

private void ensureIdentity() {
    if (!(state.sourceInstanceId ==~ /[a-f0-9]{32}/)) {
        state.sourceInstanceId = UUID.randomUUID().toString().replace("-", "").toLowerCase()
    }
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (Exception ignored) {
            state.captureMessage = "OAuth is not enabled for this app."
        }
    }
}

private Boolean captureArmed() {
    return (state.armedUntil instanceof Number) && (state.armedUntil as Long) >= now()
}

private List currentPlaces() {
    if ((state.captureExpiresAt instanceof Number) && (state.captureExpiresAt as Long) < now()) {
        clearCapture()
    }
    return state.capturedPlaces instanceof List ? state.capturedPlaces : []
}

private List selectedIds() {
    if (settings.selectedWaypointIds instanceof Collection) {
        return settings.selectedWaypointIds.collect { it.toString() }.unique()
    }
    return settings.selectedWaypointIds ? [settings.selectedWaypointIds.toString()] : []
}

private String captureUrl() {
    return "${getFullLocalApiServerUrl()}/owntracks?access_token=${state.accessToken}"
}

private String exportUrl() {
    return "${getFullLocalApiServerUrl()}/export?access_token=${state.accessToken}"
}

private void clearCapture() {
    state.remove("capturedPlaces")
    state.remove("captureExpiresAt")
    state.remove("armedUntil")
    state.captureMessage = "Temporary OwnTracks Place data cleared."
    app.removeSetting("selectedWaypointIds")
    unschedule("clearExpiredCapture")
}

def clearExpiredCapture() {
    if ((state.captureExpiresAt instanceof Number) && (state.captureExpiresAt as Long) <= now()) {
        clearCapture()
    }
}

private Map normalizeWaypoint(Object raw) {
    Set allowedFields = ["_type", "desc", "lat", "lon", "rad", "tst"] as Set
    if (!(raw instanceof Map) || !((raw as Map).keySet() - allowedFields).isEmpty()) return null
    String name = raw.desc instanceof CharSequence ? raw.desc.toString().trim().replaceAll(/\s+/, " ") : ""
    if (!name || name.startsWith("+") || name.codePointCount(0, name.length()) < 2 || name.codePointCount(0, name.length()) > 80 || name.find(/[\u0000-\u001f\u007f]/)) return null
    if (!(raw.lat instanceof Number) || !(raw.lon instanceof Number) || !(raw.rad instanceof Number) || !(raw.tst instanceof Number)) return null
    BigDecimal latitude = new BigDecimal(raw.lat.toString())
    BigDecimal longitude = new BigDecimal(raw.lon.toString())
    BigDecimal radius = new BigDecimal(raw.rad.toString())
    Long waypoint = (raw.tst as Number).longValue()
    if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || latitude == 999 || longitude == 999 || radius < 50 || radius > 5000 || waypoint <= 0) return null
    return [
        waypointId: waypoint.toString(),
        name: name,
        latitude: latitude.doubleValue(),
        longitude: longitude.doubleValue(),
        radiusMeters: radius.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    ]
}

def receiveOwnTracks() {
    if (!captureArmed()) {
        return render(contentType: "application/json", data: JsonOutput.toJson([ok: false, error: "receiver_not_armed"]), status: 403)
    }
    String body = request.body?.toString() ?: ""
    if (!body || body.length() > MAX_BODY_CHARACTERS) {
        return render(contentType: "application/json", data: JsonOutput.toJson([ok: false, error: "invalid_request"]), status: 400)
    }
    Map incoming
    try {
        incoming = new JsonSlurper().parseText(body) as Map
    } catch (Exception ignored) {
        return render(contentType: "application/json", data: JsonOutput.toJson([ok: false, error: "invalid_json"]), status: 400)
    }
    Set allowedEnvelopeFields = ["_type", "waypoints"] as Set
    if (incoming?._type != "waypoints" || !(incoming?.waypoints instanceof List) || !(incoming.keySet() - allowedEnvelopeFields).isEmpty()) {
        return render(contentType: "application/json", data: JsonOutput.toJson([ok: false, ignored: true]), status: 422)
    }
    List normalized = incoming.waypoints.collect { normalizeWaypoint(it) }.findAll { it != null }
    LinkedHashMap unique = [:]
    normalized.each { unique[it.waypointId] = it }
    List places = unique.values().toList().sort { left, right -> left.name.toString().compareToIgnoreCase(right.name.toString()) }
    if (!places || places.size() > MAX_CAPTURED_PLACES) {
        return render(contentType: "application/json", data: JsonOutput.toJson([ok: false, error: places ? "too_many_places" : "no_usable_places"]), status: 400)
    }
    state.capturedPlaces = places
    state.captureExpiresAt = now() + (RETENTION_SECONDS * 1000L)
    state.remove("armedUntil")
    Integer skipped = incoming.waypoints.size() - places.size()
    state.captureMessage = "Received ${places.size()} OwnTracks ${places.size() == 1 ? 'Place' : 'Places'}.${skipped > 0 ? " Skipped ${skipped} synthetic, duplicate, invalid, or unsupported ${skipped == 1 ? 'entry' : 'entries'}." : ''}"
    app.updateSetting("selectedWaypointIds", [type: "enum", value: places.take(MAX_EXPORTED_PLACES)*.waypointId])
    runIn(RETENTION_SECONDS, "clearExpiredCapture", [overwrite: true])
    return render(contentType: "application/json", data: JsonOutput.toJson([ok: true, accepted: places.size()]), status: 200)
}

private String sha256(String value) {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"))
    return digest.collect { String.format("%02x", it & 0xff) }.join()
}

def exportMigration() {
    List places = currentPlaces()
    List chosenIds = selectedIds()
    if (!places || !chosenIds || chosenIds.size() > MAX_EXPORTED_PLACES) {
        return render(contentType: "application/json", data: JsonOutput.toJson([ok: false, error: "invalid_selection"]), status: 400)
    }
    Set chosen = chosenIds as Set
    List selected = places.findAll { chosen.contains(it.waypointId.toString()) }
    if (selected.size() != chosen.size()) {
        return render(contentType: "application/json", data: JsonOutput.toJson([ok: false, error: "invalid_selection"]), status: 400)
    }
    Map payload = [
        format: "pixora-locator-hubitat-owntracks-migration",
        version: 1,
        createdAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone("UTC")),
        sourceInstanceId: state.sourceInstanceId,
        places: selected.collect { [waypointId: it.waypointId, name: it.name, latitude: it.latitude, longitude: it.longitude, radiusMeters: it.radiusMeters] }
    ]
    String payloadJson = JsonOutput.toJson(payload)
    Map document = [
        format: "pixora-locator-hubitat-owntracks-migration-envelope",
        version: 1,
        payload: payloadJson,
        sha256: sha256(payloadJson)
    ]
    String filename = "pixora-locator-owntracks-${new Date().format('yyyy-MM-dd', TimeZone.getTimeZone('UTC'))}.pixora"
    return render(
        contentType: "application/vnd.pixora.locator-migration+json",
        data: JsonOutput.prettyPrint(JsonOutput.toJson(document)),
        status: 200,
        headers: [
            "Content-Disposition": "attachment; filename=\"${filename}\"",
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff"
        ]
    )
}

mappings {
    path("/owntracks") {
        action: [POST: "receiveOwnTracks"]
    }
    path("/export") {
        action: [GET: "exportMigration"]
    }
}

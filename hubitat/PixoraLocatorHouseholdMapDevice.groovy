metadata {
    definition(name: "Pixora Locator Household Map", namespace: "pixorahq", author: "PixoraHQ") {
        capability "Sensor"
        capability "Refresh"
        attribute "locatorMap", "string"
        attribute "memberCount", "number"
        command "setMap", [[name: "Private map URL", type: "STRING"], [name: "Visible members", type: "NUMBER"]]
        command "clearMap"
    }
}
def installed() { clearMap() }
def updated() { refresh() }
def refresh() { if (parent) parent.refreshMap() }
def setMap(String url, count) {
    // Only the companion app's authenticated cloud endpoint is accepted.
    if (!(url ==~ /^https:\/\/cloud\.hubitat\.com\/api\/[A-Za-z0-9\/_-]+\/map\?access_token=[A-Za-z0-9_-]{16,128}$/)) {
        clearMap()
        return
    }
    String escaped = url.replace('&', '&amp;').replace("'", '&#39;')
    String tile = "<div class='pixora-locator pixora-map' style='background:#10213b;color:#f5f8ff;padding:10px;font:14px Arial'><div><b>Household map</b></div><iframe src='${escaped}' title='Household locations' referrerpolicy='no-referrer' style='width:100%;height:260px;border:0;border-radius:10px'></iframe></div>"
    if (device.currentValue('locatorMap') != tile) sendEvent(name: 'locatorMap', value: tile)
    sendEvent(name: 'memberCount', value: Math.max(0, (count as Number).intValue()))
}
def clearMap() {
    sendEvent(name: 'locatorMap', value: "<div class='pixora-locator' style='background:#10213b;color:#fff;padding:12px'>Household map unavailable. Open the companion app and select your Locator devices.</div>")
    sendEvent(name: 'memberCount', value: 0)
}

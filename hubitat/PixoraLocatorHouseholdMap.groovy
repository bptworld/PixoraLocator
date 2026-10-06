import groovy.json.JsonOutput

definition(name: "Pixora Locator Household Map", namespace: "pixorahq", author: "PixoraHQ",
    description: "Creates one private household map device from your selected Pixora Locator phones.", category: "Convenience", oauth: true)
preferences {
    page(name: "mainPage", title: "Household map", install: true, uninstall: true)
}
def mainPage() {
    dynamicPage(name: "mainPage", title: "Pixora Locator Household Map", install: true, uninstall: true) {
        section("Phones to display") {
            input name: "phones", type: "capability.sensor", title: "Select Pixora Locator phone devices", multiple: true, required: true
            input name: "mapName", type: "text", title: "New map device name", defaultValue: "Pixora Household Map"
            paragraph "Install the Household Map driver first, enable OAuth on this app, and select only Pixora Locator phones. Done creates the new map device automatically."
        }
        section("Privacy") {
            paragraph "The dashboard reads only these devices' latest shared locations. Sharing-off phones are excluded; approximate and stale readings are labeled. OpenStreetMap receives displayed map areas. Leaflet assets load from unpkg. Keep dashboard and map URLs private: the app access token grants access to this selected-device map. No history is stored and no GPS is requested."
        }
    }
}
def installed() { initialize() }
def updated() { unsubscribe(); unschedule(); initialize() }
private String childId() { "pixora-household-map-${app.id}" }
private void initialize() {
    if (!state.accessToken) {
        try { createAccessToken() } catch (Exception ignored) {
            log.warn "Enable OAuth for Pixora Locator Household Map in Apps Code."
            return
        }
    }
    def child = getChildDevice(childId())
    if (!child) child = addChildDevice("pixorahq", "Pixora Locator Household Map", childId(), [label: settings.mapName ?: "Pixora Household Map", isComponent: false])
    if (settings.mapName) child.setLabel(settings.mapName)
    if (settings.phones) {
        ["lastLocationAt", "locationHealth", "sharingMode", "locatorAvatar"].each { attribute -> subscribe(settings.phones, attribute, "phoneChanged") }
    }
    refreshMap()
}
def phoneChanged(evt) { runIn(1, "refreshMap", [overwrite: true]) }
def refreshMap() {
    def child = getChildDevice(childId())
    if (!child) return
    if (!state.accessToken) { child.clearMap(); return }
    child.setMap("${getFullApiServerUrl()}/map?access_token=${state.accessToken}", visibleMembers().size())
}
def uninstalled() {
    unsubscribe()
    unschedule()
    try { revokeAccessToken() } catch (Exception ignored) {}
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}
private boolean authorized() {
    return state.accessToken && params.access_token?.toString() == state.accessToken.toString()
}
private boolean coordinate(value, double maximum) {
    try { double number = Double.parseDouble(value.toString()); return Double.isFinite(number) && Math.abs(number) <= maximum }
    catch (Exception ignored) { return false }
}
private List visibleMembers() {
    List members = []
    (settings.phones ?: []).each { phone ->
        if (!phone.hasAttribute("locatorMap") || !(phone.currentValue("sharingMode") in ['precise', 'approximate']) || phone.currentValue("deliveryStatus") == "disabled") return
        def lat = phone.currentValue("latitude")
        def lon = phone.currentValue("longitude")
        if (!coordinate(lat, 85.051128) || !coordinate(lon, 180)) return
        String photo = (phone.currentValue("locatorAvatar") ?: "").toString()
        if (!(photo ==~ /^https:\/\/planner\.pixorahq\.com\/api\/locator\/tile-avatar\?t=[A-Za-z0-9_.-]{1,650}$/)) photo = ""
        String place = (phone.currentValue("place") ?: "").toString()
        if (place in ["", "not_home", "unknown", "unavailable"]) place = phone.currentValue("movement") == "moving" ? "On the Move" : "Unknown Place"
        members << [id: phone.id.toString(), name: (phone.currentValue("locatorMember") ?: phone.displayName ?: "Locator").toString().take(40),
            latitude: Double.parseDouble(lat.toString()), longitude: Double.parseDouble(lon.toString()),
            place: place.take(80), health: (phone.currentValue("locationHealth") ?: "unknown").toString().take(16),
            approximate: phone.currentValue("sharingMode") == "approximate", avatar: photo,
            updated: (phone.currentValue("lastLocationAt") ?: "Unknown").toString().take(40)]
    }
    return members
}
def membersEndpoint() {
    if (!authorized()) return render(contentType: "application/json", data: '{"error":"unauthorized"}', status: 403)
    render(contentType: "application/json", data: JsonOutput.toJson([members: visibleMembers()]), headers: ["Cache-Control": "no-store"], status: 200)
}
def mapEndpoint() {
    if (!authorized()) return render(contentType: "text/plain", data: "Unauthorized", status: 403)
    render(contentType: "text/html", data: mapHtml(), headers: ["Cache-Control": "no-store"], status: 200)
}
mappings {
    path("/map") { action: [GET: "mapEndpoint"] }
    path("/members") { action: [GET: "membersEndpoint"] }
}
private String mapHtml() {
    return '''<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" crossorigin="">
<style>html,body,#map{height:100%;margin:0}body{background:#10213b;font:14px Arial}#bar{position:absolute;top:10px;right:10px;z-index:1000;background:#10213b;color:white;border-radius:10px;padding:8px;max-width:65%}button{padding:6px;margin-left:6px}.pin{width:48px;height:48px;border:4px solid #66ddbc;border-radius:50%;background:#10213b;color:white;display:grid;place-items:center;font:bold 22px Arial;box-sizing:border-box;box-shadow:0 2px 8px #333}.pin img{width:100%;height:100%;border-radius:50%;object-fit:cover}.leaflet-tooltip{font:bold 13px Arial;background:#10213b;color:white;border-color:#66ddbc}.leaflet-container{background:#10213b}</style></head>
<body><div id="map"></div><div id="bar"><span id="status">Loading locations...</span><button id="fit">Fit</button></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" crossorigin=""></script>
<script>
const status=document.getElementById('status');
if(!window.L){status.textContent='Map library unavailable. Check internet access.';}
else {
const map=L.map('map').setView([20,0],2), pins=new Map();
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap contributors</a>'}).addTo(map);
let fitted=false;
function fit(){const points=Array.from(pins.values()).map(p=>p.reportedPosition||p.getLatLng());if(points.length)map.fitBounds(L.latLngBounds(points),{padding:[50,50],maxZoom:16});}
function separatePins(){
 const groups=new Map();pins.forEach(pin=>{const p=pin.reportedPosition,key=p[0].toFixed(4)+','+p[1].toFixed(4);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(pin);});
 groups.forEach(group=>group.forEach((pin,index)=>{let position=pin.reportedPosition;if(group.length>1){const point=map.project(position),angle=2*Math.PI*index/group.length;position=map.unproject(point.add(L.point(44*Math.cos(angle),44*Math.sin(angle))));}pin.setLatLng(position);}));
}
map.on('zoomend',separatePins);
document.getElementById('fit').onclick=fit;
function icon(person,index){
 const node=document.createElement('div');node.className='pin';node.style.borderColor=person.health==='current'?'#66ddbc':'#ffcc72';node.textContent=person.name.charAt(0).toUpperCase();
 if(person.avatar.startsWith('https://planner.pixorahq.com/api/locator/tile-avatar?t=')){const img=document.createElement('img');img.src=person.avatar;img.referrerPolicy='no-referrer';img.alt='';img.onerror=()=>{img.remove();node.textContent=person.name.charAt(0).toUpperCase();};node.textContent='';node.append(img);}
 const wrapper=document.createElement('div');wrapper.append(node);return L.divIcon({html:wrapper,iconSize:[48,48],iconAnchor:[24,24],className:''});
}
async function refresh(){
 try {
 const url=new URL('members',window.location.href);url.search=window.location.search;
 const response=await fetch(url,{cache:'no-store',credentials:'omit'});if(!response.ok)throw new Error('Unavailable');
 const body=await response.json(), seen=new Set();
 body.members.forEach((person,index)=>{
 seen.add(person.id);let pin=pins.get(person.id);
 const position=[person.latitude,person.longitude];
 if(!pin){pin=L.marker(position).addTo(map);pins.set(person.id,pin);}else pin.setLatLng(position);
 pin.reportedPosition=position;
 pin.setIcon(icon(person,index));
 const label=document.createElement('span');label.textContent=person.name+' - '+person.place+(person.approximate?' (approximate)':'');
 pin.unbindTooltip();pin.bindTooltip(label,{permanent:true,direction:'bottom',offset:[0,20]});
 const popup=document.createElement('div');popup.textContent=person.name+' | '+person.place+' | '+person.health+' | Updated '+person.updated;pin.unbindPopup();pin.bindPopup(popup);
 });
 pins.forEach((pin,id)=>{if(!seen.has(id)){map.removeLayer(pin);pins.delete(id);}});
 status.textContent=pins.size?pins.size+' shared locations':'No shared locations';
 if(!fitted&&pins.size){fit();fitted=true;}if(!pins.size)fitted=false;separatePins();
 }catch(error){status.textContent='Updates unavailable - pins show last loaded positions';}
 setTimeout(refresh,15000);
}
new ResizeObserver(()=>map.invalidateSize()).observe(document.getElementById('map'));refresh();
}
</script></body></html>'''
}

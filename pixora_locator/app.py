#!/usr/bin/env python3
"""Pixora Locator Home Assistant App.

Reads only the household's current Pixora locations and publishes them through
Home Assistant's built-in Mobile App integration. No location history is kept.
"""

from __future__ import annotations

import json
import html
import logging
import os
import re
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

OPTIONS_PATH = Path("/data/options.json")
REGISTRATIONS_PATH = Path("/data/registrations.json")
PIXORA_URL = "https://planner.pixorahq.com/api/locator/home-assistant/current"
HA_API = "http://supervisor/core/api"
APP_VERSION = "1.0.3"
INGRESS_PORT = 8099
LOGGER = logging.getLogger("pixora_locator")
RUNNING = True


def read_json(path: Path, fallback: Any) -> Any:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return fallback
    return value


def write_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def request_json(url: str, *, token: str = "", method: str = "GET", payload: Any = None, timeout: int = 20) -> Any:
    headers = {"Accept": "application/json", "User-Agent": f"Pixora-Locator-HA/{APP_VERSION}"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if payload is not None:
        data = json.dumps(payload, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(1_000_000)
            return json.loads(body.decode()) if body else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read(2000).decode(errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {detail or exc.reason}") from exc
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        raise RuntimeError(str(exc)) from exc


def slug(value: str) -> str:
    clean = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")
    return clean[:80] or "phone"


class LocatorBridge:
    def __init__(self) -> None:
        options = read_json(OPTIONS_PATH, {})
        self.pixora_token = str(options.get("pairing_token") or "").strip()
        self.poll_seconds = max(15, min(300, int(options.get("poll_seconds") or 30)))
        self.supervisor_token = str(os.environ.get("SUPERVISOR_TOKEN") or "").strip()
        saved = read_json(REGISTRATIONS_PATH, {})
        self.registrations: dict[str, dict[str, str]] = saved if isinstance(saved, dict) else {}
        self.status_lock = threading.Lock()
        self.status: dict[str, Any] = {
            "state": "starting",
            "message": "Waiting for the first PixoraHQ sync.",
            "lastSync": "",
            "deviceCount": 0,
            "devices": [],
        }

    def set_status(self, **updates: Any) -> None:
        with self.status_lock:
            self.status.update(updates)

    def status_snapshot(self) -> dict[str, Any]:
        with self.status_lock:
            return dict(self.status)

    def validate(self) -> None:
        if not self.pixora_token.startswith("pha1."):
            raise RuntimeError("Open Pixora Locator, create a Home Assistant pairing token, and paste it into this App's Configuration tab.")
        if not self.supervisor_token:
            raise RuntimeError("Home Assistant did not provide Supervisor API access.")

    def register(self, device: dict[str, Any]) -> dict[str, str]:
        device_id = str(device.get("deviceId") or "")
        name = str(device.get("name") or "Pixora Locator")[:80]
        existing = self.registrations.get(device_id)
        if existing and existing.get("webhook_id"):
            return existing
        response = request_json(
            f"{HA_API}/mobile_app/registrations",
            token=self.supervisor_token,
            method="POST",
            payload={
                "device_id": f"pixora_locator_{device_id}",
                "app_id": "com.pixorahq.locator.homeassistant",
                "app_name": "Pixora Locator",
                "app_version": APP_VERSION,
                "device_name": name,
                "manufacturer": "PixoraHQ",
                "model": "Pixora Locator",
                "os_name": "Pixora Locator Home Assistant App",
                "os_version": APP_VERSION,
                "supports_encryption": False,
                "app_data": {},
            },
        )
        webhook_id = str(response.get("webhook_id") or "")
        if not webhook_id:
            raise RuntimeError(f"Home Assistant did not return a webhook for {name}.")
        registration = {"webhook_id": webhook_id, "name": name}
        self.registrations[device_id] = registration
        write_json(REGISTRATIONS_PATH, self.registrations)
        LOGGER.info("Registered managed Home Assistant tracker for %s", name)
        return registration

    def webhook(self, registration: dict[str, str], message_type: str, data: dict[str, Any]) -> None:
        request_json(
            f"{HA_API}/webhook/{registration['webhook_id']}",
            token=self.supervisor_token,
            method="POST",
            payload={"type": message_type, "data": data},
        )

    def update_registration_name(self, registration: dict[str, str], name: str) -> None:
        if registration.get("name") == name:
            return
        self.webhook(registration, "update_registration", {
            "app_version": APP_VERSION,
            "device_name": name,
            "manufacturer": "PixoraHQ",
            "model": "Pixora Locator",
            "os_version": APP_VERSION,
        })
        registration["name"] = name
        write_json(REGISTRATIONS_PATH, self.registrations)

    def update(self, device: dict[str, Any]) -> None:
        device_id = str(device.get("deviceId") or "")
        for attempt in range(2):
            registration = self.register(device)
            name = str(device.get("name") or "Pixora Locator")[:80]
            try:
                self.update_registration_name(registration, name)
                location = device.get("location") if isinstance(device.get("location"), dict) else None
                if not device.get("available") or not location:
                    self.webhook(registration, "update_location", {"location_name": "not_home"})
                    return
                accuracy = max(1, round(float(location.get("accuracy") or 1)))
                self.webhook(registration, "update_location", {
                    "gps": [float(location["latitude"]), float(location["longitude"])],
                    "gps_accuracy": accuracy,
                })
                return
            except RuntimeError as exc:
                if attempt or not ("HTTP 404" in str(exc) or "HTTP 410" in str(exc)):
                    raise
                LOGGER.warning("Home Assistant removed the tracker for %s; registering it again", name)
                self.registrations.pop(device_id, None)
                write_json(REGISTRATIONS_PATH, self.registrations)

    def poll(self) -> None:
        snapshot = request_json(PIXORA_URL, token=self.pixora_token)
        devices = snapshot.get("devices") if isinstance(snapshot, dict) else None
        if not isinstance(devices, list):
            raise RuntimeError("PixoraHQ returned an invalid current-location response.")
        seen = set()
        for device in devices:
            if not isinstance(device, dict) or not device.get("deviceId"):
                continue
            seen.add(str(device["deviceId"]))
            self.update(device)
        for device_id, registration in list(self.registrations.items()):
            if device_id not in seen:
                self.webhook(registration, "update_location", {"location_name": "not_home"})
        now = time.strftime("%Y-%m-%d %H:%M:%S %Z")
        visible_devices = [
            {
                "name": str(device.get("name") or "Pixora Locator")[:80],
                "sharing": str(device.get("sharing") or "off"),
                "available": bool(device.get("available")),
            }
            for device in devices
            if isinstance(device, dict) and device.get("deviceId")
        ]
        self.set_status(
            state="connected",
            message="Current Pixora Locator positions are syncing to Home Assistant.",
            lastSync=now,
            deviceCount=len(visible_devices),
            devices=visible_devices,
        )
        LOGGER.info("Synced %d Pixora Locator device%s", len(seen), "" if len(seen) == 1 else "s")

    def run(self) -> None:
        delay = 1
        while RUNNING:
            try:
                self.validate()
                self.poll()
                delay = self.poll_seconds
            except RuntimeError as exc:
                LOGGER.error("Sync failed: %s", exc)
                self.set_status(state="error", message=str(exc))
                delay = min(max(delay * 2, 15), 300)
            deadline = time.monotonic() + delay
            while RUNNING and time.monotonic() < deadline:
                time.sleep(min(1, max(0, deadline - time.monotonic())))


def status_page(bridge: LocatorBridge) -> bytes:
    status = bridge.status_snapshot()
    state = str(status.get("state") or "starting")
    badge = {"connected": "Connected", "error": "Needs attention"}.get(state, "Starting")
    rows = []
    for device in status.get("devices") or []:
        if not isinstance(device, dict):
            continue
        available = bool(device.get("available"))
        rows.append(
            '<div class="device"><div><strong>{}</strong><small>{}</small></div><span class="{}">{}</span></div>'.format(
                html.escape(str(device.get("name") or "Pixora Locator")),
                html.escape(str(device.get("sharing") or "off").replace("_", " ").title()),
                "online" if available else "offline",
                "Current" if available else "Not sharing",
            )
        )
    device_list = "".join(rows) or '<p class="empty">No Locator phones have been registered with this household yet.</p>'
    last_sync = html.escape(str(status.get("lastSync") or "Not synced yet"))
    message = html.escape(str(status.get("message") or ""))
    document = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="15"><title>Pixora Locator</title>
<style>
:root{{color-scheme:light dark;--bg:#eef6ff;--card:#fff;--ink:#092d63;--muted:#647da2;--line:#c9dcf4;--blue:#3d63ff;--mint:#4ee0c2;--bad:#b33b32}}
@media(prefers-color-scheme:dark){{:root{{--bg:#061626;--card:#102842;--ink:#f4f8ff;--muted:#a9bdd9;--line:#315477;--blue:#86a0ff;--mint:#58dfc2;--bad:#ff9289}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,sans-serif}}
main{{max-width:860px;margin:auto;padding:28px 18px 48px}}header{{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:24px}}
h1{{margin:0;font-size:clamp(28px,5vw,44px)}}.brand{{color:var(--blue);font-size:13px;font-weight:900;letter-spacing:.2em}}
.badge{{border-radius:999px;background:color-mix(in srgb,var(--mint) 28%,transparent);padding:9px 14px;font-weight:800}}
.error .badge{{background:color-mix(in srgb,var(--bad) 18%,transparent);color:var(--bad)}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:22px;padding:22px;box-shadow:0 10px 30px #001b4514;margin:16px 0}}
h2{{margin:0 0 8px;font-size:22px}}p{{margin:6px 0;color:var(--muted)}}.message{{font-size:18px;color:var(--ink)}}
.device{{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:14px 0;border-top:1px solid var(--line)}}
.device:first-of-type{{margin-top:10px}}.device small{{display:block;color:var(--muted)}}.device span{{font-weight:800;white-space:nowrap}}
.online{{color:#16866e}}.offline{{color:var(--muted)}}ol{{margin:12px 0 0;padding-left:24px}}code{{overflow-wrap:anywhere}}.empty{{padding:12px 0}}
</style></head><body><main class="{html.escape(state)}">
<header><div><div class="brand">PIXORA LOCATOR</div><h1>Home Assistant</h1></div><span class="badge">{html.escape(badge)}</span></header>
<section class="card"><h2>Connection status</h2><p class="message">{message}</p><p>Last successful sync: {last_sync}</p></section>
<section class="card"><h2>Household devices ({int(status.get('deviceCount') or 0)})</h2>{device_list}</section>
<section class="card"><h2>Setup</h2><ol><li>Open Pixora Locator on your phone.</li><li>Open <strong>Settings → Home Assistant App</strong> and create a pairing token.</li><li>Open this Home Assistant App's <strong>Configuration</strong> tab and paste the token.</li><li>Click <strong>Save</strong>, then restart the Home Assistant App.</li></ol><p>Only current locations are synchronized. Pixora Locator does not send location history or friend battery information.</p></section>
</main></body></html>"""
    return document.encode("utf-8")


def start_status_server(bridge: LocatorBridge) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            body = status_page(bridge)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: Any) -> None:
            LOGGER.debug("Home Assistant page: " + format, *args)

    server = ThreadingHTTPServer(("0.0.0.0", INGRESS_PORT), Handler)
    threading.Thread(target=server.serve_forever, name="pixora-locator-page", daemon=True).start()
    return server


def stop(_signum: int, _frame: Any) -> None:
    global RUNNING
    RUNNING = False


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    bridge = LocatorBridge()
    server = start_status_server(bridge)
    LOGGER.info("Pixora Locator Home Assistant page is ready on port %d", INGRESS_PORT)
    try:
        bridge.run()
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

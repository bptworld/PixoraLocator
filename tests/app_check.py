import importlib.util
import json
import os
import tempfile
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "pixora_locator" / "app.py"
CONFIG = SOURCE.with_name("config.yaml")
DOCKERFILE = SOURCE.with_name("Dockerfile")
ROOT_README = SOURCE.parents[1] / "README.md"
spec = importlib.util.spec_from_file_location("pixora_locator_ha_app", SOURCE)
assert spec and spec.loader
locator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(locator)


def run() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        locator.OPTIONS_PATH = root / "options.json"
        locator.REGISTRATIONS_PATH = root / "registrations.json"
        locator.OPTIONS_PATH.write_text(json.dumps({"pairing_token": "pha1.test", "poll_seconds": 30}), encoding="utf-8")
        os.environ["SUPERVISOR_TOKEN"] = "supervisor-test-token"
        calls = []
        snapshot = {
            "devices": [{
                "deviceId": "phone-1",
                "name": "Bryan's phone",
                "available": True,
                "location": {"latitude": 42.36, "longitude": -71.06, "accuracy": 9.4},
            }]
        }

        def fake_request(url, *, token="", method="GET", payload=None, timeout=20):
            calls.append((url, token, method, payload))
            if url == locator.PIXORA_URL:
                return snapshot
            if url.endswith("/mobile_app/registrations"):
                return {"webhook_id": "managed-webhook-1"}
            if "/webhook/" in url:
                return {}
            raise AssertionError(url)

        original_request = locator.request_json
        locator.request_json = fake_request
        try:
            bridge = locator.LocatorBridge()
            bridge.validate()
            bridge.poll()
            registration = next(call for call in calls if call[0].endswith("/mobile_app/registrations"))
            assert registration[3]["device_name"] == "Bryan's phone"
            assert registration[3]["device_id"] == "pixora_locator_phone-1"
            location = next(call for call in calls if call[3] and call[3].get("type") == "update_location")
            assert location[3]["data"] == {"gps": [42.36, -71.06], "gps_accuracy": 9}
            assert json.loads(locator.REGISTRATIONS_PATH.read_text())["phone-1"]["webhook_id"] == "managed-webhook-1"
            page = locator.status_page(bridge).decode()
            assert "Bryan&#x27;s phone" in page and "Connected" in page and "Last successful sync" in page
            assert "42.36" not in page and "-71.06" not in page and "pha1.test" not in page

            calls.clear()
            snapshot["devices"][0] = {"deviceId": "phone-1", "name": "Bryan's phone", "available": False}
            bridge.poll()
            assert not any(call[0].endswith("/mobile_app/registrations") for call in calls)
            unavailable = next(call for call in calls if call[3] and call[3].get("type") == "update_location")
            assert unavailable[3]["data"] == {"location_name": "not_home"}
        finally:
            locator.request_json = original_request

    config = CONFIG.read_text(encoding="utf-8")
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")
    assert 'version: "1.0.2"' in config and "ingress: true" in config and "ingress_port: 8099" in config
    assert "ARG BUILD_VERSION=1.0.2" in dockerfile and "apk upgrade --no-cache" in dockerfile
    installation = ROOT_README.read_text(encoding="utf-8")
    for instruction in ("Install App", "Repositories", "Check for updates", "https://github.com/bptworld/PixoraLocator"):
        assert instruction in installation


if __name__ == "__main__":
    run()
    print("Pixora Locator Home Assistant App checks passed.")

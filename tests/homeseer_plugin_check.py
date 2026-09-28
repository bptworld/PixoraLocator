from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "homeseer" / "HSPI.cs").read_text(encoding="utf-8")
models = (ROOT / "homeseer" / "Models.cs").read_text(encoding="utf-8")
readme = (ROOT / "homeseer" / "README.md").read_text(encoding="utf-8")

for marker in (
    'https://planner.pixorahq.com/api/locator/homeseer/current',
    'StartsWith("phs1."',
    'request.Headers[HttpRequestHeader.Authorization]',
    'request.AllowAutoRedirect = false',
    'MaxResponseBytes',
    'CreateGenericBinarySensor',
    '"latitude"',
    '"longitude"',
    '"accuracy"',
    '"battery"',
    '"movement"',
    '"sharing"',
    '"last-report"',
    '"since"',
    'SetBinary(device, "available", false)',
):
    assert marker in source, marker

assert 'JsonProperty("location")' in models
assert "does not request, download, or create a route or location-history database" in readme
assert "DeleteDevice" not in source
assert "http://" not in source

print("HomeSeer plugin checks passed")

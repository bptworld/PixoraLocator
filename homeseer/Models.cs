using System.Collections.Generic;
using Newtonsoft.Json;

namespace HSPI_PixoraLocator
{
    internal sealed class LocatorFeed
    {
        [JsonProperty("version")]
        public int Version { get; set; }

        [JsonProperty("generatedAt")]
        public string GeneratedAt { get; set; }

        [JsonProperty("devices")]
        public List<LocatorDevice> Devices { get; set; } = new List<LocatorDevice>();
    }

    internal sealed class LocatorDevice
    {
        [JsonProperty("deviceId")]
        public string DeviceId { get; set; }

        [JsonProperty("name")]
        public string Name { get; set; }

        [JsonProperty("sharing")]
        public string Sharing { get; set; }

        [JsonProperty("available")]
        public bool Available { get; set; }

        [JsonProperty("updatedAt")]
        public string UpdatedAt { get; set; }

        [JsonProperty("battery")]
        public int? Battery { get; set; }

        [JsonProperty("location")]
        public LocatorPoint Location { get; set; }

        [JsonProperty("currentPlace")]
        public LocatorPlace CurrentPlace { get; set; }

        [JsonProperty("motion")]
        public string Motion { get; set; }

        [JsonProperty("stationarySince")]
        public string StationarySince { get; set; }

        [JsonProperty("nearby")]
        public LocatorNearby Nearby { get; set; }
    }

    internal sealed class LocatorPoint
    {
        [JsonProperty("latitude")]
        public double Latitude { get; set; }

        [JsonProperty("longitude")]
        public double Longitude { get; set; }

        [JsonProperty("accuracy")]
        public double Accuracy { get; set; }

        [JsonProperty("capturedAt")]
        public string CapturedAt { get; set; }

        [JsonProperty("receivedAt")]
        public string ReceivedAt { get; set; }
    }

    internal sealed class LocatorPlace
    {
        [JsonProperty("name")]
        public string Name { get; set; }

        [JsonProperty("since")]
        public string Since { get; set; }
    }

    internal sealed class LocatorNearby
    {
        [JsonProperty("label")]
        public string Label { get; set; }
    }
}

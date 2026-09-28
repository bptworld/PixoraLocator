using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using HomeSeer.Jui.Types;
using HomeSeer.Jui.Views;
using HomeSeer.PluginSdk;
using HomeSeer.PluginSdk.Devices;
using HomeSeer.PluginSdk.Devices.Identification;
using HomeSeer.PluginSdk.Logging;
using Newtonsoft.Json;

namespace HSPI_PixoraLocator
{
    // HomeSeer requires this class name and namespace to match HSPI_PixoraLocator.exe.
    public sealed class HSPI : AbstractPlugin
    {
        private const string Endpoint = "https://planner.pixorahq.com/api/locator/homeseer/current";
        private const string SettingsPageId = "connection";
        private const string TokenSettingId = "pairing-token";
        private const string PollSettingId = "poll-seconds";
        private const int DefaultPollSeconds = 30;
        private const int MaxResponseBytes = 1024 * 1024;
        private readonly object _syncLock = new object();
        private Timer _timer;
        private bool _syncing;

        public override string Id { get; } = "PixoraLocator";
        public override string Name { get; } = "Pixora Locator";
        protected override string SettingsFileName { get; } = "PixoraLocator.ini";

        public HSPI()
        {
            LogDebug = false;
            var page = PageFactory.CreateSettingsPage(SettingsPageId, "Settings");
            page.WithLabel("privacy", null,
                "Pixora Locator reads only the household's current shared positions. It does not download route or location history.");
            page.WithInput(TokenSettingId, "Locator pairing token", "", EInputType.Password);
            page.WithInput(PollSettingId, "Refresh interval in seconds (15-300)", DefaultPollSeconds.ToString(CultureInfo.InvariantCulture), EInputType.Number);
            Settings.Add(page.Page);
        }

        protected override void Initialize()
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            LoadSettingsFromIni();
            RestartTimer(true);
        }

        protected override void OnShutdown()
        {
            lock (_syncLock)
            {
                _timer?.Dispose();
                _timer = null;
            }
        }

        protected override bool OnSettingChange(string pageId, AbstractView currentView, AbstractView changedView)
        {
            if (pageId != SettingsPageId || !(changedView is InputView input))
            {
                return false;
            }
            if (changedView.Id == TokenSettingId)
            {
                string token = (input.Value ?? "").Trim();
                if (token.Length > 0 && (!token.StartsWith("phs1.", StringComparison.Ordinal) || token.Length > 1500))
                {
                    return false;
                }
            }
            else if (changedView.Id == PollSettingId)
            {
                if (!int.TryParse(input.Value, NumberStyles.Integer, CultureInfo.InvariantCulture, out int seconds) || seconds < 15 || seconds > 300)
                {
                    return false;
                }
            }
            ThreadPool.QueueUserWorkItem(_ =>
            {
                Thread.Sleep(500);
                RestartTimer(true);
            });
            return true;
        }

        protected override void BeforeReturnStatus()
        {
            // Synchronize owns the status so an authentication or network warning is
            // not accidentally replaced when HomeSeer asks for the current status.
        }

        private void RestartTimer(bool runNow)
        {
            int seconds = ReadPollSeconds();
            lock (_syncLock)
            {
                _timer?.Dispose();
                _timer = new Timer(_ => Synchronize(), null, runNow ? TimeSpan.Zero : TimeSpan.FromSeconds(seconds), TimeSpan.FromSeconds(seconds));
            }
        }

        private int ReadPollSeconds()
        {
            return int.TryParse(ReadSetting(PollSettingId), NumberStyles.Integer, CultureInfo.InvariantCulture, out int seconds)
                ? Math.Max(15, Math.Min(300, seconds))
                : DefaultPollSeconds;
        }

        private string ReadSetting(string id)
        {
            return (Settings[SettingsPageId].GetViewById(id) as InputView)?.Value?.Trim() ?? "";
        }

        private void Synchronize()
        {
            lock (_syncLock)
            {
                if (_syncing) return;
                _syncing = true;
            }
            try
            {
                string token = ReadSetting(TokenSettingId);
                if (string.IsNullOrWhiteSpace(token))
                {
                    Status = PluginStatus.Info("A Locator pairing token is required.");
                    return;
                }
                LocatorFeed feed = DownloadCurrentLocations(token);
                if (feed.Version != 1 || feed.Devices == null)
                {
                    throw new InvalidDataException("The Locator response version is not supported.");
                }
                ApplyFeed(feed);
                Status = PluginStatus.Ok();
            }
            catch (WebException exception) when (exception.Response is HttpWebResponse response && response.StatusCode == HttpStatusCode.Unauthorized)
            {
                Status = PluginStatus.Critical("The Locator pairing token is invalid or revoked. Create a new HomeSeer token in Locator Settings.");
            }
            catch (Exception exception)
            {
                Status = PluginStatus.Warning("The latest Locator refresh failed; existing HomeSeer values were left unchanged.");
                HomeSeerSystem.WriteLog(ELogType.Warning, SafeMessage(exception), Name);
            }
            finally
            {
                lock (_syncLock) _syncing = false;
            }
        }

        private static LocatorFeed DownloadCurrentLocations(string token)
        {
            var request = (HttpWebRequest)WebRequest.Create(Endpoint);
            request.Method = "GET";
            request.Accept = "application/json";
            request.UserAgent = "PixoraLocator-HomeSeer/1.0";
            request.Headers[HttpRequestHeader.Authorization] = "Bearer " + token;
            request.Timeout = 20000;
            request.ReadWriteTimeout = 20000;
            request.AllowAutoRedirect = false;
            using (var response = (HttpWebResponse)request.GetResponse())
            {
                if (response.StatusCode != HttpStatusCode.OK) throw new WebException("Locator returned HTTP " + (int)response.StatusCode);
                if (response.ContentLength > MaxResponseBytes) throw new InvalidDataException("Locator response was too large.");
                using (Stream stream = response.GetResponseStream())
                using (var memory = new MemoryStream())
                {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while (stream != null && (read = stream.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        total += read;
                        if (total > MaxResponseBytes) throw new InvalidDataException("Locator response was too large.");
                        memory.Write(buffer, 0, read);
                    }
                    string json = Encoding.UTF8.GetString(memory.ToArray());
                    return JsonConvert.DeserializeObject<LocatorFeed>(json) ?? throw new InvalidDataException("Locator returned an empty response.");
                }
            }
        }

        private void ApplyFeed(LocatorFeed feed)
        {
            var currentAddresses = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (LocatorDevice item in feed.Devices)
            {
                if (!IsValidDevice(item)) continue;
                string rootAddress = RootAddress(item.DeviceId);
                currentAddresses.Add(rootAddress);
                HsDevice device = FindDevice(rootAddress);
                if (device == null)
                {
                    int deviceRef = HomeSeerSystem.CreateDevice(BuildDevice(item).PrepareForHs());
                    device = HomeSeerSystem.GetDeviceWithFeaturesByRef(deviceRef);
                }
                else
                {
                    if (!string.Equals(device.Name, CleanName(item.Name), StringComparison.Ordinal))
                    {
                        HomeSeerSystem.UpdateDeviceByRef(device.Ref, new Dictionary<EProperty, object> { { EProperty.Name, CleanName(item.Name) } });
                    }
                    device = HomeSeerSystem.GetDeviceWithFeaturesByRef(device.Ref);
                    EnsureFeatures(device);
                    device = HomeSeerSystem.GetDeviceWithFeaturesByRef(device.Ref);
                }
                UpdateDevice(item, device);
            }

            foreach (int deviceRef in HomeSeerSystem.GetRefsByInterface(Id, true))
            {
                HsDevice device = HomeSeerSystem.GetDeviceWithFeaturesByRef(deviceRef);
                if (device.Address.StartsWith("pixora:", StringComparison.OrdinalIgnoreCase) && !currentAddresses.Contains(device.Address))
                {
                    SetBinary(device, "available", false);
                    SetBinary(device, "presence", false);
                    SetText(device, "place", "Unavailable");
                }
            }
        }

        private DeviceFactory BuildDevice(LocatorDevice item)
        {
            var factory = DeviceFactory.CreateDevice(Id)
                .WithName(CleanName(item.Name))
                .WithAddress(RootAddress(item.DeviceId))
                .WithLocation("Pixora Locator")
                .WithLocation2("Location")
                .AsType(EDeviceType.Generic, 0);
            AddAllFeatures(factory, item.DeviceId);
            return factory;
        }

        private void AddAllFeatures(DeviceFactory factory, string deviceId)
        {
            factory
                .WithFeature(BinaryFeature(deviceId, "presence", "At saved Place", "At a saved Place", "Away from saved Places"))
                .WithFeature(BinaryFeature(deviceId, "available", "Location available", "Available", "Unavailable"))
                .WithFeature(TextFeature(deviceId, "place", "Place"))
                .WithFeature(TextFeature(deviceId, "latitude", "Latitude"))
                .WithFeature(TextFeature(deviceId, "longitude", "Longitude"))
                .WithFeature(TextFeature(deviceId, "accuracy", "GPS accuracy"))
                .WithFeature(TextFeature(deviceId, "battery", "Battery"))
                .WithFeature(TextFeature(deviceId, "movement", "Movement"))
                .WithFeature(TextFeature(deviceId, "sharing", "Sharing mode"))
                .WithFeature(TextFeature(deviceId, "last-report", "Last report"))
                .WithFeature(TextFeature(deviceId, "since", "Since"));
        }

        private void EnsureFeatures(HsDevice device)
        {
            string deviceId = device.Address.Substring("pixora:".Length);
            var expected = new[] { "presence", "available", "place", "latitude", "longitude", "accuracy", "battery", "movement", "sharing", "last-report", "since" };
            foreach (string key in expected)
            {
                if (FindFeature(device, key) != null) continue;
                FeatureFactory feature = key == "presence"
                    ? BinaryFeature(deviceId, key, "At saved Place", "At a saved Place", "Away from saved Places")
                    : key == "available"
                        ? BinaryFeature(deviceId, key, "Location available", "Available", "Unavailable")
                        : TextFeature(deviceId, key, FeatureName(key));
                HomeSeerSystem.CreateFeatureForDevice(feature.PrepareForHsDevice(device.Ref));
            }
        }

        private static FeatureFactory BinaryFeature(string deviceId, string key, string name, string onText, string offText)
        {
            return FeatureFactory.CreateGenericBinarySensor("PixoraLocator", name, onText, offText, 1, 0)
                .WithAddress(FeatureAddress(deviceId, key))
                .WithLocation("Pixora Locator")
                .WithLocation2("Location")
                .WithDisplayType(EFeatureDisplayType.Important);
        }

        private static FeatureFactory TextFeature(string deviceId, string key, string name)
        {
            return FeatureFactory.CreateFeature("PixoraLocator")
                .WithName(name)
                .WithAddress(FeatureAddress(deviceId, key))
                .WithLocation("Pixora Locator")
                .WithLocation2("Location")
                .AsType(EFeatureType.Generic, 0)
                .WithDisplayType(EFeatureDisplayType.Normal);
        }

        private void UpdateDevice(LocatorDevice item, HsDevice device)
        {
            bool usable = item.Available && item.Location != null;
            SetBinary(device, "available", usable);
            SetBinary(device, "presence", usable && item.CurrentPlace != null && !string.IsNullOrWhiteSpace(item.CurrentPlace.Name));
            SetText(device, "place", PlaceLabel(item, usable));
            SetText(device, "sharing", Humanize(item.Sharing));
            SetText(device, "movement", usable ? Humanize(item.Motion) : "Unavailable");
            SetText(device, "last-report", usable ? FirstText(item.Location.ReceivedAt, item.Location.CapturedAt, item.UpdatedAt) : FirstText(item.UpdatedAt, "Unavailable"));
            SetText(device, "since", usable ? FirstText(item.CurrentPlace?.Since, item.StationarySince, item.Location.CapturedAt) : "Unavailable");
            SetText(device, "battery", item.Battery.HasValue ? Math.Max(0, Math.Min(100, item.Battery.Value)).ToString(CultureInfo.InvariantCulture) + "%" : "Unknown");
            if (usable)
            {
                SetNumber(device, "latitude", item.Location.Latitude, item.Location.Latitude.ToString("F6", CultureInfo.InvariantCulture));
                SetNumber(device, "longitude", item.Location.Longitude, item.Location.Longitude.ToString("F6", CultureInfo.InvariantCulture));
                SetNumber(device, "accuracy", Math.Max(0, item.Location.Accuracy), Math.Max(0, item.Location.Accuracy).ToString("F1", CultureInfo.InvariantCulture) + " m");
            }
            else
            {
                SetText(device, "latitude", "Unavailable");
                SetText(device, "longitude", "Unavailable");
                SetText(device, "accuracy", "Unavailable");
            }
        }

        private void SetBinary(HsDevice device, string key, bool value)
        {
            HsFeature feature = FindFeature(device, key);
            if (feature != null) HomeSeerSystem.UpdateFeatureValueByRef(feature.Ref, value ? 1 : 0);
        }

        private void SetText(HsDevice device, string key, string value)
        {
            HsFeature feature = FindFeature(device, key);
            if (feature != null) HomeSeerSystem.UpdateFeatureValueStringByRef(feature.Ref, value ?? "");
        }

        private void SetNumber(HsDevice device, string key, double value, string display)
        {
            HsFeature feature = FindFeature(device, key);
            if (feature == null) return;
            HomeSeerSystem.UpdateFeatureValueByRef(feature.Ref, value);
            HomeSeerSystem.UpdateFeatureValueStringByRef(feature.Ref, display);
        }

        private static HsFeature FindFeature(HsDevice device, string key)
        {
            string suffix = ":" + key;
            return device.Features.Find(feature => feature.Address.EndsWith(suffix, StringComparison.OrdinalIgnoreCase));
        }

        private HsDevice FindDevice(string address)
        {
            try { return HomeSeerSystem.GetDeviceByAddress(address); }
            catch { return null; }
        }

        private static bool IsValidDevice(LocatorDevice item)
        {
            if (item == null || string.IsNullOrWhiteSpace(item.DeviceId) || item.DeviceId.Length > 80) return false;
            foreach (char character in item.DeviceId)
            {
                if (!(char.IsLetterOrDigit(character) || character == '-')) return false;
            }
            return true;
        }

        private static string RootAddress(string deviceId) => "pixora:" + deviceId;
        private static string FeatureAddress(string deviceId, string key) => RootAddress(deviceId) + ":" + key;

        private static string CleanName(string value)
        {
            string cleaned = (value ?? "Locator phone").Trim();
            var builder = new StringBuilder();
            foreach (char character in cleaned)
            {
                if (!char.IsControl(character)) builder.Append(character);
                if (builder.Length >= 80) break;
            }
            return builder.Length == 0 ? "Locator phone" : builder.ToString();
        }

        private static string PlaceLabel(LocatorDevice item, bool usable)
        {
            if (!usable) return "Unavailable";
            if (!string.IsNullOrWhiteSpace(item.CurrentPlace?.Name)) return item.CurrentPlace.Name.Trim();
            if (string.Equals(item.Motion, "stationary", StringComparison.OrdinalIgnoreCase) && !string.IsNullOrWhiteSpace(item.Nearby?.Label)) return "Near " + item.Nearby.Label.Trim();
            return string.Equals(item.Sharing, "approximate", StringComparison.OrdinalIgnoreCase) ? "Approximate area" : "Not at a saved Place";
        }

        private static string Humanize(string value)
        {
            if (string.IsNullOrWhiteSpace(value)) return "Unknown";
            string cleaned = value.Replace('-', ' ').Replace('_', ' ').Trim();
            return char.ToUpperInvariant(cleaned[0]) + cleaned.Substring(1);
        }

        private static string FirstText(params string[] values)
        {
            foreach (string value in values) if (!string.IsNullOrWhiteSpace(value)) return value.Trim();
            return "Unknown";
        }

        private static string FeatureName(string key)
        {
            switch (key)
            {
                case "place": return "Place";
                case "latitude": return "Latitude";
                case "longitude": return "Longitude";
                case "accuracy": return "GPS accuracy";
                case "battery": return "Battery";
                case "movement": return "Movement";
                case "sharing": return "Sharing mode";
                case "last-report": return "Last report";
                case "since": return "Since";
                default: return key;
            }
        }

        private static string SafeMessage(Exception exception)
        {
            string message = exception?.Message ?? "Unknown error";
            message = message.Replace("\r", " ").Replace("\n", " ");
            return message.Length <= 300 ? message : message.Substring(0, 300);
        }
    }
}

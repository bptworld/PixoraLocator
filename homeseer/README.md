# Pixora Locator for HomeSeer HS4

This plugin creates one HomeSeer device for every phone in the paired Pixora Locator household. It updates current latitude, longitude, GPS accuracy, saved Place, presence at a saved Place, battery, movement, sharing mode, report time, and since-time values.

It reads only Locator's current state. It does not request, download, or create a route or location-history database.

## Requirements

- HomeSeer HS4
- Windows or Linux HomeSeer installation capable of running .NET Framework 4.6.2-compatible plugins
- Pixora Locator with at least one phone actively sharing

## Install

1. Download `PixoraLocator-HomeSeer-1.0.0.zip` from this repository's **Releases** page, or build the plugin from source with `dotnet publish homeseer/PixoraLocator.HomeSeer.csproj -c Release`.
2. Stop HomeSeer.
3. Copy the files from the publish folder into the HomeSeer program directory. `HSPI_PixoraLocator.exe` must be in the same directory as the other HSPI executables.
4. Start HomeSeer and enable **Pixora Locator** under **Plugins**.
5. In the Locator phone app, open **Settings → HomeSeer** and create a pairing token.
6. In HomeSeer, open **Plugins → Pixora Locator → Settings**, paste the token, and save it.

The plugin refreshes every 30 seconds by default. The interval can be set from 15 to 300 seconds. One household token covers all sharing phones in that household. Locator friends outside the household are not included.

## Privacy and removal

The token works only with the HomeSeer current-location endpoint. It cannot call the mobile API, Home Assistant endpoint, Planner, or location history. Revoke it from Locator Settings at any time to stop access immediately.

The plugin does not automatically delete HomeSeer devices when a phone stops sharing or disappears; it marks them unavailable so HomeSeer events do not mistake an old coordinate for a current one. You can delete an unused device in HomeSeer manually.

## Build

```powershell
dotnet publish .\homeseer\PixoraLocator.HomeSeer.csproj -c Release
```

The output is in `homeseer/bin/Release/net462/publish/`.

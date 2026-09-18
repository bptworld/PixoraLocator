# PixoraLocator

Official Pixora Locator integrations for Home Assistant and Hubitat. The phone APK is distributed separately and is not stored in this repository.

## Home Assistant

1. In Home Assistant, open **Settings → Apps → App Store**.
2. Open the three-dot menu and choose **Repositories**.
3. Add this repository:

   ```text
   https://github.com/bptworld/PixoraLocator
   ```

4. Return to the App Store and install **Pixora Locator**.
5. In the Pixora Locator phone app, open **Settings → Home Assistant App** and create a pairing token.
6. Paste the token into the Home Assistant App's **Configuration** tab, save, and start it.
7. Open Pixora Locator from its Home Assistant App page or sidebar entry.

## What it does

- Creates managed Home Assistant device trackers using Home Assistant's built-in Mobile App integration.
- Uses each phone's saved Pixora Locator device name.
- Synchronizes only the current position of household phones that are actively sharing.
- Marks a tracker away when sharing stops or its current position is unavailable.
- Starts automatically with Home Assistant and recovers removed tracker registrations.
- Provides its own Home Assistant page showing connection health, last sync, and device status.

The Home Assistant App source is in [`pixora_locator/`](pixora_locator/).

## Hubitat

1. In Hubitat, open **Drivers Code** and choose **New Driver**.
2. Paste the contents of [`hubitat/PixoraLocator.groovy`](hubitat/PixoraLocator.groovy) and save it.
3. Open **Devices**, choose **Add Device → Virtual**, and select **Pixora Locator** as the device type.
4. In Planner, connect Hubitat under **Setup Options**.
5. In the Pixora Locator phone app, enable Hubitat under **Settings → Where to send** and select the virtual device.

The Hubitat driver source and detailed instructions are in [`hubitat/`](hubitat/).

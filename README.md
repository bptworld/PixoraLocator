# PixoraLocator

Official Pixora Locator integrations for Home Assistant and Hubitat. The phone APK is distributed separately and is not stored in this repository.

## Home Assistant

1. Navigate to the app store in the Home Assistant UI: **Settings**, **Apps**, then **Install App**.
2. Select the three vertical dots in the upper-right corner and select **Repositories**.
3. At the bottom of the **Repositories** screen, click **Add**.
4. Enter this project's GitHub page URL and click **Add**:

   ```text
   https://github.com/bptworld/PixoraLocator
   ```

5. After adding the repository, go back to the **App Store**, select the three vertical dots, and select **Check for updates**.
6. Scroll down the **App Store** or use search to find **Pixora Locator**.
7. Select **Pixora Locator** and click **Install**.
8. Start Pixora Locator and open it from the app page or sidebar.
9. In the Pixora Locator phone app, open **Settings → Home Assistant App** and create a pairing token.
10. Paste the token into the Home Assistant App's **Configuration** tab and save it.
11. Restart the Home Assistant App. Its page will show the connection and device status.

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

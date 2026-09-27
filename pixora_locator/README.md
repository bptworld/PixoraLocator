# Pixora Locator Home Assistant App

Pixora Locator privately connects actively sharing phones to Home Assistant using a pairing token.

## Install

1. Navigate to the app store in the Home Assistant UI: **Settings**, **Apps**, then **Install App**.
2. Select the three vertical dots in the upper-right corner and select **Repositories**.
3. At the bottom of the **Repositories** screen, click **Add**.
4. Enter `https://github.com/bptworld/PixoraLocator` and click **Add**.
5. Go back to the **App Store**, select the three vertical dots, and select **Check for updates**.
6. Scroll down the **App Store** or use search to find **Pixora Locator**.
7. Select **Pixora Locator** and click **Install**.
8. In the Pixora Locator phone app, open **Settings → Home Assistant App** and create a pairing token.
9. Before starting the Home Assistant App, open its **Configuration** tab, paste the token, and click **Save**.
10. Start Pixora Locator and open it from the app page or sidebar.

The App starts automatically with Home Assistant. Normal synchronization reads only the current location point for each actively sharing phone. Creating a migration file reads selected Home Assistant zones only; recorder history is never accessed.

## Import Home Assistant Places

Open the Pixora Locator App page in Home Assistant and use **Migrate Home Assistant zones**. Choose the zones to turn into Locator Places and create the checksummed `.pixora` file. Import that file from Locator Settings, confirm the preview, and delete the downloaded file when the migration finishes. New Places start fresh, just like Places added directly in Locator; an existing matching Place keeps its current activity and settings.

The file contains only the selected zone definitions. It contains no tracker records or location, arrival, or departure history, and never contains Home Assistant passwords, access tokens, automations, or unrelated sensor data.

Each phone has a normal `device_tracker` for maps and zones. The same device also shows separate Place, Latitude, Longitude, GPS Accuracy, Since, Movement, Sharing Mode, and Last Report sensor rows, making the details directly usable in dashboards and automations. Home Assistant shows new App versions under **Settings → Apps** after its repository update check runs; use **Check for updates** in the App Store menu to check immediately.

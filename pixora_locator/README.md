# Pixora Locator Home Assistant App

This is a native Home Assistant App, not a HACS integration.

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

The App starts automatically with Home Assistant. It reads only the current location point for each actively sharing phone. It does not receive or retain location history.

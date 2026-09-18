# Pixora Locator Home Assistant App

This is a native Home Assistant App, not a HACS integration.

## Install

1. In Home Assistant, open **Settings → Apps → App Store**.
2. Open **Repositories** and add `https://github.com/bptworld/PixoraLocator`.
3. Install **Pixora Locator**.
4. In the Pixora Locator phone app, open **Settings → Home Assistant** and create a pairing token.
5. Paste the token into this App's **Configuration** tab, save, and start the App.
6. Open the App to see connection health, the last successful sync, and each household phone's current sharing state.

The App starts automatically with Home Assistant. It reads only the current location point for each actively sharing phone. It does not receive or retain location history.

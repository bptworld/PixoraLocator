# Pixora Locator for Hubitat

1. In Hubitat, open **Drivers Code**, choose **New Driver**, paste `PixoraLocator.groovy`, and save it.
2. Open **Devices**, choose **Add Device → Virtual**, give it the same name as the phone in Pixora Locator, and select **Pixora Locator** as its type.
3. Open the Maker API app and enable that virtual device.
4. Connect Hubitat in Planner **Setup Options**, then enable Hubitat delivery in the phone app under **Settings → Where to send**.

PixoraHQ sends the current latitude, longitude, accuracy, saved-place name, presence, battery level, and capture time to the virtual device. Credentials stay on PixoraHQ; the phone never stores the Hubitat access token.

# Changelog

## 1.2.0

- Replaced the single attribute-heavy Location sensor with separate visible Place, Latitude, Longitude, GPS Accuracy, Since, Movement, Sharing Mode, and Last Report sensor rows on each phone device.
- Reused the existing Location entity as Place so upgrades do not leave a duplicate legacy sensor behind.
- Marked every location-detail sensor unavailable when its phone stops sharing or disappears from the current feed.

## 1.1.0

- Added a Location sensor for each phone with current Place, Place-since time, motion, sharing mode, latitude, longitude, GPS accuracy, nearby area, and report timestamps.
- Fixed Home Assistant device firmware versions so they update after every Pixora Locator App upgrade.
- Bumped the App version so Home Assistant shows the available update in the App store.

## 1.0.3

- Corrected every installation guide so the pairing token is saved before the Home Assistant App is started.

## 1.0.2

- Upgraded base-image packages during builds and restricted container vulnerability enforcement to patchable operating-system packages.

## 1.0.1

- Added a dedicated Home Assistant App page with connection state, device status, last sync, and setup guidance.
- Kept the page available when the pairing token is missing or invalid so configuration errors are visible.

## 1.0.0

- Initial Home Assistant App release.
- Secure, revocable pairing with Pixora Locator.
- Managed trackers through Home Assistant's built-in Mobile App integration.
- Current-location-only synchronization with automatic startup and recovery.

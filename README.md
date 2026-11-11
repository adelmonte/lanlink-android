# lanlink for Android

The phone side of [lanlink](https://github.com/adelmonte/lanlink), a small KDE
Connect alternative. Plain Kotlin on the Android SDK, no AndroidX or Compose;
the only library is Apache MINA SSHD for the SFTP server.

- **Send files, text and links** to a computer, from the app or the share sheet.
  Received files land in Downloads; received text goes to the clipboard.
- **Clipboard on request**: select text in any app and pick *Send with lanlink*, or
  add the **Send clipboard** quick-settings tile. Nothing syncs on its own.
- **Let the computer browse the phone** (`lanlink mount <phone>` on the desktop).
- **Media**: whatever plays on the computer shows up in Android's media controls,
  with a fuller remote (seek, volume, player choice) in the app.
- **Touchpad and keyboard** for the computer or TV: tap to click, two-finger tap
  for right click, two fingers to scroll, long-press then move to drag.
- Reports battery to the computer.

Requires Android 10+.

## Install

Download the APK from [Releases](https://github.com/adelmonte/lanlink-android/releases)
and install it, or build it:

```sh
gradle assembleRelease          # needs the Android SDK; set sdk.dir in local.properties
adb install -r build/outputs/apk/release/lanlink-release.apk
```

The release build is signed with a debug key, which is fine for sideloading.

On first run, allow notifications, then tap **Allow file access** and **Keep
running in the background**. Without the second one Android drops the connection
when the screen turns off.

## Tests

`src/test` runs the Kotlin core against the real desktop daemon (TLS both ways,
pairing both ways, file transfer):

```sh
LANLINK_BIN=/path/to/lanlink gradle testDebugUnitTest
```

## License

GPL-3.0-or-later

# lanlink for Android

Phone side of lanlink. Kotlin, plain Android SDK (no AndroidX/Compose). The
only library is Apache MINA SSHD, for the SFTP server the desktop mounts.

Features:

- Send files, text and links to a computer: **Send files** on the device page,
  or the Android share sheet ("Send with lanlink"). Received files land in
  **Downloads**; received text goes to the clipboard.
- Let the computer browse the phone (`lanlink mount <phone>` on the desktop).
  Needs "All files access", which the app asks for.
- **Media**: control the computer's players (play/pause, skip, seek, volume).
- **Touchpad & keyboard**: drive the computer or TV. Tap = click, two-finger
  tap = right click, three-finger tap = middle click, two fingers scroll,
  long-press then move = drag.
- Reports battery to the computer (shown in the desktop TUI).

## Build & install

```sh
cd android
gradle assembleRelease          # needs the Android SDK; set sdk.dir in local.properties
adb install -r build/outputs/apk/release/lanlink-release.apk
```

The release build is signed with the debug key, which is fine for sideloading
your own phone. Requires Android 10+.

First run: allow notifications, tap **Allow file access** and **Keep running in
the background** (otherwise Android kills the connection when the screen is off).

## Tests

`src/test` has an interop test that runs the Kotlin core against the real
desktop daemon (TLS both directions, pairing both ways, file transfer):

```sh
(cd .. && cargo build --release)
LANLINK_BIN=$PWD/../target/release/lanlink gradle testDebugUnitTest
```

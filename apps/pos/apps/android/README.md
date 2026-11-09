# Dedicated Android launcher

`:apps:android` is a dedicated AGP application. `:ui:app` is an Android KMP
library, not the deprecated mixed KMP/application shape. This local shell targets
SDK 37 and has an API 24 minimum candidate; the combined platform matrix is not
qualified by those numbers.

`./gradlew :apps:android:assembleDebug` is the local debug build entry point.
The manifest disables backups/cleartext, supports RTL and declares only a client
launcher and Internet permission. The Activity protects its window with
`FLAG_SECURE` and locks client UI on stop. Background/rotation/IME/accessibility
behavior still needs real emulator and hardware validation.

There is no foreground master service, hosted LAN transport, notification,
protected device identity or physical-device adapter in this shell. Evaluate
SDK 37 LAN permissions, service types/start restrictions, notification, OEM
battery, denied/revoked grants and force-stop behavior before enabling hosting.
Do not interpret missing live adapters as an operating-system support waiver.

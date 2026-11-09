# Dedicated Android launcher

`:apps:android` is the POS Android application; `:ui:app` is its shared KMP UI
library. The declared baseline is Android 7/API 24 with target/compile SDK 37.
These declarations do not establish that every native security policy works on
every device.

The application includes the original setup controller, native installed-identity
and Keystore adapters, retained local branch owner, authoritative Room services,
LAN RPC hosting/client transport, protected client journals, physical journal
participants and native report/document adapters. LIVE and the two training
slots use separate native composition namespaces. Training data and exports are
synthetic. Successful setup is separate from employee sign-in and explicit client
hosting; current native custody and employee/device authority remain required.

The two nonexported special-use foreground services retain the LIVE owner or
present the training hosting notice. Ordinary screen disposal does not commit a
master STOP. Explicit STOP/RESUME follows the durable lifecycle intent and binding
path. Backups and cleartext traffic are disabled, the window uses `FLAG_SECURE`,
and backgrounding locks the employee presentation. On Android 17/API 37+, the
LAN section requests local-network access explicitly and keeps Host/Connect
disabled until the current OS grant is present. The native layer checks again
before acquiring transport and opening sockets; granting permission starts no
connection and supplies no employee/device authority. Separate protected DocumentsUI
providers expose LIVE and training reports.

For a local debug UI build, run `./gradlew :apps:android:assembleDebug`. The genuine
native original owner refuses debuggable installed applications; use the isolated
nondebuggable testing variant for native setup qualification:

```sh
python3 tools/package_local_android.py
```

The script reuses the retained private testing signer and produces
`apps/android/build/outputs/local-testing-signed/ukkera-pos-testing.apk`, its
SHA-256/source metadata and signature report. Missing credentials fail rather
than replacing the signer. `--initialize-signing-key` is only for an explicitly
new installation with no retained testing key. Never upload the private signing
directory. Use the existing resource-aware build gate when sharing a build host.

Install on a selected synthetic-data device with:

```sh
adb -s DEVICE_SERIAL install -r ukkera-pos-testing.apk
adb -s DEVICE_SERIAL shell am start -n com.ukkera.pos.local.testing/com.ukkera.pos.android.MainActivity
```

Keep the existing application/data when testing restart recovery. Do not uninstall,
clear storage or substitute a signer to work around unresolved custody. Packaging
and signature verification alone are not a setup, native-storage, LAN, financial
workflow or device-acceptance result.

Before owner acceptance, exercise original setup and separate employee entry,
LIVE/T1/T2 separation, explicit hosting/STOP/restart/RESUME, two-device admission and
LAN reconnect, screen rotation/background/lock, denied/revoked platform grants,
report/document cancellation and export, native journal recovery and force-stop.
OEM battery/foreground restrictions, peripherals and native protection levels need
actual device execution. Strict financial capacity reservation remains a separate
implementation dependency; do not infer physical-money readiness from the native
spool or an APK. See the platform handoff for executed checks and current blockers.

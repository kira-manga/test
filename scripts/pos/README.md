# POS native packaging scripts

These scripts prepare local macOS/Windows/iPhone-iPad build artifacts. They do
not grant authority, sign device apps, publish releases or run remote actions.

Run from the repository root:

```sh
scripts/pos/validate-config.sh
scripts/pos/ios/build-ios-artifacts.sh --pos-root apps/pos
scripts/pos/desktop/package-desktop.sh --format dmg   # macOS
scripts/pos/desktop/package-desktop.sh --format msi   # Windows Git Bash
```

Each packaging script writes only built packages, sanitized diagnostics and
`SHA256SUMS`/`manifest.json` to `build/pos-artifacts/`. The artifact manifest
rejects credentials, signing material and native databases. The iOS unsigned
device archive is prepared with `CODE_SIGNING_ALLOWED=NO`; it is not installable
on physical hardware.

See [the native packaging handoff](../../docs/pos/NATIVE-PACKAGING-HANDOFF.md)
for exact task paths, CI dispatch, runner/toolchain validation and limitations.

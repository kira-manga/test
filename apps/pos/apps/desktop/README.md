# Native desktop launcher and packaging handoff

`./gradlew :apps:desktop:run` launches the shared CMP client using JDK 21.
`--args=--smoke-exit` is an explicit local smoke option: show the bootstrap and
close after 2.5 seconds. It does not authenticate, create a master, or simulate a
transaction.

The root build owns dependency/toolchain pins. DMG/MSI targets describe local
qualification packaging; they must be built and launched on their actual OS.
A Linux `packageDeb` or GUI check is not Windows/macOS qualification.

## Package locally

From the repository root:

```sh
# macOS (choose the host architecture)
scripts/pos/desktop/package-desktop.sh --format dmg

# Windows (Git Bash)
scripts/pos/desktop/package-desktop.sh --format msi
```

The script validates JDK 21, runs the ordinary Compose desktop Gradle target
(`:apps:desktop:packageDmg` or `:apps:desktop:packageMsi`), copies the package,
verifies the package can be opened/extracted, records signing and version state,
and writes `diagnostics/`, `SHA256SUMS` and `manifest.json`.

Direct Gradle paths:

```sh
cd apps/pos
./gradlew :apps:desktop:packageDmg --console=plain   # macOS
./gradlew :apps:desktop:packageMsi --console=plain   # Windows
./gradlew :apps:desktop:createDistributable --console=plain
```

Use `bash ./gradlew ...` on Windows/Git Bash if the checkout did not retain the
executable bit. Keep `-P` and `-D` arguments quoted when invoking through
PowerShell:

```powershell
.\gradlew.bat ":apps:desktop:packageMsi" "-Pkotlin.compiler.execution.strategy=in-process" "--max-workers=2"
```

## What each package contains

- macOS: an unsigned `UkkeraPOS.app` inside a DMG. Bundle identifier
  `com.ukkera.pos.local.desktop`; no certificate or notarization is applied.
- Windows: an unsigned per-user MSI with stable upgrade UUID
  `8B0F4A62-3C7D-4E19-9A5B-6D2F1C8E7A30`; shortcuts and a Start-menu entry are
  created under the current user. No code signing is applied.

The packages are local test artifacts. Gatekeeper/SmartScreen may warn or block
them; do not globally disable platform security. Install with the platform's
normal package workflow:

```sh
# macOS local check; expected to be unsigned.
hdiutil attach UkkeraPOS-1.0.0.dmg -nobrowse -readonly
cp -R /Volumes/UkkeraPOS/UkkeraPOS.app /Applications/
# First launch: right-click Open (or use the owner's signed build later).
# Optional bounded smoke:
/Applications/UkkeraPOS.app/Contents/MacOS/UkkeraPOS --smoke-exit
hdiutil detach /Volumes/UkkeraPOS
```

```bat
msiexec /i UkkeraPOS-0.1.0.msi /qn
"%LOCALAPPDATA%\UkkeraPOS\UkkeraPOS.exe" --smoke-exit
```

Package verification is not runtime qualification: the default dependency
composition remains visibly unconfigured. `Main.kt` passes
`AppDependencies(platform = PlatformFamily.DESKTOP)` with the `Unavailable*`
gateways; `centralServicesEnabled` is false and no master/branch service starts.
`--smoke-exit` only checks that the packaged launcher starts and closes. Closing a
window does not prove a future master service survives; no supervised
Windows/macOS service lifecycle, native key/history owner, signing/notarization,
device confidence or owner acceptance is claimed.

## Manual CI (auxiliary repository only)

`.github/workflows/pos-desktop-native.yml` is `workflow_dispatch` only and refuses
to run outside `kira-manga/test`. It builds an arm64 and an x64 macOS DMG on the
official macOS runners plus an x64 Windows MSI on `windows-2025`, then uploads
each package with diagnostics and a SHA256 manifest. Root dispatches and
downloads; the child author does not push or upload. See
[the packaging handoff](../../../../docs/pos/NATIVE-PACKAGING-HANDOFF.md).

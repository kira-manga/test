# Desktop Master and Client — manual owner testing

Synthetic data only, one local network, no real payments. Record for every step:
device, OS version, the screen shown and any refusal text. A refusal in an
unfinished path is a result to record, not a reason to reset data or repeat an
original. Package downloads and general install notes are in
[INSTALL-AND-TEST.md](../INSTALL-AND-TEST.md).

One installer serves both roles. The desktop **Master** is the OS-supervised
background service mode of the same app (`UkkeraPOS --service`); the window is the
Client/administration UI.

## Windows (MSIX, administrator install)

The MSIX is signed with the self-signed test certificate
`CN=Ukkera POS Test Signing` (public certificate:
`apps/pos/apps/desktop/packaging/windows/UkkeraPOS-Test-Signing.cer`). Packaged
services require an administrator install.

1. **Trust the test certificate.** In an administrator PowerShell:
   `Import-Certificate -FilePath .\UkkeraPOS-Test-Signing.cer -CertStoreLocation Cert:\LocalMachine\TrustedPeople`
   (or MSIX → Properties → Digital Signatures → Details → View Certificate →
   Install Certificate → Local Machine → Trusted People).
2. **Install.** In the same administrator PowerShell:
   `Add-AppxPackage .\UkkeraPOS-Test-<version>.msix`
3. **Verify the Master service registration.**
   `Get-CimInstance Win32_Service -Filter "Name='UkkeraPOSTestMaster'" | Select Name, StartMode, StartName, State`
   Expected: `StartMode` = `Auto`, `StartName` = `NT AUTHORITY\LocalService`.
   Record `sc.exe qsidtype UkkeraPOSTestMaster`; the Master requires a per-service
   SID (`UNRESTRICTED` or `RESTRICTED`), so `NONE` is a defect.
4. **Start it now** (it starts by itself after every reboot):
   `Start-Service UkkeraPOSTestMaster` then `Get-Service UkkeraPOSTestMaster`
   → `Running`. Record the result if it stops or fails to start (Event Viewer →
   Windows Logs → System, source *Service Control Manager*).
5. **Run setup.** Start **Ukkera POS (test)** from the Start menu → **Setup** →
   create the synthetic company/branch with this computer as the Master. Record
   each screen.
6. **Reboot** and repeat step 4's `Get-Service` check without starting it
   manually; it must be `Running`.
7. **Uninstall** when finished: `Get-AppxPackage Ukkera.POS.Test | Remove-AppxPackage`,
   then remove the certificate from Local Machine → Trusted People.

## Pair a Client and log in

1. On the Client (Android APK, or the same MSIX/DMG on another computer) open
   Ukkera POS → **Setup** → join an existing branch.
2. Pairing exchanges small documents. When a screen offers **Export**, save
   `ukkera-<document>.bin`, move it to the other device (USB, cable or shared
   folder) and **Import** it there, in the order the screens request.
3. Approve the Client on the Master. The Client then connects over the LAN
   (on Android 17 / API 37 grant local-network access when asked).
4. Log in on the Client with the synthetic employee created during setup.
5. Close and reopen the Client, and reboot the Master; record whether the Client
   reconnects without repeating pairing.

## macOS (DMG, test only)

The Master runs as the same app in service mode under a per-user LaunchAgent
(`com.ukkera.pos.master`) in your login session. Service mode needs the app
signed with the owner's Developer ID certificate and a build configured with that
certificate's Team ID and designated-requirement digest; an unsigned or
unconfigured build must refuse to act as Master.

1. Open the DMG and copy **UkkeraPOS** to **/Applications**. Do not run it from
   the disk image (the service would point to a path that disappears). Open it
   once and follow the Gatekeeper steps in INSTALL-AND-TEST.md if asked.
2. In the app choose **Install Master service**; if this build has no such
   action yet, record that and stop the macOS Master test here. The action
   writes `~/Library/LaunchAgents/com.ukkera.pos.master.plist` (owner-only) and
   loads it.
3. **Verify** in Terminal:
   `launchctl print gui/$(id -u)/com.ukkera.pos.master | grep -E 'state|pid'`
   → `state = running` with a pid.
   `ls -l ~/Library/LaunchAgents/com.ukkera.pos.master.plist` → `-rw-------`.
   Logs are in `~/Library/Logs/com.ukkera.pos.master/` (owner-only).
4. Run setup from the app as for Windows step 5, then pair a Client as above.
5. Log out and back in (or restart); repeat step 3 — the agent must be running
   again.
6. **Remove**: choose **Remove Master service** in the app, or
   `launchctl bootout gui/$(id -u)/com.ukkera.pos.master` and delete the plist.

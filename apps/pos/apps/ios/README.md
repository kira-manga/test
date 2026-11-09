# iPhone/iPad native host — qualification incomplete

**LOCAL ONLY.** The shared `:ui:app` module declares `iosArm64` and
`iosSimulatorArm64`, producing static framework `UkkeraPos`. The SwiftUI app
retains one `IosAppHost` and embeds `ComposeUIViewController`; it adds no UIKit
or SwiftUI navigation history. The same native company/branch screens are used.
The Swift launcher module is `UkkeraPosLauncher`, distinct from its imported
Kotlin `UkkeraPos` framework.

Open `UkkeraPos.xcodeproj` on an Apple worker with the agreed Xcode **26.4**,
supported JDK 21 and dependencies. The shared scheme runs
`:ui:app:embedAndSignAppleFrameworkForXcode`. iOS 15.0 is a **candidate**, not a
qualified minimum. A physical build needs the owner's signing team; no team,
certificate, provisioning profile or upload is configured here. Xcode's user
script sandbox is disabled for the documented Gradle build phase, not for the
application runtime.

## Honest gaps

- This Linux authoring environment does not compile, link, launch or sign iOS.
  Validate the project, device framework, simulator and application on macOS.
- The Swift scene covers inactive previews and locks its UI context. Real
  app-switcher timing/VoiceOver/background/lock/termination behavior is unverified.
- There is **no iOS master host**, LAN gateway, keychain or peripheral adapter.
  No Bonjour service name or local-network grant is invented. Add the exact
  `NSLocalNetworkUsageDescription`, `NSBonjourServices` and tested permission
  behavior when the qualified transport is wired, before enabling it.
- Live master hosting must keep the app active/awake, show the keep-open notice,
  and release idle prevention on stopping. Backgrounding/manual lock/force quit
  makes it unavailable. None of this is claimed implemented by a UI warning.
- Default Nav3 1.1.2 public gestures/transitions are used without private UIKit
  recognizers or copied timing hacks. Physical iPhone **and** iPad LTR/RTL edge,
  cancellation, fast flick, language switch, 60/120 Hz and multitasking gates remain.
- No fiscal, live-payment, recovery, encryption, accessibility or hardware
  qualification is implied by compiling this scaffold.

The lead-owned verification report is the source of actual command evidence.

# Native desktop launcher

`./gradlew :apps:desktop:run` launches the shared CMP client using the pinned JDK.
`--args=--smoke-exit` is an explicit local smoke option: show bootstrap and close
after 2.5 seconds. It does not authenticate or simulate any transaction.

The root build owns dependency/toolchain pins. DMG/MSI targets describe intended
local packaging; Windows and macOS installers must be built/launched on their
actual OS and qualified. A Linux GUI check is not Windows/macOS qualification.

This client creates no branch daemon. Closing its window cannot prove a future
master service survives. Supervised Windows/macOS service lifecycles, protected
service-account storage, native linkage, signing/notarization and devices remain
separate gates. Never deploy, publish or upload these local artifacts.

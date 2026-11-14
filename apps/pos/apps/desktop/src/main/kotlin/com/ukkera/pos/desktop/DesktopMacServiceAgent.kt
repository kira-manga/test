package com.ukkera.pos.desktop

import com.sun.jna.Platform
import com.sun.jna.platform.unix.LibC
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * macOS (test-only) per-user LaunchAgent that keeps the Master service mode of this app running
 * (`UkkeraPOS --service`) in the user's login session. It only registers the launchd job; the
 * service process itself enforces identity and custody. Refuses outside a launchable .app bundle.
 */
internal object DesktopMacServiceAgent {
    const val LABEL = DesktopMacServiceIdentity.LAUNCH_AGENT_LABEL
    private const val TEMPLATE = "/$LABEL.plist.template"
    private const val LAUNCHER_SUFFIX = ".app/Contents/MacOS/UkkeraPOS"
    private val PLACEHOLDERS = listOf("@LABEL@", "@PROGRAM@", "@LOG_DIR@")
    private val OWNER_FILE = PosixFilePermissions.fromString("rw-------")
    private val OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------")

    fun install(): Result<Unit> = runCatching {
        requireMac()
        val launcher = bundledLauncher()
        val plist = render(launcher, ensureDirectory(logDirectory(), ownerOnly = true))
        val target = plistPath()
        ensureDirectory(target.parent, ownerOnly = false)
        val staged = Files.createTempFile(target.parent, ".$LABEL", ".tmp",
            PosixFilePermissions.asFileAttribute(OWNER_FILE))
        try {
            Files.writeString(staged, plist)
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(staged) }
        Files.setPosixFilePermissions(target, OWNER_FILE)
        // Replace any earlier registration so the new launcher path takes effect.
        if (loaded()) launchctl("bootout", "$domain/$LABEL")
        val (code, output) = launchctl("bootstrap", domain, target.toString())
        check(code == 0) { "launchctl bootstrap failed ($code): $output" }
    }

    fun uninstall(): Result<Unit> = runCatching {
        requireMac()
        if (loaded()) {
            val (code, output) = launchctl("bootout", "$domain/$LABEL")
            check(code == 0 || !loaded()) { "launchctl bootout failed ($code): $output" }
        }
        Files.deleteIfExists(plistPath())
    }

    /** True when the agent file is present AND launchd has the job loaded in this login session. */
    fun isInstalled(): Boolean = runCatching {
        Platform.isMac() && Files.isRegularFile(plistPath(), LinkOption.NOFOLLOW_LINKS) && loaded()
    }.getOrDefault(false)

    private fun requireMac() = check(Platform.isMac()) { "The LaunchAgent is available only on macOS" }

    private val domain: String get() = "gui/${LibC.INSTANCE.getuid()}"

    private fun home(): Path = Path.of(System.getProperty("user.home")).also { check(it.isAbsolute) }
    private fun plistPath(): Path = home().resolve("Library/LaunchAgents/$LABEL.plist")
    private fun logDirectory(): Path = home().resolve("Library/Logs/$LABEL")

    private fun loaded(): Boolean = launchctl("print", "$domain/$LABEL").first == 0

    /**
     * The running bundle's launcher. jpackage records it as `jpackage.app-path`; the process
     * command is the fallback. Mounted disk images and App Translocation are refused because the
     * path would not survive: the app must be copied (e.g. to /Applications) first.
     */
    internal fun bundledLauncher(
        appPath: String? = System.getProperty("jpackage.app-path"),
        command: String? = ProcessHandle.current().info().command().orElse(null),
        executable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    ): Path {
        val raw = appPath?.takeIf { it.isNotBlank() } ?: command
            ?: error("Cannot determine the running launcher")
        val launcher = Path.of(raw).normalize()
        val text = launcher.toString()
        check(launcher.isAbsolute && text.endsWith(LAUNCHER_SUFFIX)) { "Not running from the UkkeraPOS.app bundle" }
        check(!text.startsWith("/Volumes/")) { "Copy UkkeraPOS.app out of the disk image before installing the service" }
        check("/AppTranslocation/" !in text) { "Move UkkeraPOS.app to /Applications before installing the service" }
        check(text.none { it.isISOControl() }) { "Unsupported launcher path" }
        check(executable(launcher)) { "Bundled launcher is not executable" }
        return launcher
    }

    internal fun render(launcher: Path, logs: Path): String {
        val template = DesktopMacServiceAgent::class.java.getResourceAsStream(TEMPLATE)
            ?.use { String(it.readBytes(), Charsets.UTF_8) } ?: error("LaunchAgent template is not bundled")
        return template.replace("@LABEL@", xml(LABEL)).replace("@PROGRAM@", xml(launcher.toString()))
            .replace("@LOG_DIR@", xml(logs.toString()))
            .also { rendered -> check(PLACEHOLDERS.none { it in rendered }) { "Unrendered LaunchAgent placeholder" } }
    }

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun ensureDirectory(path: Path, ownerOnly: Boolean): Path {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (ownerOnly) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY))
            else Files.createDirectories(path)
        }
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Expected a directory, not a link or file" }
        if (ownerOnly) Files.setPosixFilePermissions(path, OWNER_DIRECTORY)
        return path
    }

    private fun launchctl(vararg arguments: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("/bin/launchctl") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); "launchctl timed out" }
        return process.exitValue() to output.take(400).trim()
    }
}

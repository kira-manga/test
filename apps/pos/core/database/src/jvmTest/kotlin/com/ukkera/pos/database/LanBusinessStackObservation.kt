package com.ukkera.pos.database

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Self-authored, NOT independent. Opt-in, observation-only JVM stacks; no business,
 * native/SDK/service timeout, source input, authorization or cleanup is replaced. */
internal fun observeLanBusinessStacks(): AutoCloseable {
    if (!java.lang.Boolean.getBoolean("ukkera.lan.businessStackObservation")) return AutoCloseable { }
    val root = Files.createTempDirectory(Path.of("/var/tmp"), "pos-native-business-stacks-")
    System.err.println("Supporting stock finalize stack observations retained at $root")
    val sampler = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "supporting-native-business-stack-observer").apply { isDaemon = true }
    }
    for (seconds in listOf(5L, 15L, 25L, 40L, 55L)) sampler.schedule({
        val text = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n\n") { (thread, frames) ->
            "${thread.name} [${thread.state}]\n" + frames.joinToString("\n") { "    $it" }
        }
        Files.writeString(root.resolve("finalize-after-${seconds}s.txt"), text)
    }, seconds, TimeUnit.SECONDS)
    return AutoCloseable { sampler.shutdownNow() }
}

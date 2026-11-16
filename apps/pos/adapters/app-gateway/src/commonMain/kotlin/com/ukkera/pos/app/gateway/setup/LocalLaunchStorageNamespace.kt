package com.ukkera.pos.app.gateway.setup

/**
 * Shared launch-hint namespace policy for both Master and LAN roles. An interrupted
 * replacement is unresolved intent, including an older AtomicFile backup. Neither a read
 * nor a later RUN/STOP write may choose a winner, delete it or restore an older role.
 * Native adapters must call this before opening an atomic reader/writer that could repair
 * the namespace. This is not recovery permission or a native file-integrity check.
 */
public object LocalLaunchStorageNamespace {
    public const val FILE_NAME: String = "launch"
    public const val MAXIMUM_FILES: Int = 3
    private val names = setOf(FILE_NAME, "$FILE_NAME.new", "$FILE_NAME.bak")

    /** Null/empty is observed absence; true means exactly the settled launch file exists. */
    public fun requireSettled(observed: Collection<String>?): Boolean {
        if (observed == null) return false
        require(observed.size == observed.toSet().size && observed.all { it in names }) {
            "Local launch storage contains foreign entries"
        }
        check(observed.isEmpty() || observed.toSet() == setOf(FILE_NAME)) {
            "Local launch record write remains unresolved"
        }
        return observed.isNotEmpty()
    }
}

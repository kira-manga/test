package com.ukkera.pos.database

/** Observational only. Never publishes messages, arguments, records, paths or key material. */
internal expect fun reportOriginalInstallationBodyFailure(failure: Throwable)

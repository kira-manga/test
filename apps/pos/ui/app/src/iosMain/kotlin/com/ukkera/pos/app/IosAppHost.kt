package com.ukkera.pos.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Swift owns one instance for its scene. UIKit contributes no competing navigation history. */
class IosAppHost private constructor(private val runtime: UkkeraRuntime) {
    constructor() : this(UkkeraRuntime(AppDependencies(platform = PlatformCapabilities(PlatformFamily.IOS))))
    /** Swift/native trusted composition supplies actual adapters; the default never authenticates. */
    constructor(dependencies: AppDependencies) : this(UkkeraRuntime(dependencies.also {
        require(it.platform.family == PlatformFamily.IOS)
    }))

    fun makeViewController(): UIViewController = ComposeUIViewController { UkkeraApp(runtime) }

    fun lockForBackground() { runtime.lock(runtime.navigator.token()) }

    fun close() { runtime.close() }
}

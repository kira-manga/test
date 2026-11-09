import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.cryptography)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// Resolve the active Xcode Swift libraries when linking the CryptoKit-backed framework.
cryptography { configureSwiftLinkerOpts = true }

kotlin {
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    android {
        namespace = "com.ukkera.pos.app"
        compileSdk = 37
        minSdk = 24
        androidResources.enable = true
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "UkkeraPos"
            isStatic = true
            binaryOption("bundleId", "com.ukkera.pos.shared")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core:types"))
            // Public local-administration UI contracts expose bounded security projections.
            api(project(":core:security"))
            // Complete device-admission review reads the official SDK's public identity values.
            implementation(libs.p2pkit.core)
            // Public catalog drafts share the exact domain unit/conversion values.
            api(project(":domain:catalog"))
            // Public NavKey exposes Compose runtime; export the already pinned runtime alignment.
            api(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.navigation3.ui)
            // Public AppRoute implements NavKey; typed production gateways consume those routes.
            api(libs.navigation3.runtime)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Desktop lifecycle/session cleanup runs on the actual Swing UI dispatcher.
            implementation(libs.coroutines.swing)
            // Actual macOS geteuid/extended-ACL and Windows volume/kernel-file observations.
            implementation(libs.jna)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.compose.ui.test.junit4)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.ukkera.pos.app.resources"
    generateResClass = always
}

tasks.withType<Test>().configureEach { maxHeapSize = "256m" }

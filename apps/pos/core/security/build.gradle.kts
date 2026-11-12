import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    android {
        namespace = "com.ukkera.pos.security"
        compileSdk = 37
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    // Preserve iosMain actuals while adding the separate JVM/Android JCA branch.
    applyDefaultHierarchyTemplate()
    sourceSets {
        val jcaMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jcaMain)
        androidMain.get().dependsOn(jcaMain)
        commonMain.dependencies {
            api(project(":core:types"))
            api(libs.p2pkit.core)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.datetime)
        }
        androidMain.dependencies {
            api(project(":adapters:android-native-files"))
            implementation(libs.p2pkit.lan)
            implementation(libs.sqlite.bundled)
            // Direct RFC8032 primitives; no global JCA provider replacement. The supported
            // Android baseline does not guarantee platform Ed25519 for company publication.
            implementation(libs.bouncycastle.provider)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.cryptokit)
        }
        jvmMain.dependencies { implementation(libs.jna); implementation(libs.jna.platform); implementation(libs.p2pkit.lan) }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }
}
tasks.withType<Test>().configureEach { maxHeapSize = "256m" }

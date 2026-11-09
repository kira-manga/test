import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// Portable recovery policy is available to every app. Concrete JVM/POSIX storage
// stays in jvmMain; mobile consumers must supply their own qualified native ports.
kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    android {
        namespace = "com.ukkera.pos.recovery.store"
        compileSdk = 37
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":core:recovery"))
            api(project(":core:security"))
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.bundled)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.ksp) apply false
}
allprojects {
    group = "com.ukkera.pos"
    version = "0.1.0-local"
    // A restricted constructor must not gain a public copy() factory. In
    // particular, transaction-local authorization/allocation proofs are not DTOs.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
}

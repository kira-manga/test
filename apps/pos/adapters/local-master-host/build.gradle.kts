import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

// Concrete JVM/POSIX host composition. This is not a mobile host or a qualified
// OS supervisor/key provider merely because it compiles.
kotlin {
    explicitApi()
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// Keep Java validation and future Java sources aligned with the Kotlin JVM 17 artifact.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(project(":core:database"))
    api(project(":core:security"))
    api(project(":core:recovery"))
    api(project(":core:types"))
    api(project(":adapters:recovery-store"))
    api(libs.coroutines.core)
    implementation(libs.serialization.json)
}

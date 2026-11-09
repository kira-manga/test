import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
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
        namespace = "com.ukkera.pos.sales.store"
        compileSdk = 37
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:sales"))
            api(project(":core:database"))
            api(project(":adapters:inventory-store"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }
}
// JVM source-integrity tests may construct database DATA, never native authority.
// Friendship is test-only and includes both dependency output forms.
evaluationDependsOn(":core:database")
val databaseJvmMain = project(":core:database").tasks.named<KotlinCompile>("compileKotlinJvm")
val databaseJvmJar = project(":core:database").tasks.named<Jar>("jvmJar")
tasks.named<KotlinCompile>("compileTestKotlinJvm") {
    friendPaths.from(databaseJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(databaseJvmJar.flatMap { it.archiveFile })
}
tasks.withType<Test>().configureEach { maxHeapSize = "256m" }

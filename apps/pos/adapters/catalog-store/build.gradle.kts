import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
        namespace = "com.ukkera.pos.catalog.store"
        compileSdk = 37
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:catalog"))
            api(project(":adapters:inventory-store"))
            api(project(":adapters:sales-store"))
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }
}
// Planner unit tests use internal immutable input carriers, not native authority fixtures.
evaluationDependsOn(":adapters:inventory-store")
evaluationDependsOn(":core:database")
val inventoryJvmMain = project(":adapters:inventory-store").tasks.named<KotlinCompile>("compileKotlinJvm")
val inventoryJvmJar = project(":adapters:inventory-store").tasks.named<Jar>("jvmJar")
val databaseJvmMain = project(":core:database").tasks.named<KotlinCompile>("compileKotlinJvm")
val databaseJvmJar = project(":core:database").tasks.named<Jar>("jvmJar")
tasks.named<KotlinCompile>("compileTestKotlinJvm") {
    friendPaths.from(inventoryJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(inventoryJvmJar.flatMap { it.archiveFile })
    friendPaths.from(databaseJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(databaseJvmJar.flatMap { it.archiveFile })
}
tasks.withType<Test>().configureEach { maxHeapSize = "256m" }

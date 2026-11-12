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
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    android {
        namespace = "com.ukkera.pos.app.gateway"
        compileSdk = 37
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        // Shared JVM desktop + Android code (client journal engine and LAN composition).
        val jcaMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jcaMain)
        androidMain.get().dependsOn(jcaMain)
        jcaMain.dependencies { api(project(":adapters:local-rpc")) }
        commonMain.dependencies {
            api(project(":ui:app"))
            api(project(":core:database"))
            implementation(project(":adapters:catalog-store"))
            implementation(project(":adapters:sales-store"))
            implementation(project(":adapters:inventory-store"))
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            api(libs.p2pkit.lan)
            api(libs.p2pkit.rpc)
        }
        androidMain.dependencies {
            api(project(":adapters:local-rpc"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }
}
// JVM workflow tests use UI-owned seams and controlled database/security DATA constructors only.
// Include both ordinary main output forms so friendship matches the dependency artifact in use.
evaluationDependsOn(":ui:app")
evaluationDependsOn(":core:database")
evaluationDependsOn(":core:security")
val uiAppJvmMain = project(":ui:app").tasks.named<KotlinCompile>("compileKotlinJvm")
val uiAppJvmJar = project(":ui:app").tasks.named<Jar>("jvmJar")
val databaseJvmMain = project(":core:database").tasks.named<KotlinCompile>("compileKotlinJvm")
val databaseJvmJar = project(":core:database").tasks.named<Jar>("jvmJar")
val securityJvmMain = project(":core:security").tasks.named<KotlinCompile>("compileKotlinJvm")
val securityJvmJar = project(":core:security").tasks.named<Jar>("jvmJar")
tasks.named<KotlinCompile>("compileTestKotlinJvm") {
    friendPaths.from(uiAppJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(uiAppJvmJar.flatMap { it.archiveFile })
    friendPaths.from(databaseJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(databaseJvmJar.flatMap { it.archiveFile })
    friendPaths.from(securityJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(securityJvmJar.flatMap { it.archiveFile })
}

tasks.withType<Test>().configureEach { maxHeapSize = "256m" }

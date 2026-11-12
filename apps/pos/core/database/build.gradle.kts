import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.room3)
    alias(libs.plugins.ksp)
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    android {
        namespace = "com.ukkera.pos.database"
        compileSdk = 37
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":core:types"))
            api(project(":core:security"))
            api(project(":core:recovery"))
            api(libs.p2pkit.rpc)
            api(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            // Test-only official JVM manual endpoint/provisioning used by the DR-A09
            // in-memory transport harness. No production dependency is added.
            implementation("io.github.apdelrahman1911:p2p-network-provisioning-desktop:0.8.0-rc12")
            // Test-only reverse edge: exercise the public typed handler through
            // real SQLite, not its internal pure-test seams. Production has no
            // database -> business-domain dependency.
            implementation(project(":adapters:sales-store"))
            implementation(project(":adapters:inventory-store"))
            implementation(project(":adapters:catalog-store"))
            // Test-only reverse edge: real grouped auth/Room -> public client root and
            // encrypted journals. No production database -> application dependency.
            implementation(project(":adapters:app-gateway"))
            // Test-only reverse edge for the production authentication wire codecs.
            // Native RPC owners/registration are not constructed by this harness.
            implementation(project(":adapters:local-rpc"))
        }
    }
}
dependencies {
    add("kspJvm", libs.room3.compiler)
    // The migration qualification database is test-only and must be generated
    // by the actual Room processor, not a handwritten imitation.
    add("kspJvmTest", libs.room3.compiler)
    add("kspAndroid", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
}
room3 { schemaDirectory("$projectDir/schemas") }
// JVM tests exercise the real protected lifecycle service, whose production composition
// exposes module-internal security constructors. This is test-source friendship only; no
// production dependency or accepting runtime provider is added.
evaluationDependsOn(":core:security")
val securityJvmMain = project(":core:security").tasks.named<KotlinCompile>("compileKotlinJvm")
val securityJvmJar = project(":core:security").tasks.named<Jar>("jvmJar")
tasks.named<KotlinCompile>("compileTestKotlinJvm") {
    friendPaths.from(securityJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(securityJvmJar.flatMap { it.archiveFile })
}
// Test-source friendship permits the unchanged production codecs only; it adds no
// public runtime seam and supplies no native admission or original owner.
evaluationDependsOn(":adapters:local-rpc")
val localRpcJvmMain = project(":adapters:local-rpc").tasks.named<KotlinCompile>("compileKotlinJvm")
val localRpcJvmJar = project(":adapters:local-rpc").tasks.named<Jar>("jvmJar")
tasks.named<KotlinCompile>("compileTestKotlinJvm") {
    friendPaths.from(localRpcJvmMain.flatMap { it.destinationDirectory })
    friendPaths.from(localRpcJvmJar.flatMap { it.archiveFile })
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
    doFirst { systemProperty("ukkera.test.runtime.classpath", classpath.asPath) }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
            // Test-only reverse edge: exercise the public typed handler through
            // real SQLite, not its internal pure-test seams. Production has no
            // database -> business-domain dependency.
            implementation(project(":adapters:sales-store"))
            implementation(project(":adapters:inventory-store"))
            implementation(project(":adapters:catalog-store"))
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
tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
    doFirst { systemProperty("ukkera.test.runtime.classpath", classpath.asPath) }
}

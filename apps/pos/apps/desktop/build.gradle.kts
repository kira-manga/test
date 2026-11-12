import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    implementation(project(":ui:app"))
    implementation(project(":adapters:app-gateway"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test-junit"))
}

tasks.withType<Test>().configureEach {
    systemProperty("ukkera.desktop.packaging.dir", layout.projectDirectory.dir("packaging").asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.ukkera.pos.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "UkkeraPOS"
            packageVersion = "0.1.0"
            description = "Ukkera POS local qualification shell — not a live checkout"
            vendor = "Ukkera"
            copyright = "(c) 2026 Ukkera"
            macOS {
                bundleID = "com.ukkera.pos.local.desktop"
                // jpackage requires a positive leading macOS application version.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
            windows {
                // Stable identity so a later MSI can replace this local test install
                // instead of appearing as an unrelated product.
                upgradeUuid = "8B0F4A62-3C7D-4E19-9A5B-6D2F1C8E7A30"
                msiPackageVersion = "0.1.0"
                perUserInstall = true
                shortcut = true
                menu = true
                menuGroup = "Ukkera POS"
            }
        }
    }
}

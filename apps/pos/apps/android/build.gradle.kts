plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ukkera.pos.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ukkera.pos.local"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-local"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        // java.time is used by native acceptance bindings on the declared API24 baseline.
        // The official desugaring runtime also protects transitive Java API compatibility.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Optional standard Gradle signing lets both APKs use the same private certificate.
    // Normal compilation and the existing packaging command need no signing inputs.
    val testingKeyStore = providers.environmentVariable("UKKERA_POS_TEST_KEYSTORE").orNull
    val testingKeyPassword = providers.environmentVariable("UKKERA_POS_TEST_KEYSTORE_PASSWORD").orNull
    require((testingKeyStore == null) == (testingKeyPassword == null)) {
        "Supply both local testing keystore and password, or neither"
    }
    val testingSigning = testingKeyStore?.let {
        signingConfigs.create("localTestingPrivate") {
            storeFile = file(it)
            storeType = "PKCS12"
            storePassword = testingKeyPassword
            keyAlias = "ukkera-pos-local-testing"
            keyPassword = testingKeyPassword
        }
    }
    buildTypes {
        create("localTesting") {
            initWith(getByName("release"))
            // Exercise the real native owner, which correctly refuses debuggable apps.
            // A distinct package keeps synthetic testing away from any normal installation.
            isDebuggable = false
            isMinifyEnabled = false
            applicationIdSuffix = ".testing"
            versionNameSuffix = "-testing"
            // Without opt-in Gradle signing, tools/package_local_android.py signs the APK.
            signingConfig = testingSigning
            matchingFallbacks += listOf("release")
        }
    }
    // The standalone runtime-test module targets this isolated, nondebuggable variant.
    testBuildType = "localTesting"
}

androidComponents {
    beforeVariants(selector().withBuildType("localTesting")) { variantBuilder ->
        // Compile the unchanged native harness only in :apps:android-runtime-tests.
        variantBuilder.deviceTests.values.forEach { it.enable = false }
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)
    implementation(project(":ui:app"))
    // The real same-process setup owner invokes the native receipt/company services.
    // ui/app deliberately does not export these implementation capabilities.
    implementation(project(":core:database"))
    implementation(project(":adapters:app-gateway"))
    implementation(project(":adapters:local-rpc"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.coroutines.core)
}

plugins {
    // AGP is already provided by the application's/KMP library plugins.
    id("com.android.test")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ukkera.pos.android.test"
    compileSdk = 37
    targetProjectPath = ":apps:android"
    defaultConfig {
        minSdk = 24
        targetSdk = 37
        testApplicationId = "com.ukkera.pos.local.testing.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Use the target application's optional private signing inputs for both APKs.
    val testingKeyStore = providers.environmentVariable("UKKERA_POS_TEST_KEYSTORE").orNull
    val testingKeyPassword = providers.environmentVariable("UKKERA_POS_TEST_KEYSTORE_PASSWORD").orNull
    require((testingKeyStore == null) == (testingKeyPassword == null)) {
        "Supply both local testing keystore and password, or neither"
    }
    val testingSigning = testingKeyStore?.let {
        signingConfigs.create("localTestingPrivate") {
            // Preserve the application's handling of relative keystore paths.
            storeFile = project(":apps:android").file(it)
            storeType = "PKCS12"
            storePassword = testingKeyPassword
            keyAlias = "ukkera-pos-local-testing"
            keyPassword = testingKeyPassword
        }
    }
    buildTypes {
        create("localTesting") {
            initWith(getByName("debug"))
            // A debuggable standalone test APK carries the complete official runtime.
            // The application under test retains its nondebuggable native-owner checks.
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = testingSigning
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("main") {
        // Reuse the original harness and frozen assets in place.
        kotlin.srcDir("../android/src/androidTest/kotlin")
        java.srcDir("../android/src/androidTest/java")
        assets.srcDir("../android/src/androidTest/assets")
        assets.srcDir(rootProject.file("../../qa/pos/freezes/retail-handoff-history-v1"))
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        // Matching build-type names select only the application's localTesting APK.
        variantBuilder.enable = variantBuilder.buildType == "localTesting"
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)
    // Production APIs called by the unchanged native fixtures.
    implementation(project(":core:types"))
    implementation(project(":core:security"))
    implementation(project(":core:recovery"))
    implementation(project(":core:database"))
    implementation(project(":domain:pricing"))
    implementation(project(":domain:inventory"))
    implementation(project(":adapters:android-native-files"))
    implementation(project(":adapters:app-gateway"))
    implementation(project(":adapters:catalog-store"))
    implementation(project(":adapters:inventory-store"))
    implementation(project(":adapters:sales-store"))
    implementation(project(":ui:app"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui.test.junit4)
    implementation(libs.coroutines.core)
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.ext:junit:1.3.0")
}

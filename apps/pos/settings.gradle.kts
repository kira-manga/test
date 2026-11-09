pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ukkera-pos"
include(":core:types")
include(":core:recovery", ":core:security", ":core:database")
include(":domain:pricing", ":domain:inventory", ":domain:sales")
include(":domain:catalog", ":adapters:sales-store", ":adapters:inventory-store")
include(":adapters:catalog-store", ":adapters:recovery-store")
include(":adapters:app-gateway")
include(":adapters:local-rpc")
include(":adapters:local-master-host")
include(":adapters:android-native-files")
include(":ui:app", ":apps:desktop", ":apps:android")
include(":apps:android-runtime-tests")

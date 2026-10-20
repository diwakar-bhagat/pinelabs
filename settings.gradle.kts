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
        // Pine Billing SDK local Maven bundle (not on Maven Central).
        // Unzip pine-billing-sdk-android-<version>-prod.zip here. See docs/DEPLOY_E700.md.
        maven { url = uri("app/libs/pine-billing-sdk-android-maven") }
    }
}

rootProject.name = "bohemian-odoo-pos"
include(":app")

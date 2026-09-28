rootProject.name = "KMP-App-Template"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":voice")
include(":db")
include(":core:model")
include(":core:base")
include(":core:network")
include(":core:design")
include(":core:data")
include(":core:audio")
include(":feature:reading")
include(":feature:mine")
include(":feature:ai")
include(":feature:rss")
include(":feature:auth")
include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")

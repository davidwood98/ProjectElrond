pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-downloads a matching JDK for toolchain requests (e.g. :aibackend's Java 17)
    // so the build works on machines without a local JDK 17 installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MSAL (com.microsoft.identity:common) pulls com.microsoft.device.display:display-mask,
        // which is only published to Microsoft's Duo SDK feed — not Central/Google (FA-11).
        maven {
            url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            content { includeGroupByRegex("com\\.microsoft\\.device.*") }
        }
    }
}

rootProject.name = "ProjectElrond"
include(":app")
include(":aibackend")

@file:Suppress("UnstableApiUsage")

val useLocalInnerTubeX = providers.gradleProperty("useLocalInnerTubeX").isPresent
val useMavenLocalInnerTubeX = providers.gradleProperty("useMavenLocalInnerTubeX").isPresent
if (useLocalInnerTubeX && useMavenLocalInnerTubeX) {
    error("-PuseLocalInnerTubeX and -PuseMavenLocalInnerTubeX are mutually exclusive")
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.6.1"
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        publishing.onlyIf { System.getenv("CI") != null }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        if (useMavenLocalInnerTubeX) mavenLocal()
        google()
        mavenCentral()
        maven {
            setUrl("https://jitpack.io")
            content {
                includeGroup("com.github.MetrolistGroup.innertubex")
                includeGroup("com.github.MetrolistGroup.quackjs")
            }
        }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven("https://jogamp.org/deployment/maven")
        maven {
            name = "FDD Maven"
            url = uri("https://maven.firstdark.dev/releases")
        }
    }
}

rootProject.name = "Metrolist"
if (useLocalInnerTubeX) {
    includeBuild("../innertubex") {
        dependencySubstitution {
            substitute(module("com.github.MetrolistGroup.innertubex:innertubex")).using(project(":"))
        }
    }
}
include(":shared")
include(":listenTogetherProto")
include(":opusDecoder")
include(":androidApp")
include(":desktopApp")

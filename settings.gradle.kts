pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // ✅ JitPack 支持 MetaWear
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // ✅ 不要再用 mbientlab.com/releases
    }
}

rootProject.name = "JumpSensorRecorder"
include(":app", ":wear")

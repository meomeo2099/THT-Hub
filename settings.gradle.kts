pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // usb-serial-for-android (MIT) chỉ phát hành qua JitPack; giới hạn đúng nhóm này
        maven(url = "https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}
rootProject.name = "THT-Hub"
include(":app")

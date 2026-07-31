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
        // Tesseract4Android (LSTM OCR, used for Arabic-script languages) is
        // only published here.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MCR PDF Studio"
include(":app")

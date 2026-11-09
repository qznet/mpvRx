pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
        // KSP is published to the Plugin Portal and Maven Central, never Google Maven.
        excludeGroup("com.google.devtools.ksp")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    exclusiveContent {
      forRepository {
        ivy {
          name = "mpvlibGitHubReleases"
          url = uri("https://github.com/Riteshp2001/mpvlibAndroid/releases/download")
          patternLayout {
            artifact("v[revision]/[artifact]-[revision].[ext]")
          }
          metadataSources {
            artifact()
          }
        }
      }
      filter {
        includeGroup("app.gyrolet.mpvlib")
      }
    }
    // FongMi seek-fix kernel: a locally assembled AAR (1.1.8 Java/JNI + a libmpv.so
    // rebuilt from FongMi/mpv after a318357e0a) is published on qznet/mpv-android.
    // Keeping it under a separate group lets standard / no-vulkan flavors continue to
    // resolve the untouched upstream AAR from Riteshp2001/mpvlibAndroid.
    exclusiveContent {
      forRepository {
        ivy {
          name = "qznetFongmiKernel"
          url = uri("https://github.com/qznet/mpv-android/releases/download")
          patternLayout {
            artifact("v[revision]/[artifact]-[revision].[ext]")
          }
          metadataSources {
            artifact()
          }
        }
      }
      filter {
        includeGroup("app.gyrolet.mpvlib.fongmifix")
      }
    }
    maven(url = "https://www.jitpack.io") {
      content {
        // Only use JitPack for specific dependencies to avoid unnecessary checks
        includeGroup("io.github.abdallahmehiz")
        includeGroup("com.github.abdallahmehiz")
        includeGroup("com.github.K1rakishou")
        includeGroup("com.github.marlboro-advance")
        includeGroup("com.github.thegrizzlylabs")
      }
    }
  }
}

rootProject.name = "mpvRx"
include(":app")

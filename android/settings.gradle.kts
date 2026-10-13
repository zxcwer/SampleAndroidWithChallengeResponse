// Gradle 8.14 / Kotlin 2.1 / AGP 8.10 は JDK 17〜21 で動かす。JDK 22 以降だと Kotlin プラグインが
// "What went wrong: 25" のような分かりにくいエラーで落ちるため、先に分かりやすく止める。
run {
    val major = JavaVersion.current().majorVersion.toIntOrNull() ?: 0
    if (major !in 17..21) {
        throw GradleException(
            """
            Gradle is running on JDK $major, but this project needs JDK 17-21.
              macOS : JAVA_HOME=${'$'}(/usr/libexec/java_home -v 21) ./gradlew ...
              or use Android Studio's bundled JDK:
                      JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew ...
            """.trimIndent(),
        )
    }
}

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
rootProject.name = "SampleAndroidWithChallengeResponse"
include(":authclient", ":app")

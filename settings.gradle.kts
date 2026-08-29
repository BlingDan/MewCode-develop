rootProject.name = "mewcode"

pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://plugins-artifacts.gradle.org/m2") }
        gradlePluginPortal()
    }
}

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "org.rri.ideals.server"
version = System.getenv("IDEALS_VERSION") ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
        bundledPlugin("com.intellij.java")
        // plugin("com.jetbrains.python:2026.1.1") // Python plugin not available for 2026.1
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.17.0")
    implementation("io.github.furstenheim:copy_down:1.1")
    testImplementation("org.jsoup:jsoup:1.16.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

configurations.all {
    exclude("org.jsoup", "jsoup")
    resolutionStrategy.eachDependency {
        if (requested.group == "org.junit.platform") {
            useVersion("1.10.2")
        }
        if (requested.group == "org.junit.jupiter") {
            useVersion("5.10.2")
        }
        if (requested.group == "org.junit.vintage") {
            useVersion("5.10.2")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*" // IntelliJ 2026.1 branch
        }
    }
}

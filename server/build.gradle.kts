import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "tf.locals.idealsp.server"
version = System.getenv("IDEALSP_VERSION") ?: "1.0-SNAPSHOT"

val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated-resources")
    inputs.property("version", project.version)
    inputs.property("githubSha", System.getenv("GITHUB_SHA") ?: "local")
    outputs.dir(outDir)
    doLast {
        val commit = System.getenv("GITHUB_SHA")?.take(8)
            ?: runCatching {
                ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                    .redirectErrorStream(true)
                    .start().inputStream.bufferedReader().readText().trim()
            }.getOrNull()
            ?.ifEmpty { null }
            ?: "unknown"
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "build-info.properties").writeText(
            "version=${project.version}\ngit.commit=$commit\n"
        )
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}
tasks.named("processResources") { dependsOn(generateBuildInfo) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
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
            sinceBuild = "253.32098"
            untilBuild = "262.*" // IntelliJ 2025.3 - 2026.1
        }
    }
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("intellij.platform.test.output.mode", "console")
    systemProperty("idea.test.cyclic.buffer.size", "1048576")
    outputs.upToDateWhen { false }
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                val summary = mutableListOf<String>()
                if (result.failedTestCount > 0) {
                    summary.add("${result.failedTestCount} failed")
                }
                summary.add("${result.successfulTestCount} passed")
                if (result.skippedTestCount > 0) {
                    summary.add("${result.skippedTestCount} skipped")
                }
                println("\nTests: ${result.testCount} total, ${summary.joinToString(", ")} (${result.resultType})")
            }
        }
        override fun beforeTest(test: TestDescriptor) {}
        override fun afterTest(test: TestDescriptor, result: TestResult) {
            if (result.resultType == TestResult.ResultType.FAILURE) {
                val className = test.className?.substringAfterLast('.') ?: test.className ?: "Unknown"
                println("  FAILED  $className.${test.name}")
                result.exceptions.forEach { ex ->
                    println("    ${ex.javaClass.name}: ${ex.message?.take(200)}")
                    ex.stackTrace.take(5).forEach { frame ->
                        println("      at $frame")
                    }
                }
            }
        }
    })
}

tasks.runIde {
    jvmArgs("-Djava.awt.headless=true")
}

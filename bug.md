# Bug: Debug println left in IntelliJ Platform Gradle Plugin 2.14.0 - ✅ FIXED UPSTREAM (2.16.0)

## Repo to file at

https://github.com/JetBrains/intellij-platform-gradle-plugin/issues

## Suggested title

`Debug println in moduleDescriptorCoordinates dumps ~2000 lines to stdout on every build`

## Status: ✅ FIXED UPSTREAM (2.16.0)

The stdout spam workaround has been removed from settings.gradle.kts and the plugin
bumped to 2.16.0 which no longer contains the debug println.

## Body

### Plugin version

2.14.0

### Problem

`IntelliJPlatformDependenciesHelper.moduleDescriptorCoordinates()` contains a debug `println` that dumps all module descriptor coordinates to stdout on every Gradle invocation. This produces ~2000 lines of noise:

```
it = 
com.jetbrains.intellij.platform:core-nio-fs
com.jetbrains.intellij.java:java-ide-resources
com.jetbrains.intellij.jsp:jsp-base
com.jetbrains.intellij.platform:ml
com.jetbrains.intellij.platform:sqlite
... (~2000 more lines)
```

### Location in source

File: `src/main/kotlin/org/jetbrains/intellij/platform/gradle/extensions/IntelliJPlatformDependenciesHelper.kt`

Method: `moduleDescriptorCoordinates()`, inside the `.also {}` block:

```kotlin
private fun moduleDescriptorCoordinates(platformPath: Path) =
    moduleDescriptorCoordinatesService.get().resolve(platformPath) {
        providers.of(ModuleDescriptorsValueSource::class) {
            parameters {
                intellijPlatformPath = layout.dir(provider { platformPath.toFile() })
            }
        }.get().also {
            println("it = \n${it.joinToString("\n")}")   // <-- this line
        }
    }
```

### Impact

- Fires on every Gradle invocation (build, test, any task that triggers dependency resolution)
- Produces ~2000 lines of stdout spam
- Cannot be suppressed by `--quiet` or `logging.level` since it uses `System.out.println` directly, not the Gradle logging API
- Makes CI logs and terminal output unusable

### Fix Applied (Bump to 2.16.0)

The `moduleDescriptorCoordinates()` debug println was removed upstream in version 2.16.0.
The project now uses `org.jetbrains.intellij.platform` version `2.16.0` in `build.gradle.kts`
and the stdout suppression workaround has been removed from `settings.gradle.kts`.

### Resolution

Upstream removed the debug println in 2.16.0. No local workaround needed.

### Environment

- Plugin: `org.jetbrains.intellij.platform` 2.16.0
- Gradle: 9.4.1
- IntelliJ target: 2026.1
- OS: Linux

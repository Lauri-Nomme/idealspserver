# Bug: Debug println left in IntelliJ Platform Gradle Plugin 2.14.0

## Repo to file at

https://github.com/JetBrains/intellij-platform-gradle-plugin/issues

## Suggested title

`Debug println in moduleDescriptorCoordinates dumps ~2000 lines to stdout on every build`

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

### Confirmed in bytecode

Disassembly of `IntelliJPlatformDependenciesHelper.class` from the published 2.14.0 jar shows:

```
invokestatic  CollectionsKt.joinToString$default
invokedynamic makeConcatWithConstants:(Ljava/lang/String;)Ljava/lang/String;
getstatic     java/lang/System.out:Ljava/io/PrintStream;
invokevirtual java/io/PrintStream.println:(Ljava/lang/Object;)V
```

### Impact

- Fires on every Gradle invocation (build, test, any task that triggers dependency resolution)
- Produces ~2000 lines of stdout spam
- Cannot be suppressed by `--quiet` or `logging.level` since it uses `System.out.println` directly, not the Gradle logging API
- Makes CI logs and terminal output unusable

### Fix

Remove or replace the `println` with `logger.debug()`:

```kotlin
// Remove this:
println("it = \n${it.joinToString("\n")}")

// Or replace with:
logger.debug("moduleDescriptorCoordinates resolved: {}", it.size)
```

### Workaround

Install a filtering `PrintStream` in `settings.gradle.kts`:

```kotlin
val originalOut = System.out
System.setOut(object : java.io.PrintStream(originalOut, true) {
    private var suppressing = false
    override fun println(x: Any?) {
        val s = x?.toString() ?: "null"
        if (s.startsWith("it = ")) { suppressing = true; return }
        if (suppressing) {
            if (s.matches(Regex("^[a-zA-Z][a-zA-Z0-9._-]*:[a-zA-Z][a-zA-Z0-9._-]*$"))) return
            suppressing = false
        }
        originalOut.println(s)
    }
})
```

### Environment

- Plugin: `org.jetbrains.intellij.platform` 2.14.0
- Gradle: 9.4.1
- IntelliJ target: 2026.1
- OS: Linux

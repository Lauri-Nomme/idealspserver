rootProject.name = "server"

// Suppress debug println spam from IntelliJ Platform Gradle Plugin 2.14.0
// (moduleDescriptorCoordinates prints all module coordinates to stdout)
val originalOut = System.out
System.setOut(object : java.io.PrintStream(originalOut, true) {
    private var suppressing = false

    override fun println(x: Any?) {
        val s = x?.toString() ?: "null"
        if (s.startsWith("it = ")) {
            suppressing = true
            return
        }
        if (suppressing) {
            // Coordinate lines are like "com.jetbrains.intellij.platform:core-nio-fs"
            if (s.matches(Regex("^[a-zA-Z][a-zA-Z0-9._-]*:[a-zA-Z][a-zA-Z0-9._-]*$"))) {
                return
            }
            suppressing = false
        }
        originalOut.println(s)
    }
})
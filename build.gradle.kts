plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
    // Declared here only to put it on the build classpath. composeApp applies it by id, and only
    // when google-services.json is actually present — see that build script.
    alias(libs.plugins.googleServices) apply false
}

/**
 * The rule from 02, checked by the build rather than by review (D21): an app module may reach the
 * library's contract and its widgets, and nothing else. An app that could see `superizer:host`
 * could create a platform service of its own, and §19 would be a suggestion.
 *
 * Read as text rather than off the resolved graph on purpose: the rule is about what somebody
 * *wrote*, the check has to survive the configuration cache, and a stray `superizer.host` import is
 * exactly the mistake being looked for.
 */
val forbiddenForApps = listOf("libs.superizer.host", "superizer:host", "project(\":composeApp\")")

val checkDependencyRules by tasks.registering {
    group = "verification"
    description = "Fails when an app module reaches for the host or for another app."

    val appBuildFiles = subprojects
        .filter { it.path.startsWith(":apps:") && it.buildFile.exists() }
        .associate { it.path to it.buildFile }
    appBuildFiles.values.forEach { inputs.file(it).withPathSensitivity(PathSensitivity.RELATIVE) }
    // Captured here, not read from the script at execution time: with the configuration cache the
    // script object is gone by then, and a task that reaches for it fails with a null that names
    // nothing.
    val forbidden = forbiddenForApps
    val stamp = layout.buildDirectory.file("dependency-rules.ok")
    outputs.file(stamp)

    doLast {
        // Comments are stripped first. The KDoc on each app module says in words what this task
        // says in code — "no `superizer:host`, no other app" — and a check that matched its own
        // documentation would be a check that can only be satisfied by deleting the explanation.
        fun code(script: String): String = script.lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

        val violations = buildList {
            appBuildFiles.forEach { (path, file) ->
                val text = code(file.readText())
                forbidden.forEach { needle ->
                    if (needle in text) add("$path must not depend on $needle")
                }
                Regex(""":apps:([a-z-]+)""").findAll(text).forEach { match ->
                    if (":apps:${match.groupValues[1]}" != path) {
                        add("$path must not depend on :apps:${match.groupValues[1]}")
                    }
                }
            }
        }
        check(violations.isEmpty()) {
            "Dependency rules (02) violated:\n" + violations.joinToString("\n") { "  - $it" }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Static checks plus every test that needs no browser or device."
    dependsOn(checkDependencyRules)
    dependsOn(":composeApp:desktopTest", ":apps:calculator:desktopTest", ":apps:currency-converter:desktopTest")
}

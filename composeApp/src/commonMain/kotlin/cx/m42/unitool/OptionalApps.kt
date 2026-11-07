package cx.m42.unitool

import cx.m42.superizer.host.SuperizerBuilder

/**
 * The apps that are in the build only when their module is (.ai-notes/app-learn D642): Learning English
 * comes from the submodule `external/app-learn`, which a plain clone leaves empty. Common code cannot
 * name a class that may not exist, so the body is one of two files under `src/optional/`, picked by
 * `composeApp/build.gradle.kts`: `learn/` when `:apps:learn` is in the build, `none/` otherwise.
 * The web build always gets `none/` (D646): learn has no wasmJs target.
 */
internal expect fun SuperizerBuilder.registerOptionalApps()

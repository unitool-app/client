package cx.m42.unitool

import cx.m42.english.learn.LearnApp
import cx.m42.superizer.host.SuperizerBuilder

// The submodule is checked out: Learning English is in. In the catalog, not on a fresh Home (D48).
internal actual fun SuperizerBuilder.registerOptionalApps() {
    register(LearnApp())
}

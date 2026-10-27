package cx.m42.unitool.cli.app

import cx.m42.unitool.cli.exec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun binaryListsItsAppsAndAnswersVersion() {
        val help = unitool().exec("--help")
        assertEquals(0, help.code)
        assertTrue("  qr        Make a QR code from text" in help.out, help.out)
        assertTrue("  split     Keep a secret in parts" in help.out, help.out)
        assertTrue("  browser   Fill passwords from the phone" in help.out, help.out)
        assertTrue("  password  Generate passwords from the device's entropy" in help.out, help.out)
        assertEquals("unitool $VERSION\n", unitool().exec("--version").out)
    }
}

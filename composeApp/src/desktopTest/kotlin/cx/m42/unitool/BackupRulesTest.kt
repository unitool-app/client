package cx.m42.unitool

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * I10 (ssh-new 03 §2, §5): Android's two sets of backup rules keep the keyring's records out, so
 * neither Google's backup nor a device transfer carries them. Every section lists what it takes
 * (`include` only, never a whole domain) and the records are in none of them. An `exclude` would
 * say it more plainly, but release lint refuses an `exclude` outside an `include` — so this test is
 * the guard against the rules ever turning into "everything but" (impl, 03 §2).
 */
class BackupRulesTest {

    private val records = "cx.m42.services.ssh.xml"

    private class Rule(val section: String, val tag: String, val domain: String, val path: String)

    private fun rules(name: String): List<Rule> {
        val file = listOf(File("src/androidMain/res/xml/$name"), File("composeApp/src/androidMain/res/xml/$name")).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Rule>()
        for (tag in listOf("include", "exclude")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val path = node.attributes.getNamedItem("path")?.nodeValue ?: "."
                out += Rule(node.parentNode.nodeName, tag, node.attributes.getNamedItem("domain").nodeValue, path)
            }
        }
        return out
    }

    private fun assertIncludeOnly(file: String, sections: List<String>) {
        val all = rules(file)
        for (section in sections) {
            val included = all.filter { it.section == section && it.tag == "include" }
            assertTrue(included.isNotEmpty(), "$file/$section: no include means everything is backed up")
            assertTrue(included.none { it.path == "." || it.path.isEmpty() || it.path == "/" }, "$file/$section: a whole domain is included")
            assertTrue(included.none { it.path == records }, "$file/$section: the keyring's records are included")
        }
    }

    @Test
    fun everySectionTakesOnlyWhatItNamesAndTheKeyringsRecordsAreNotNamed() {
        assertIncludeOnly("backup_rules.xml", listOf("full-backup-content"))
        assertIncludeOnly("data_extraction_rules.xml", listOf("cloud-backup", "device-transfer"))
    }
}

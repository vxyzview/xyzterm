package com.rk

import android.content.Context
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Pins the cloud-backup policy for a terminal.
 *
 * Both `data_extraction_rules.xml` and `backup_rules.xml` were still the unmodified Android
 * Studio templates — empty element bodies, which mean *include everything* — while
 * `android:allowBackup="true"`. For a terminal that silently uploads `~/.ssh`, shell history,
 * `.env` files and the whole rootfs to the user's Google Drive by default.
 *
 * Files must be reachable through the app's own Backup & restore screen (Settings → Terminal →
 * Backups), which the user drives explicitly, and not through a mechanism that fires on its own.
 *
 * The production change these tests catch: widening the cloud-backup set back to `file`.
 */
@RunWith(RobolectricTestRunner::class)
class BackupPolicyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun parse(@XmlRes resId: Int): Element {
        val stream: InputStream = context.resources.openRawResource(resId)
        return stream.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
        }
    }

    private fun entries(root: Element, section: String, tag: String): List<String> {
        val container = root.getElementsByTagName(section).item(0)
        return (0 until container.childNodes.length)
            .map { container.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && (it as Element).tagName == tag }
            .map { (it as Element).let { e -> e.attributes.getNamedItem("domain").nodeValue + "/" + e.attributes.getNamedItem("path").nodeValue } }
    }

    @Test
    fun `cloud backup does not upload the sandbox or the guest home directory`() {
        val excludes = entries(parse(R.xml.data_extraction_rules), "cloud-backup", "exclude")

        assertThat(excludes).contains("file/.")
    }

    @Test
    fun `cloud backup does not upload databases`() {
        val excludes = entries(parse(R.xml.data_extraction_rules), "cloud-backup", "exclude")

        assertThat(excludes).contains("database/.")
    }

    @Test
    fun `cloud backup still keeps settings, which are small and safe to restore`() {
        val includes = entries(parse(R.xml.data_extraction_rules), "cloud-backup", "include")

        assertThat(includes).contains("sharedpref/.")
    }

    @Test
    fun `legacy backup rules for pre-31 devices also exclude files`() {
        val root = parse(R.xml.backup_rules)

        assertThat(entries(root, "full-backup-content", "exclude")).contains("file/.")
    }
}
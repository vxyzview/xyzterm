package com.rk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

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

    private fun resId(name: String): Int {
        val id = context.resources.getIdentifier(name, "xml", context.packageName)
        assertThat(id).isNotEqualTo(0)
        return id
    }

    /**
     * Walks the compiled resource with a pull parser.
     *
     * `openRawResource` returns AAPT2's *binary* XML, so a DOM parser fails with
     * MalformedByteSequenceException. Resources.getXml gives a parser that decodes it.
     */
    private fun entries(resName: String, section: String, tag: String): List<String> {
        val parser = context.resources.getXml(resId(resName))
        val found = mutableListOf<String>()
        var inSection = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        section -> inSection = true
                        tag ->
                            if (inSection) {
                                val domain = parser.getAttributeValue(null, "domain")
                                val path = parser.getAttributeValue(null, "path")
                                found += "$domain/$path"
                            }
                    }
                }
                XmlPullParser.END_TAG ->
                    if (parser.name == section) {
                        inSection = false
                    }
            }
            event = parser.next()
        }
        return found
    }

    @Test
    fun `cloud backup does not upload the sandbox or the guest home directory`() {
        assertThat(entries("data_extraction_rules", "cloud-backup", "exclude")).contains("file/.")
    }

    @Test
    fun `cloud backup does not upload databases`() {
        assertThat(entries("data_extraction_rules", "cloud-backup", "exclude")).contains("database/.")
    }

    @Test
    fun `cloud backup still keeps settings, which are small and safe to restore`() {
        assertThat(entries("data_extraction_rules", "cloud-backup", "include")).contains("sharedpref/.")
    }

    @Test
    fun `device transfer does carry files, which is a different contract`() {
        assertThat(entries("data_extraction_rules", "device-transfer", "include")).contains("file/.")
    }

    @Test
    fun `legacy backup rules for pre-31 devices also exclude files`() {
        assertThat(entries("backup_rules", "full-backup-content", "exclude")).contains("file/.")
    }
}
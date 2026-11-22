import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Bundles the pinned archm rootfs tarballs into the terminal assets. All state
 * lives in Gradle properties so the task works with the configuration cache.
 */
abstract class DownloadRootfsTask : DefaultTask() {
    @get:Input abstract val baseUrl: Property<String>

    @get:Input abstract val digests: MapProperty<String, String>

    @get:OutputDirectory abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun download() {
        val assets = assetsDir.get().asFile
        val base = baseUrl.get()
        val expectedDigests = digests.get()
        for (abi in listOf("arm64", "arm")) {
            val name = "archm-$abi.tar.gz"
            val expected = expectedDigests[abi] ?: error("No pinned SHA-256 for $name in Constants.kt")
            val target = File(assets, name)
            if (target.exists() && sha256(target) == expected) continue
            assets.mkdirs()
            val partial = File(assets, "$name.part")
            URL("$base/$name").openStream().use { input ->
                FileOutputStream(partial).use { output -> input.copyTo(output) }
            }
            val actual = sha256(partial)
            check(actual == expected) { "SHA-256 mismatch for $name: expected $expected, got $actual" }
            check(partial.renameTo(target)) { "Failed to move $name into assets" }
        }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

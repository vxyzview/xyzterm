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

    @get:OutputDirectory abstract val armAssetsDir: DirectoryProperty
    @get:OutputDirectory abstract val arm64AssetsDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dirs = mapOf("arm" to armAssetsDir.get().asFile, "arm64" to arm64AssetsDir.get().asFile)
        val base = baseUrl.get()
        val expectedDigests = digests.get()
        for (abi in listOf("arm64", "arm")) {
            val remoteName = "archm-$abi.tar.gz"
            // .gz assets are transparently decompressed by aapt; store the same
            // gzip bytes under a neutral name so the pinned digest still matches.
            val assetName = "archm-$abi.bin"
            val expected = expectedDigests[abi] ?: error("No pinned SHA-256 for $remoteName in Constants.kt")
            // Each flavor gets its own asset tree so only one rootfs is packaged.
            val assets = dirs.getValue(abi)
            val target = File(assets, assetName)
            if (target.exists() && sha256(target) == expected) continue
            assets.mkdirs()
            val partial = File(assets, "$assetName.part")
            URL("$base/$remoteName").openStream().use { input ->
                FileOutputStream(partial).use { output -> input.copyTo(output) }
            }
            val actual = sha256(partial)
            check(actual == expected) { "SHA-256 mismatch for $remoteName: expected $expected, got $actual" }
            check(partial.renameTo(target)) { "Failed to move $assetName into assets" }
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

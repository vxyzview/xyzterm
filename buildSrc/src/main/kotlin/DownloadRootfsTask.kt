import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Downloads the latest archm release, unpacks it here, and ships the extracted
 * tree as a zip + manifest so the app never runs tar or proot on first launch.
 */
abstract class DownloadRootfsTask : DefaultTask() {
    @get:Input abstract val baseUrl: Property<String>

    @get:Internal abstract val stagingDir: DirectoryProperty
    @get:OutputDirectory abstract val armAssetsDir: DirectoryProperty
    @get:OutputDirectory abstract val arm64AssetsDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dirs = mapOf("arm" to armAssetsDir.get().asFile, "arm64" to arm64AssetsDir.get().asFile)
        val base = baseUrl.get()

        for (abi in listOf("arm", "arm64")) {
            val remote = "archm-$abi.tar.gz"
            val expected = URL("$base/$remote.sha256").readText().trim().split(Regex("\\s+")).first()
            val staging = File(stagingDir.get().asFile, abi)
            staging.deleteRecursively()
            staging.mkdirs()
            val tarball = File(staging, remote)
            URL("$base/$remote").openStream().use { input ->
                FileOutputStream(tarball).use { output -> input.copyTo(output) }
            }
            check(sha256(tarball) == expected) { "SHA-256 mismatch for $remote" }

            val tree = File(staging, "tree")
            tree.mkdirs()
            val process =
                ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", tree.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            check(process.waitFor() == 0) { "Failed to extract $remote" }
            // Some archm helpers ship with restrictive modes; give the build
            // user access so they can be packaged. Runtime modes come from the manifest.
            Files.walkFileTree(
                tree.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        grantBuildUser(dir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        grantBuildUser(file)
                        return FileVisitResult.CONTINUE
                    }
                },
            )

            val assets = dirs.getValue(abi)
            assets.mkdirs()
            val entries = StringBuilder("[")
            var first = true
            var totalBytes = 0L
            val zip = File(assets, "rootfs-$abi.zip")
            ZipOutputStream(FileOutputStream(zip)).use { out ->
                Files.walk(tree.toPath()).use { paths ->
                    paths.iterator().let { it ->
                        while (it.hasNext()) {
                            val path = it.next()
                            val rel = tree.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                            if (rel.isEmpty()) continue
                            val kind =
                                when {
                                    Files.isSymbolicLink(path) -> "l"
                                    Files.isDirectory(path) -> "d"
                                    Files.isRegularFile(path) -> "f"
                                    else -> continue
                                }
                            val mode = posixMode(path)
                            val target = if (kind == "l") Files.readSymbolicLink(path).toString() else null
                            val size = if (kind == "f") Files.size(path) else 0L
                            totalBytes += size
                            if (!first) entries.append(',')
                            first = false
                            entries.append("{\"p\":\"").append(escape(rel)).append("\",\"t\":\"").append(kind)
                                .append("\",\"m\":").append(mode).append(",\"s\":").append(size)
                            if (target != null) entries.append(",\"to\":\"").append(escape(target)).append("\"")
                            entries.append('}')

                            if (kind == "f") {
                                val entry = ZipEntry(rel)
                                entry.time = 0L
                                out.putNextEntry(entry)
                                Files.newInputStream(path).use { input -> input.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                    }
                }
            }
            entries.append(']')
            File(assets, "rootfs-$abi.json").writeText("{\"bytes\":$totalBytes,\"entries\":$entries}")
            File(assets, "archm-$abi.bin").delete()
            File(assets, "archm-$abi.bin.part").delete()
        }
    }
}

private fun grantBuildUser(path: Path) {
    runCatching {
        val permissions = Files.getPosixFilePermissions(path).toMutableSet()
        permissions += PosixFilePermission.OWNER_READ
        permissions += PosixFilePermission.OWNER_WRITE
        permissions += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(path, permissions)
    }
}

private fun posixMode(path: Path): Int {
    val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return 0
    var mode = 0
    if (PosixFilePermission.OWNER_READ in permissions) mode = mode or 0b100000000
    if (PosixFilePermission.OWNER_WRITE in permissions) mode = mode or 0b010000000
    if (PosixFilePermission.OWNER_EXECUTE in permissions) mode = mode or 0b001000000
    if (PosixFilePermission.GROUP_READ in permissions) mode = mode or 0b000100000
    if (PosixFilePermission.GROUP_WRITE in permissions) mode = mode or 0b000010000
    if (PosixFilePermission.GROUP_EXECUTE in permissions) mode = mode or 0b000001000
    if (PosixFilePermission.OTHERS_READ in permissions) mode = mode or 0b000000100
    if (PosixFilePermission.OTHERS_WRITE in permissions) mode = mode or 0b000000010
    if (PosixFilePermission.OTHERS_EXECUTE in permissions) mode = mode or 0b000000001
    return mode
}

private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

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

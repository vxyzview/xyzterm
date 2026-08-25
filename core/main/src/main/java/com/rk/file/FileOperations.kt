package com.rk.file

data class ContentProgress(val totalSize: Long, val totalItems: Long)

object FileOperations {
    /**
     * Recursively calculates the total size and item count (files and directories) within a given folder or file.
     *
     * @param folder The [FileObject] to start the calculation from.
     * @return A [ContentProgress] containing total size and total item count.
     */
    suspend fun calculateContent(folder: FileObject): ContentProgress {
        var totalSize = 0L
        var totalItems = 0L

        val stack = ArrayDeque<FileObject>()
        stack.add(folder)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            runCatching {
                if (current.isDirectory()) {
                    stack.addAll(current.listFiles())
                    totalItems++
                } else {
                    totalSize += current.length()
                    totalItems++
                }
            }
        }

        return ContentProgress(totalSize, totalItems)
    }
}

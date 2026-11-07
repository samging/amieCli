package org.example.project.settings

import java.io.File

/** What the tree of the AMP folder does to the files: deletes, and prunes the folders that end up empty */
object AmpFiles {
    /**
     * Deletes every folder under [root] that holds nothing (bottom up, so a folder of empty folders goes too);
     * the root itself stays. Returns the folders removed. The app makes any folder it needs again with mkdirs,
     * so an empty one has nothing to keep.
     */
    fun pruneEmptyFolders(root: File): List<File> {
        val removed = mutableListOf<File>()
        fun prune(dir: File): Boolean {
            val children = dir.listFiles() ?: return false
            var empty = true
            for (child in children) {
                if (child.isDirectory) { if (!prune(child)) empty = false }
                else empty = false
            }
            if (empty && dir != root && dir.delete()) { removed += dir; return true }
            return empty && dir != root
        }
        prune(root)
        return removed
    }

    /** Deletes the file (or folder with everything in it), then every folder under [root] that is left holding nothing */
    fun delete(root: File, target: File): Boolean {
        val gone = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!gone) return false
        pruneEmptyFolders(root)
        return true
    }

    /** "1.2 KB", "3 MB" */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(java.util.Locale.US, bytes / 1024.0)} KB"
        else -> "${"%.1f".format(java.util.Locale.US, bytes / (1024.0 * 1024))} MB"
    }
}

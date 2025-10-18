package app.revanced.patcher

import java.io.File
import java.io.InputStream

/**
 * The result of a patcher.
 *
 * @param dexFiles The patched dex files.
 * @param resources The patched resources.
 */
@Suppress("MemberVisibilityCanBePrivate")
class PatcherResult internal constructor(
    val dexFiles: Set<PatchedDexFile>,
    val resources: PatchedResources?,
) {

    /**
     * A dex file.
     *
     * @param name The original name of the dex file.
     * @param streamSupplier Supplier for the dex file [InputStream].
     */
    class PatchedDexFile internal constructor(
        val name: String, 
        private val streamSupplier: () -> InputStream
    ) {
        /**
         * Get the InputStream for this dex file.
         */
        val stream: InputStream
            get() = streamSupplier()
        
        // Constructor for immediate InputStream (backwards compatibility)
        internal constructor(name: String, stream: InputStream) : this(name, { stream })
        
        internal companion object {
            /**
             * Create a [PatchedDexFile] from a [File].
             * Reads the file content immediately to avoid issues with file moving on Windows.
             *
             * @param file The file to create the [PatchedDexFile] from.
             * @return A [PatchedDexFile] with the file content.
             */
            internal fun fromFile(file: File): PatchedDexFile {
                // Read file content immediately before it gets moved
                val content = file.readBytes()
                return PatchedDexFile(file.name) { content.inputStream() }
            }
        }
    }

    /**
     * The resources of a patched apk.
     *
     * @param resourcesApk The compiled resources.apk file.
     * @param otherResources The directory containing other resources files.
     * @param doNotCompress List of files that should not be compressed.
     * @param deleteResources List of resources that should be deleted.
     */
    class PatchedResources internal constructor(
        val resourcesApk: File?,
        val otherResources: File?,
        val doNotCompress: Set<String>,
        val deleteResources: Set<String>,
    )
}

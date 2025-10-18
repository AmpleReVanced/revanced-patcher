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
     * @param stream The dex file as [InputStream].
     */
    class PatchedDexFile internal constructor(val name: String, val stream: InputStream) {
        internal companion object {
            /**
             * Create a [PatchedDexFile] from a [File].
             * The InputStream is created lazily to avoid file locking issues on Windows.
             *
             * @param file The file to create the [PatchedDexFile] from.
             * @return A [PatchedDexFile] with a lazy InputStream.
             */
            internal fun fromFile(file: File): PatchedDexFile {
                return PatchedDexFile(file.name, file.inputStream())
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

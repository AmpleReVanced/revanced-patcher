package app.revanced.patcher

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.ResourcePatchContext
import com.reandroid.apk.ApkModule
import java.io.Closeable
import java.io.IOException

/**
 * A context for the patcher containing the current state of the patcher.
 *
 * @param config The configuration for the patcher.
 */
@Suppress("MemberVisibilityCanBePrivate")
class PatcherContext internal constructor(config: PatcherConfig): Closeable {
    /**
     * [PackageMetadata] of the supplied [PatcherConfig.apkFile].
     */
    val packageMetadata: PackageMetadata

    init {
        try {
            val apkModule = ApkModule.loadApkFile(config.apkFile).apply {
                setLoadDefaultFramework(true)
            }

            config.frameworkDirectory?.let { frameworkDir ->
                if (frameworkDir.exists() && frameworkDir.isDirectory) {
                    frameworkDir.listFiles()?.forEach { frameworkFile ->
                        if (frameworkFile.extension == "apk") {
                            apkModule.addExternalFramework(frameworkFile)
                        }
                    }
                }
            }

            config.externalFrameworks.forEach { frameworkFile ->
                apkModule.addExternalFramework(frameworkFile)
            }

            packageMetadata = PackageMetadata(apkModule)
        } catch (e: IOException) {
            throw IllegalStateException("Failed to load APK file: ${config.apkFile.absolutePath}", e)
        }
    }

    /**
     * The set of [Patch]es.
     */
    internal val executablePatches = mutableSetOf<Patch<*>>()

    /**
     * The set of all [Patch]es and their dependencies.
     */
    internal val allPatches = mutableSetOf<Patch<*>>()

    /**
     * The context for patches containing the current state of the resources.
     */
    internal val resourceContext = ResourcePatchContext(packageMetadata, config)

    /**
     * The context for patches containing the current state of the bytecode.
     */
    internal val bytecodeContext = BytecodePatchContext(config)

    override fun close() {
        bytecodeContext.close()
        packageMetadata.apkModule.close()
    }
}

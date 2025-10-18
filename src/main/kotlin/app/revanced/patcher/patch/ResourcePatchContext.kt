package app.revanced.patcher.patch

import app.revanced.patcher.InternalApi
import app.revanced.patcher.PackageMetadata
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.PatcherResult
import app.revanced.patcher.util.Document
import com.reandroid.apk.ApkModuleRawDecoder
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.archive.InputSource
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.logging.Logger

/**
 * A context for patches containing the current state of resources.
 *
 * @param packageMetadata The [PackageMetadata] of the apk file.
 * @param config The [PatcherConfig] used to create this context.
 */
class ResourcePatchContext internal constructor(
    private val packageMetadata: PackageMetadata,
    private val config: PatcherConfig,
) : PatchContext<PatcherResult.PatchedResources?> {
    private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

    /**
     * Read a document from an [InputStream].
     */
    fun document(inputStream: InputStream) = Document(inputStream)

    /**
     * Read and write documents in the [PatcherConfig.apkFiles].
     */
    fun document(path: String) = Document(get(path))

    /**
     * Set of resources from [PatcherConfig.apkFiles] to delete.
     */
    private val deleteResources = mutableSetOf<String>()

    /**
     * Decode resources of [PatcherConfig.apkFile].
     *
     * @param mode The [ResourceMode] to use.
     */
    internal fun decodeResources(mode: ResourceMode) =
        with(packageMetadata.apkModule) {
            config.initializeTemporaryFilesDirectories()

            if (mode == ResourceMode.FULL) {
                logger.info("Decoding resources")

                setLoadDefaultFramework(true)
                
                config.frameworkDirectory?.let { frameworkDir ->
                    if (frameworkDir.exists() && frameworkDir.isDirectory) {
                        frameworkDir.listFiles()?.forEach { frameworkFile ->
                            if (frameworkFile.extension == "apk") {
                                logger.info("Loading framework: ${frameworkFile.name}")
                                addExternalFramework(frameworkFile)
                            }
                        }
                    }
                }

                val decoder = ApkModuleXmlDecoder(this)
                decoder.dexDecoder = null
                
                try {
                    decoder.decode(config.apkFiles)
                    
                    // Update metadata from decoded manifest
                    androidManifest?.let { manifest ->
                        packageMetadata.packageName = manifest.packageName ?: ""
                        packageMetadata.packageVersion = manifest.versionName ?: manifest.versionCode?.toString() ?: ""
                    }
                } catch (e: Exception) {
                    logger.severe("Failed to decode resources: ${e.message}")
                    throw PatchException("Failed to decode resources", e)
                }
            } else {
                logger.info("Decoding app manifest")

                val decoder = ApkModuleRawDecoder(this)
                decoder.dexDecoder = null
                
                try {
                    decoder.decode(config.apkFiles)
                    
                    // Update metadata from manifest
                    androidManifest?.let { manifest ->
                        packageMetadata.packageName = manifest.packageName ?: ""
                        packageMetadata.packageVersion = manifest.versionName ?: manifest.versionCode?.toString() ?: ""
                    }
                } catch (e: Exception) {
                    logger.severe("Failed to decode manifest: ${e.message}")
                    throw PatchException("Failed to decode manifest", e)
                }
            }
        }

    /**
     * Compile resources in [PatcherConfig.apkFiles].
     *
     * @return The [PatcherResult.PatchedResources].
     */
    @InternalApi
    override fun get(): PatcherResult.PatchedResources? {
        if (config.resourceMode == ResourceMode.NONE) return null

        logger.info("Compiling modified resources")

        val resources = config.patchedFiles.resolve("resources").also { it.mkdirs() }

        val resourcesApkFile =
            if (config.resourceMode == ResourceMode.FULL) {
                resources.resolve("resources.apk").apply {
                    val manifestFile = config.apkFiles.resolve("AndroidManifest.xml")
                    if (!manifestFile.exists()) {
                        throw PatchException("AndroidManifest.xml not found in ${config.apkFiles}")
                    }
                    
                    logger.info("Encoding resources from ${config.apkFiles}")
                    
                    val encoder = ApkModuleXmlEncoder().apply {
                        val encoderModule = this.apkModule
                        
                        packageMetadata.apkModule.let { decoderModule ->
                            encoderModule.setLoadDefaultFramework(true)
                            
                            config.frameworkDirectory?.let { frameworkDir ->
                                if (frameworkDir.exists() && frameworkDir.isDirectory) {
                                    frameworkDir.listFiles()?.forEach { frameworkFile ->
                                        if (frameworkFile.extension == "apk") {
                                            logger.info("Adding framework: ${frameworkFile.name}")
                                            encoderModule.addExternalFramework(frameworkFile)
                                        }
                                    }
                                }
                            }
                        }
                        
                        dexEncoder = null // We don't want to modify the dex files here.
                        scanDirectory(config.apkFiles)
                    }

                    val encodedModule = encoder.apkModule
                    
                    encodedModule.androidManifest?.let { manifest ->
                        logger.info("Encoding AndroidManifest: package=${manifest.packageName}, version=${manifest.versionName ?: manifest.versionCode}")
                        
                        manifest.refresh()
                        
                        if (manifest.packageName.isNullOrEmpty()) {
                            throw PatchException("AndroidManifest.xml has no package name")
                        }
                    } ?: throw PatchException("AndroidManifest not found in encoded module")
                    
                    logger.info("Writing resources.apk")
                    encodedModule.writeApk(this)
                }
            } else {
                null
            }

        val otherFiles =
            config.apkFiles.listFiles()!!.filter {
                // Include non-DEX files that should be in the APK root.
                // DEX files are already handled by BytecodePatchContext and returned as PatchedDexFile objects.
                // AndroidManifest.xml is already included in resources.apk when FULL mode,
                // but we need to include it in otherResourceFiles for RAW_ONLY mode.
                it.isFile && 
                    it.name != "build" &&
                    !it.name.endsWith(".json") &&
                    !it.name.endsWith(".dex") &&  // Exclude DEX files - already handled by BytecodePatchContext
                    // Exclude manifest only in FULL mode (already in resources.apk)
                    !(config.resourceMode == ResourceMode.FULL && it.name == "AndroidManifest.xml") &&
                    // Exclude res directory metadata
                    it.name != "res"
            }

        val otherResourceFiles =
            if (otherFiles.isNotEmpty()) {
                // Move the other resources files.
                resources.resolve("other").also { it.mkdirs() }.apply {
                    otherFiles.forEach { file ->
                        Files.move(
                            file.toPath(),
                            resolve(file.name).toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }
            } else {
                null
            }

        val doNotCompress = mutableSetOf<String>()
        packageMetadata.apkModule.zipEntryMap?.let { entryMap ->
            entryMap.forEach { entry ->
                if (entry.method == 0) { // STORED method means uncompressed
                    doNotCompress.add(entry.name)
                }
            }
        }

        return PatcherResult.PatchedResources(
            resourcesApkFile,
            otherResourceFiles,
            doNotCompress,
            deleteResources,
        )
    }

    /**
     * Get a file from [PatcherConfig.apkFiles].
     *
     * @param path The path of the file.
     * @param copy Whether to copy the file from [PatcherConfig.apkFile] if it does not exist yet in [PatcherConfig.apkFiles].
     */
    operator fun get(
        path: String,
        copy: Boolean = true,
    ): File {
        val directFile = config.apkFiles.resolve(path)
        if (directFile.exists()) {
            return directFile
        }

        val resourcesDir = config.apkFiles.resolve("resources")
        if (resourcesDir.exists() && resourcesDir.isDirectory) {
            val packageDirs = resourcesDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("package_") }
                ?: emptyList()

            val basePackageName = packageMetadata.packageName
            packageDirs.forEach { packageDir ->
                val packageJsonFile = packageDir.resolve("package.json")
                if (packageJsonFile.exists()) {
                    try {
                        val packageJson = packageJsonFile.readText()
                        val packageNameMatch = Regex(""""package_name"\s*:\s*"([^"]+)"""")
                            .find(packageJson)

                        if (packageNameMatch != null && packageNameMatch.groupValues[1] == basePackageName) {
                            val splitApkFile = packageDir.resolve(path)
                            if (splitApkFile.exists()) {
                                logger.fine("Found resource in base package ($basePackageName): ${packageDir.name}/$path")
                                return splitApkFile
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore and continue
                    }
                }
            }

            packageDirs.forEach { packageDir ->
                val splitApkFile = packageDir.resolve(path)
                if (splitApkFile.exists()) {
                    logger.fine("Found resource in Split APK package: ${packageDir.name}/$path")
                    return splitApkFile
                }
            }
        }

        if (!copy) {
            return directFile
        }

        try {
            val apkModule = packageMetadata.apkModule
            val inputSource: InputSource? = apkModule.getInputSource(path)

            if (inputSource != null) {
                directFile.parentFile?.mkdirs()

                inputSource.write(directFile)

                logger.fine("Extracted resource file: $path")
                return directFile
            } else {
                logger.warning("Resource file not found in APK: $path")
                return directFile
            }
        } catch (e: Exception) {
            logger.warning("Failed to extract file '$path': ${e.message}")
            throw PatchException("Failed to extract resource file '$path' from APK: ${e.message}", e)
        }
    }

    /**
     * Get a file from [PatcherConfig.apkFiles] from a specific package.
     * Uses package.json metadata to find the correct Split APK package.
     *
     * @param path The path of the file.
     * @param packageName The package name to search for (e.g., "com.kakao.talk").
     * @param copy Whether to copy the file from [PatcherConfig.apkFile] if it does not exist yet.
     * @return The file from the specified package.
     * @throws PatchException if the package is not found.
     */
    fun get(
        path: String,
        packageName: String,
        copy: Boolean = true,
    ): java.io.File {
        // Search for the package by reading package.json files
        val resourcesDir = config.apkFiles.resolve("resources")
        if (resourcesDir.exists() && resourcesDir.isDirectory) {
            resourcesDir.listFiles()?.forEach { packageDir ->
                if (packageDir.isDirectory && packageDir.name.startsWith("package_")) {
                    // Read package.json to check package_name
                    val packageJsonFile = packageDir.resolve("package.json")
                    if (packageJsonFile.exists()) {
                        try {
                            val packageJson = packageJsonFile.readText()
                            // Simple JSON parsing to extract package_name
                            val packageNameMatch = Regex(""""package_name"\s*:\s*"([^"]+)"""")
                                .find(packageJson)

                            if (packageNameMatch != null && packageNameMatch.groupValues[1] == packageName) {
                                val targetFile = packageDir.resolve(path)
                                if (targetFile.exists()) {
                                    logger.fine("Found resource in package '$packageName': ${packageDir.name}/$path")
                                    return targetFile
                                } else if (copy) {
                                    // Try to extract from APK
                                    try {
                                        val apkModule = packageMetadata.apkModule
                                        val inputSource: InputSource? = apkModule.getInputSource(path)

                                        if (inputSource != null) {
                                            targetFile.parentFile?.mkdirs()
                                            inputSource.write(targetFile)
                                            logger.fine("Extracted resource file for package '$packageName': $path")
                                            return targetFile
                                        }
                                    } catch (e: Exception) {
                                        logger.warning("Failed to extract file '$path' for package '$packageName': ${e.message}")
                                    }
                                }
                                // File not found in this package, but package matched
                                throw PatchException("Resource '$path' not found in package '$packageName'")
                            }
                        } catch (e: Exception) {
                            logger.warning("Failed to read package.json in ${packageDir.name}: ${e.message}")
                        }
                    }
                }
            }
        }

        // Package not found
        throw PatchException("Split APK package with name '$packageName' not found. Available packages: ${getAvailablePackages().joinToString(", ")}")
    }

    /**
     * Get list of available package names from Split APK structure.
     * @return List of package names found in package.json files.
     */
    private fun getAvailablePackages(): List<String> {
        val packages = mutableListOf<String>()
        val resourcesDir = config.apkFiles.resolve("resources")

        if (resourcesDir.exists() && resourcesDir.isDirectory) {
            resourcesDir.listFiles()?.forEach { packageDir ->
                if (packageDir.isDirectory && packageDir.name.startsWith("package_")) {
                    val packageJsonFile = packageDir.resolve("package.json")
                    if (packageJsonFile.exists()) {
                        try {
                            val packageJson = packageJsonFile.readText()
                            val packageNameMatch = Regex(""""package_name"\s*:\s*"([^"]+)"""")
                                .find(packageJson)
                            packageNameMatch?.groupValues?.get(1)?.let { packages.add(it) }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
        }

        return packages
    }

    /**
     * Mark a file for deletion when the APK is rebuilt.
     *
     * @param name The name of the file to delete.
     */
    fun delete(name: String) = deleteResources.add(name)

    /**
     * How to handle resources decoding and compiling.
     */
    internal enum class ResourceMode {
        /**
         * Decode and compile all resources.
         */
        FULL,

        /**
         * Only extract resources from the APK.
         * The AndroidManifest.xml and resources inside /res are not decoded or compiled.
         */
        RAW_ONLY,

        /**
         * Do not decode or compile any resources.
         */
        NONE,
    }
}

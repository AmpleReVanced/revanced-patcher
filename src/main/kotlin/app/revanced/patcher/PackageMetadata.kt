package app.revanced.patcher

import com.reandroid.apk.ApkModule

/**
 * Metadata about a package.
 *
 * @param apkModule The [ApkModule] of the apk file.
 */
class PackageMetadata internal constructor(internal val apkModule: ApkModule) {
    lateinit var packageName: String
        internal set

    lateinit var packageVersion: String
        internal set
}

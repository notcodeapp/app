package com.notcode.mobile.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val DEFAULT_RELEASE_REPOSITORY = "NotCodeApp/App"

data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
)

data class GithubRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

data class UpdateTarget(val release: GithubRelease, val asset: ReleaseAsset)

/**
 * Pure GitHub Releases parsing and version comparison so the update decision is testable
 * without a device.
 */
object GithubReleases {
    const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_NOTE_CHARS = 4_000

    private const val SEGMENT_LIMIT = 1_000_000L
    private val REPOSITORY = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
    private val VERSION = Regex("v?(\\d+(?:\\.\\d+){0,3})")

    /** Accepts `owner/repo`, a repository URL or a releases URL and returns `owner/repo`. */
    fun normalizeRepository(raw: String?): String? {
        val trimmed = raw?.trim()?.removePrefix("https://")?.removePrefix("http://")
            ?.removePrefix("www.github.com/")?.removePrefix("github.com/")?.trim('/') ?: return null
        val path = trimmed.removeSuffix(".git").removeSuffix("/releases").trim('/')
        val segments = path.split('/')
        if (segments.size != 2) return null
        return path.takeIf { REPOSITORY.matches(it) }
    }

    fun releasesUrl(repository: String): String = "https://api.github.com/repos/$repository/releases"

    fun parse(root: JsonElement): List<GithubRelease> {
        val items = root as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val release = element as? JsonObject ?: return@mapNotNull null
            val tag = release.text("tag_name") ?: return@mapNotNull null
            if (release.flag("draft")) return@mapNotNull null
            GithubRelease(
                tagName = tag,
                title = release.text("name") ?: tag,
                notes = (release.text("body") ?: "").take(MAX_NOTE_CHARS),
                prerelease = release.flag("prerelease"),
                assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { assetElement ->
                    val asset = assetElement as? JsonObject ?: return@mapNotNull null
                    val name = asset.text("name") ?: return@mapNotNull null
                    val url = asset.text("browser_download_url") ?: return@mapNotNull null
                    if (!name.lowercase().endsWith(".apk") || !url.startsWith("https://")) return@mapNotNull null
                    val size = (asset["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: -1L
                    ReleaseAsset(name, size, url)
                },
            )
        }
    }

    fun versionOf(tag: String): String? = VERSION.find(tag.trim())?.groupValues?.get(1)

    /** The installed version may carry a suffix such as `-debug`; only the numeric part counts. */
    fun installedVersion(raw: String): String = versionOf(raw) ?: raw.trim().removePrefix("v")

    /** Compares dotted numeric versions; missing trailing segments count as zero. */
    fun compare(first: String, second: String): Int {
        val left = segments(first)
        val right = segments(second)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val value = left.getOrElse(index) { 0L }.compareTo(right.getOrElse(index) { 0L })
            if (value != 0) return value
        }
        return 0
    }

    /**
     * Picks the release newer than the running build that ships an installable APK.
     * Stable releases outrank pre-releases even when a pre-release has a higher version.
     */
    fun pickApk(releases: List<GithubRelease>, installed: String): UpdateTarget? {
        val current = installedVersion(installed)
        val candidates = releases.mapNotNull { release ->
            val version = versionOf(release.tagName) ?: return@mapNotNull null
            val asset = preferredApk(release) ?: return@mapNotNull null
            if (compare(version, current) <= 0) return@mapNotNull null
            Triple(if (release.prerelease) 0L else 1L, orderKey(version), UpdateTarget(release, asset))
        }
        return candidates.maxWithOrNull(compareBy({ it.first }, { it.second }))?.third
    }

    /** Per-ABI builds share one release; the universal APK installs on every device. */
    fun preferredApk(release: GithubRelease): ReleaseAsset? =
        release.assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: release.assets.firstOrNull()

    private fun orderKey(version: String): Long =
        segments(version).take(3).fold(0L) { accumulator, part ->
            accumulator * SEGMENT_LIMIT + part.coerceIn(0L, SEGMENT_LIMIT - 1)
        }

    private fun segments(version: String): List<Long> =
        (versionOf(version) ?: version).split('.').map { it.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
}

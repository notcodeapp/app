package com.notcode.mobile.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleaseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun asset(name: String, url: String?, size: Long) = buildJsonObject {
        put("name", name)
        put("size", size)
        if (url != null) put("browser_download_url", url)
    }

    private fun release(
        tag: String,
        assets: List<JsonObject>,
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "",
    ) = buildJsonObject {
        put("tag_name", tag)
        put("name", "Release $tag")
        put("body", body)
        put("draft", draft)
        put("prerelease", prerelease)
        putJsonArray("assets") { assets.forEach { add(it) } }
    }

    private fun apk(tag: String, name: String = "notcode.apk", https: Boolean = true, size: Long = 70_000_000L) =
        asset(
            name,
            "${if (https) "https" else "http"}://github.com/NotCodeApp/App/releases/download/$tag/$name",
            size,
        )

    private fun parse(vararg items: JsonObject) =
        GithubReleases.parse(json.parseToJsonElement(items.joinToString(",", "[", "]")))

    @Test fun acceptsRepositoryUrlAndPlainPath() {
        assertEquals("NotCodeApp/App", GithubReleases.normalizeRepository("NotCodeApp/App"))
        assertEquals("notcodeapp/app", GithubReleases.normalizeRepository("https://github.com/notcodeapp/app"))
        assertEquals("NotCodeApp/App", GithubReleases.normalizeRepository("https://github.com/NotCodeApp/App/releases"))
        assertEquals("NotCodeApp/App", GithubReleases.normalizeRepository("github.com/NotCodeApp/App.git"))
        assertNull(GithubReleases.normalizeRepository("NotCodeApp"))
        assertNull(GithubReleases.normalizeRepository("https://evil.example/owner/repo/extra"))
        assertNull(GithubReleases.normalizeRepository("   "))
        assertNull(GithubReleases.normalizeRepository(null))
    }

    @Test fun releasesUrlUsesHttpsApi() {
        assertEquals(
            "https://api.github.com/repos/NotCodeApp/App/releases",
            GithubReleases.releasesUrl(GithubReleases.normalizeRepository("https://github.com/NotCodeApp/App")!!),
        )
    }

    @Test fun draftsAndApkLessReleasesAreIgnored() {
        val releases = parse(
            release("v2.0.0", listOf(apk("v2.0.0")), draft = true),
            release("v1.9.0", emptyList()),
            release("v1.5.0", listOf(apk("v1.5.0"))),
        )
        assertEquals(listOf("v1.9.0", "v1.5.0"), releases.map { it.tagName })
        assertEquals("v1.5.0", GithubReleases.pickApk(releases, "1.1.0")?.release?.tagName)
    }

    @Test fun insecureAndNonApkAssetsAreSkipped() {
        val releases = parse(
            release(
                "v3.0.0",
                listOf(apk("v3.0.0", https = false), asset("notes.txt", "https://github.com/a/b.git", 10L)),
            ),
        )
        assertTrue(releases.first().assets.isEmpty())
        assertNull(GithubReleases.pickApk(releases, "1.1.0"))
    }

    @Test fun newestReleaseWinsAndStableBeatsPrerelease() {
        val releases = parse(
            release("v1.2.0", listOf(apk("v1.2.0"))),
            release("v1.3.0-rc1", listOf(apk("v1.3.0-rc1")), prerelease = true),
            release("v1.1.0", listOf(apk("v1.1.0"))),
        )
        assertEquals("v1.2.0", GithubReleases.pickApk(releases, "1.1.0")?.release?.tagName)
        val onlyPrerelease = parse(release("v1.3.0-rc1", listOf(apk("v1.3.0-rc1")), prerelease = true))
        assertEquals("v1.3.0-rc1", GithubReleases.pickApk(onlyPrerelease, "1.2.0")?.release?.tagName)
    }

    @Test fun installedBuildIsNeverDowngraded() {
        val releases = parse(release("v1.1.0", listOf(apk("v1.1.0"))), release("v1.0.9", listOf(apk("v1.0.9"))))
        assertNull(GithubReleases.pickApk(releases, "1.2.0-debug"))
        assertNull(GithubReleases.pickApk(releases, "1.1.0-beta"))
        assertEquals("v1.1.0", GithubReleases.pickApk(releases, "1.0.9-alpha")?.release?.tagName)
    }

    @Test fun versionComparisonHandlesSegmentCount() {
        assertEquals(0, GithubReleases.compare("1.2", "1.2.0"))
        assertEquals(1, GithubReleases.compare("1.10.0", "1.9.9"))
        assertEquals(-1, GithubReleases.compare("v2", "2.1"))
        assertEquals("1.2.3", GithubReleases.versionOf("release-v1.2.3-final"))
        assertNull(GithubReleases.versionOf("latest"))
        assertEquals("1.2.0", GithubReleases.installedVersion("1.2.0-debug"))
    }

    @Test fun assetSizeAndNotesAreCarried() {
        val target = GithubReleases.pickApk(
            parse(release("v9.9.9", listOf(apk("v9.9.9", size = 12345678L)), body = "исправлены ретраи")),
            "1.1.0",
        )
        assertNotNull(target)
        assertEquals(12345678L, target!!.asset.size)
        assertEquals("notcode.apk", target.asset.name)
        assertEquals("исправлены ретраи", target.release.notes)
    }
}

package app.obsibible

import app.obsibible.update.SelfUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun comparesVersions() {
        assertTrue(SelfUpdate.newer("1.1.0", "1.0.1"))
        assertTrue(SelfUpdate.newer("v1.10.0", "1.9.9"))
        assertFalse(SelfUpdate.newer("1.0.1", "1.0.1"))
        assertFalse(SelfUpdate.newer("1.0", "1.0.0"))
    }

    @Test
    fun picksTheApkForThisCpu() {
        val names = listOf("app-armeabi-v7a.apk", "app-arm64-v8a.apk", "app-universal.apk")
        assertEquals("app-arm64-v8a.apk", SelfUpdate.pickAsset(names, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("ObsiBible.apk", SelfUpdate.pickAsset(listOf("ObsiBible.apk", "app-x86.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun readsAGithubRelease() {
        val o = obj(
            """{"tag_name":"v1.2.0","body":"Notes","assets":[
            {"name":"notes.txt","url":"api/0","browser_download_url":"web/0"},
            {"name":"ObsiBible-1.2.0.apk","url":"api/1","browser_download_url":"web/1","size":123}]}""",
        )
        val open = SelfUpdate.parse(o, hasToken = false, abis = listOf("arm64-v8a"))!!
        assertEquals("1.2.0", open.version)
        assertEquals("web/1", open.url)
        assertFalse(open.api)
        val priv = SelfUpdate.parse(o, hasToken = true, abis = listOf("arm64-v8a"))!!
        assertEquals("api/1", priv.url)
        assertTrue(priv.api)
        assertEquals(123L, priv.size)
    }

    @Test
    fun readsAMirror() {
        val i = SelfUpdate.parse(obj("""{"version":"2.0.0","notes":"n","url":"https://x/y.apk","size":9}"""), false, emptyList())!!
        assertEquals("2.0.0", i.version)
        assertEquals("https://x/y.apk", i.url)
    }
}

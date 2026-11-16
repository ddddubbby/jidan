package com.jidan.receiving.ui.update

import com.google.gson.Gson
import com.jidan.receiving.data.ApiError
import com.jidan.receiving.data.AppVersion
import com.jidan.receiving.ui.create.CreateUiState
import com.jidan.receiving.ui.login.LoginUiState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UpdateOfferTest {
    private val url = "http://x/apk/a.apk"

    @Test
    fun gate() {
        assertNull(offerFor(1, null))
        assertNull(offerFor(2, AppVersion(2, "1.0.1", url)))
        assertNull(offerFor(3, AppVersion(2, "1.0.1", url)))
        assertNull(offerFor(1, AppVersion(2, "1.0.1", null)))
        assertNull(offerFor(1, AppVersion(2, "1.0.1", "")))
        assertEquals(UpdateOffer(url, "有新版本 1.0.1，点此下载"), offerFor(1, AppVersion(2, "1.0.1", url)))
        assertEquals("有新版本 2，点此下载", offerFor(1, AppVersion(2, null, url))?.label)
    }

    private fun createState(local: Int, fetch: suspend () -> AppVersion): CreateUiState =
        CreateUiState().copy(update = runBlocking { fetchUpdateOffer(local, fetch) })

    @Test
    fun createStateHasUrlAndLabelWhenRemoteNewer() {
        val s = createState(1) { AppVersion(2, "1.0.1", url) }
        assertEquals(url, s.update?.url)
        assertEquals("有新版本 1.0.1，点此下载", s.update?.label)
    }

    @Test
    fun createStateHasNothingWhenEqualOlderOr404() {
        assertNull(createState(2) { AppVersion(2, "1.0.1", url) }.update)
        assertNull(createState(3) { AppVersion(2, "1.0.1", url) }.update)
        assertNull(createState(1) { throw ApiError(404, "NOT_FOUND", "没有这个接口") }.update)
        assertNull(createState(1) { throw java.io.IOException("连不上") }.update)
    }

    @Test
    fun loginStateSharesTheSameJudgement() {
        val offer = runBlocking { fetchUpdateOffer(1) { AppVersion(2, "1.0.1", url) } }
        assertNotNull(LoginUiState().copy(update = offer).update)
        assertNull(LoginUiState().update)
    }

    /** `deploy/publish-apk.sh` 写的 version.json，经 nginx 换掉 __BASE__ 后的样子。 */
    @Test
    fun parsesPublishedVersionJson() {
        val json = """{"versionCode":2,"versionName":"1.0.1","downloadUrl":"http://192.168.1.5:8080/apk/jidan-1.0.1.apk"}"""
        val v = Gson().fromJson(json, AppVersion::class.java)
        assertEquals(2, v.versionCode)
        assertEquals("1.0.1", v.versionName)
        assertEquals("http://192.168.1.5:8080/apk/jidan-1.0.1.apk", offerFor(1, v)?.url)
    }
}

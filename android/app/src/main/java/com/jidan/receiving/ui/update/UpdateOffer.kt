package com.jidan.receiving.ui.update

import com.jidan.receiving.data.AppVersion
import kotlinx.coroutines.CancellationException

/**
 * 新版本下载入口的判断，登录页与建单页共用（开发规格 2.7、9.4）。
 * APP 不走应用商店，这是唯一的更新渠道；长期登录的手机不会回到登录页，所以建单页也要查。
 */
data class UpdateOffer(val url: String, val label: String)

/** 远端 versionCode 大于本机且给了下载地址才出入口；其余一律没有。 */
fun offerFor(localVersionCode: Int, remote: AppVersion?): UpdateOffer? {
    if (remote == null) return null
    val url = remote.downloadUrl
    if (url.isNullOrBlank()) return null
    if (remote.versionCode <= localVersionCode) return null
    return UpdateOffer(url, "有新版本 ${remote.versionName ?: remote.versionCode}，点此下载")
}

/** 查一次 `GET /api/app/version`；接口不存在、连不上、解析失败都当没有更新。 */
suspend fun fetchUpdateOffer(localVersionCode: Int, fetch: suspend () -> AppVersion): UpdateOffer? =
    try {
        offerFor(localVersionCode, fetch())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

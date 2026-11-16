package com.jidan.receiving.ui.login

/** 登录表单纯逻辑，便于单测（对照 lab/app-sim.html `loginBtn` / `loginInput`）。 */

enum class LoginStep {
    SERVER,
    PHONE,
    PASSWORD,
    READY,
    BUSY,
}

fun digitsPhone(raw: String): String = raw.filter(Char::isDigit).take(11)

/** 原型：`138 0013 8000` 分段显示。 */
fun formatPhoneDisplay(digits: String): String {
    val d = digitsPhone(digits)
    return buildString {
        append(d.take(3))
        if (d.length > 3) {
            append(' ')
            append(d.drop(3).take(4))
        }
        if (d.length > 7) {
            append(' ')
            append(d.drop(7).take(4))
        }
    }
}

fun loginStep(serverUrl: String, phone: String, password: String, busy: Boolean): LoginStep = when {
    busy -> LoginStep.BUSY
    serverUrl.trim().isEmpty() -> LoginStep.SERVER
    digitsPhone(phone).length < 11 -> LoginStep.PHONE
    password.isEmpty() -> LoginStep.PASSWORD
    else -> LoginStep.READY
}

/** 有更新时才显示下载入口：远端 versionCode 大于本机。 */
fun shouldShowUpdate(localVersionCode: Int, remote: AppVersionLike?): Boolean {
    if (remote == null) return false
    if (remote.downloadUrl.isNullOrBlank()) return false
    return remote.versionCode > localVersionCode
}

data class AppVersionLike(val versionCode: Int, val downloadUrl: String?)

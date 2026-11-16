package com.jidan.receiving.data

class ApiError(
    val status: Int,
    val code: String,
    override val message: String,
) : Exception(message)

fun Throwable.userMessage(): String = when (this) {
    is ApiError -> message
    else -> "连不上服务器，请检查网络后重试"
}

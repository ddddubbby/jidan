package com.jidan.receiving.data

import android.content.Context
import com.jidan.receiving.core.Session
import java.io.File

/**
 * 本地草稿（开发规格 9.4）：Session 每次变化写入 JSON；进程回收后恢复。
 * 不是离线功能——提交成功或放弃整单后删除。
 */
class SessionStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    fun save(session: Session) {
        file.writeText(session.toJson())
    }

    fun load(): Session? {
        if (!file.exists()) return null
        return try {
            Session.fromJson(file.readText())
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    fun clear() {
        file.delete()
    }

    fun exists(): Boolean = file.exists()

    companion object {
        private const val FILE_NAME = "receiving_draft.json"
    }
}

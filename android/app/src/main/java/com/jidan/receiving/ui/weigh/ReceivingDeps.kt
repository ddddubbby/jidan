package com.jidan.receiving.ui.weigh

import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.BootstrapResponse
import com.jidan.receiving.data.CatalogRepository
import com.jidan.receiving.data.OrderDraftStore
import com.jidan.receiving.data.OrderRepository
import com.jidan.receiving.data.SessionStore
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.Scale
import kotlinx.coroutines.flow.StateFlow

/**
 * [ReceivingViewModel] 用到的外部能力。[com.jidan.receiving.AppContainer] 实现它；
 * JVM 测试里用真的仓库（接 MockWebServer）、真的 Fake 设备、临时目录里的草稿拼一份，不需要 Android 环境。
 * 不是新的抽象层：只列出 ViewModel 已经在用的这些成员。
 */
interface ReceivingDeps {
    val catalog: CatalogRepository
    val orders: OrderRepository
    val orderDraft: OrderDraftStore
    val sessionStore: SessionStore
    val options: StateFlow<AppOptions>
    val scale: Scale
    val printer: LabelPrinter

    /** 上一单用的热键皮重 id（Prefs 里的 `lastTareId`）。 */
    var lastTareId: Long?

    /** 提交时登录失效：先登录，登录后回到汇总页。 */
    var resumeSummaryAfterLogin: Boolean

    /** 取 `GET /app/bootstrap` 并让新选项生效；失败抛出。 */
    suspend fun fetchBootstrap(): BootstrapResponse
}

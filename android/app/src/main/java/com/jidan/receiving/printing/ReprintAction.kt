package com.jidan.receiving.printing

/**
 * 补打按钮此刻该做什么。只看打印机当前的状态，不看上一次失败留下的文字：
 * 缺纸的提示在换好纸之后就该过去，按钮立刻变回「补打」。
 */
sealed interface ReprintAction {
    /** 打印机能打：点了直接补打。 */
    data object Print : ReprintAction

    /** 打印机此刻有问题（[reason] 如“缺纸”“打印机未连接”）：点了去设备弹层处理。 */
    data class FixPrinter(val reason: String) : ReprintAction
}

/** 不用打印，或没有未打印的箱：返回 null，按钮不出现。 */
fun reprintAction(printEnabled: Boolean, unprinted: Int, printBlock: String): ReprintAction? = when {
    !printEnabled || unprinted <= 0 -> null
    printBlock.isNotBlank() -> ReprintAction.FixPrinter(printBlock)
    else -> ReprintAction.Print
}

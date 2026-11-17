package com.jidan.receiving.screens

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * 渲染截图的工具（F4-1）：推进 Robolectric 的虚拟时间、等回放接口、把页面与弹层画成 PNG、扫孤字行。
 * 渲染的是 Robolectric 原生图形（L1），不是真机；字体与真机可能略有差别。
 */
class ScreenHarness(
    private val activity: () -> Activity,
    val config: String,
    private val outDir: File,
    private val only: Set<String>,
) {
    val orphans = mutableListOf<OrphanHit>()
    val rendered = mutableListOf<String>()
    val unreachable = mutableListOf<String>()
    private var broken = false

    init {
        outDir.mkdirs()
        // 上一轮的旧图不留：这一轮没渲染到的状态不该还有图
        outDir.listFiles { f -> f.extension == "png" }?.forEach { it.delete() }
    }

    private val looper get() = shadowOf(Looper.getMainLooper())

    /** 推进虚拟时间 [ms] 毫秒（FakeScale 的延时、动画、协程 delay 都跟着走），每 20ms 让出一点真时间给接口回放的线程。 */
    fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(20L, left)
            looper.idleFor(Duration.ofMillis(step))
            Thread.sleep(1)
            left -= step
        }
    }

    /** 等条件成立：虚拟时间每次走 10ms，真时间最多等 [timeoutMs]。等不到就带着说明失败。 */
    fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            looper.idleFor(Duration.ofMillis(10))
            if (cond()) return
            check(System.nanoTime() < deadline) { "等不到：$what" }
            Thread.sleep(5)
        }
    }

    /** 一段操作：前面有一步失败后，后面依赖它的不再试，记成“走不到”。 */
    fun run(what: String, body: () -> Unit) {
        if (broken) return
        try {
            body()
        } catch (e: Throwable) {
            broken = true
            unreachable += "$what：${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}"
        }
    }

    /** 做一步操作，然后渲染 [name]。 */
    fun step(name: String, body: () -> Unit) {
        if (broken) {
            unreachable += "$name：前面的状态没走到"
            return
        }
        try {
            body()
            shot(name)
        } catch (e: Throwable) {
            broken = true
            unreachable += "$name：${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}"
        }
    }

    /** 找文字时不计显示用的不换行空格和零宽连接符（F4-3，数字与单位不拆行用的），按原型的写法匹配。 */
    private fun plain(t: CharSequence): String = t.toString().replace("\u2060", "").replace('\u00A0', ' ')

    /** 只在弹层里找。 */
    fun hasDialogText(contains: String): Boolean =
        dialogs().any { d -> textViews(d.window!!.decorView).any { it.isShown && plain(it.text).contains(contains) } }

    fun hasText(contains: String): Boolean = runCatching { findText(contains) }.isSuccess

    /** 当前所有在显示的弹层窗口（底部弹层、AlertDialog），先出现的在前。 */
    fun dialogs(): List<Dialog> = ShadowDialog.getShownDialogs().filter { it.isShowing }

    /** 找某个文字所在的、可见的 TextView（按钮也是 TextView），在页面与弹层里都找。 */
    fun findText(contains: String): TextView {
        val roots = dialogs().map { it.window!!.decorView } + activity().window.decorView
        for (root in roots) {
            val hit = textViews(root).firstOrNull { it.isShown && plain(it.text).contains(contains) }
            if (hit != null) return hit
        }
        error("找不到文字「$contains」")
    }

    /** 关掉所有弹层（底部弹层、对话框），回到页面。 */
    fun dismissDialogs() {
        dialogs().forEach { it.dismiss() }
        advance(450)
    }

    fun clickId(id: Int) {
        val v = activity().findViewById<View>(id)
        check(v != null && v.isShown && v.isEnabled) { "控件 ${activity().resources.getResourceEntryName(id)} 点不了" }
        v.performClick()
        advance(50)
    }

    fun click(contains: String) {
        val v = findText(contains)
        check(v.isEnabled) { "「$contains」点不了（未启用）" }
        v.performClick()
        advance(50)
    }

    private fun textViews(root: View): Sequence<TextView> = sequence {
        if (root is TextView) yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(textViews(root.getChildAt(i)))
    }

    /** 渲染当前屏幕（页面加弹层）存成 `<name>.png`，并扫孤字行。[name] 形如 `06-create-ready`。 */
    fun shot(name: String) {
        advance(450) // 弹层滑入动画、按钮状态变化都要走完
        if (only.isNotEmpty() && only.none { name.startsWith(it) }) {
            scanOrphans(name)
            return
        }
        val act = activity()
        val dm = act.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        drawWindow(canvas, act.window.decorView, w, h)
        for (d in dialogs()) {
            val win = d.window ?: continue
            val dim = if (win.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) win.attributes.dimAmount else 0f
            if (dim > 0f) {
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb((dim * 255).toInt(), 0, 0, 0) })
            }
            drawWindow(canvas, win.decorView, w, h)
        }
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rendered += name
        scanOrphans(name)
    }

    private fun drawWindow(canvas: Canvas, decor: View, screenW: Int, screenH: Int) {
        if (decor.width == 0 || decor.height == 0) {
            // 弹层窗口还没排版过：按屏幕大小排一次（底部弹层是铺满屏幕的窗口）
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST),
            )
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(loc[0].toFloat(), loc[1].toFloat())
        decor.draw(canvas)
        canvas.restore()
    }

    /**
     * 收尾：写本套尺寸的 orphans.tsv / unreachable.txt，并把已跑完的各套合并成 `<root>/orphans.tsv`；
     * 有没放行的孤字行、或有状态走不到，就失败。（各套各自收尾，不靠静态变量，Robolectric 每套参数的类加载是分开的。）
     */
    fun finish(root: File, allow: OrphanAllowList) {
        val bad = orphans.filterNot(allow::allows)
        File(outDir, "orphans.tsv").writeText(
            (listOf(OrphanHit.HEADER + "\tallowed") +
                orphans.map { it.tsv() + "\t" + (if (it in bad) "no" else "yes") }).joinToString("\n") + "\n",
        )
        File(outDir, "unreachable.txt").writeText(unreachable.joinToString("") { "$config\t$it\n" })
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        val merged = mutableListOf(OrphanHit.HEADER + "\tallowed")
        for (d in dirs) File(d, "orphans.tsv").takeIf { it.exists() }?.readLines()?.drop(1)?.filter { it.isNotBlank() }?.let { merged += it }
        File(root, "orphans.tsv").writeText(merged.joinToString("\n") + "\n")
        println("screens $config: 孤字行 ${orphans.size} 处（未放行 ${bad.size}），走不到的状态 ${unreachable.size} 个")
        check(unreachable.isEmpty()) { "有状态走不到（$config）：\n" + unreachable.joinToString("\n") }
        check(bad.isEmpty()) {
            "孤字行 ${bad.size} 处（$config，详见 app/build/screens/orphans.tsv）：\n" +
                bad.joinToString("\n") { "  ${it.screen} ${it.view}：最后一行「${it.lastLine}」 ← ${it.text.replace('\n', ' ')}" }
        }
    }

    /** 扫页面与全部弹层里可见的 TextView：多行、最后一行只剩一个字（或一个字加标点）的记下来。 */
    private fun scanOrphans(screen: String) {
        val roots = listOf(activity().window.decorView) + dialogs().mapNotNull { it.window?.decorView }
        for (root in roots) {
            for (tv in textViews(root)) {
                if (!tv.isShown || tv.width <= 0) continue
                val layout = tv.layout ?: continue
                val lines = layout.lineCount
                if (lines < 2) continue
                val text = layout.text
                val last = text.subSequence(layout.getLineStart(lines - 1), layout.getLineEnd(lines - 1)).toString()
                if (!OrphanLine.isOrphan(last)) continue
                val id = tv.id
                val viewName = if (id == View.NO_ID) tv.javaClass.simpleName else
                    runCatching { tv.resources.getResourceEntryName(id) }.getOrDefault(tv.javaClass.simpleName)
                orphans += OrphanHit(config, screen, viewName, text.toString(), OrphanLine.trimmed(last), lines)
            }
        }
    }
}

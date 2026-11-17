package com.jidan.receiving.screens

import android.widget.EditText
import com.jidan.receiving.DeviceFactory
import com.jidan.receiving.MainActivity
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.device.fake.FakeScale
import java.io.File
import java.util.Locale
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 渲染截图（F4-1）：真跑 MainActivity（模拟版），后端用 MockWebServer 回放，秤和打印机是 FakeScale / FakePrinter，
 * 走一遍登录 → 建单 → 称重 → 汇总 → 完成，每个状态存一张 PNG。
 * 只由 `./gradlew :app:screens` 运行；默认的单测验收不跑。渲染在 Robolectric 原生图形（L1）下，不是真机，字体可能略有差别。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreensTest(private val widthDp: Int, private val fontScale: Float) {
    private lateinit var server: MockWebServer
    private lateinit var controller: ActivityController<MainActivity>
    private var h: ScreenHarness? = null

    private val config = "$widthDp-${String.format(Locale.ROOT, "%.1f", fontScale)}"

    @Before
    fun setUp() {
        // 只在 :app:screens 里跑（它会设 screens.run）；默认的单测验收即使扫到这个类也跳过
        Assume.assumeTrue(RUN)
        server = MockWebServer().apply { dispatcher = ScreenFixtures.dispatcher; start() }
        RuntimeEnvironment.setQualifiers("+w${widthDp}dp-h${HEIGHT_DP}dp-port-xxhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        h = ScreenHarness({ controller.get() }, config, File(OUT_ROOT, config), ONLY)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun allowList(): OrphanAllowList =
        File("screens-orphan-allow.tsv").takeIf { it.exists() }?.let { OrphanAllowList.parse(it.readText()) }
            ?: OrphanAllowList.EMPTY

    private fun text(id: Int) = controller.get().findViewById<EditText>(id)

    @Test
    fun renderAllScreens() {
        val h = h!!
        val act = { controller.get() }

        // 登录
        h.step("01-login-empty") { }
        h.step("02-login-filled") {
            val url = server.url("/").toString()
            act().appContainer().prefs.serverUrl = url
            text(R.id.serverUrl).setText(url)
            text(R.id.phone).setText("13900001111")
            text(R.id.password).setText("egg123")
            h.advance(100)
        }
        h.run("登录") {
            h.clickId(R.id.action)
            h.waitFor("进入建单页") { act().findViewById<android.view.View>(R.id.tail)?.isShown == true }
            h.waitFor("建单页数据") { h.hasText("最近") }
            h.advance(1500) // 设备卡片上秤与打印机连上
        }

        // 建单
        h.step("03-create-empty") { }
        h.step("04-create-1234") {
            text(R.id.tail).setText("1234")
            h.waitFor("尾号 1234 的农户") { h.hasText("王秀英") }
        }
        h.step("05-create-lisi") {
            h.click("李四")
            h.waitFor("李四的规则带出商品与规格") { h.hasText("粉蛋李四收购定价方案") }
        }
        h.step("06-create-ready") {
            h.click("1号仓库")
            h.waitFor("四步齐了") { h.hasText("开始收蛋") }
        }

        // 称重
        h.run("开始收蛋") {
            h.click("开始收蛋")
            h.waitFor("进入称重页") { h.hasText("请选择皮重") }
            h.advance(1500)
        }
        h.step("07-weigh-tare") { }
        val scale = DeviceFactory.fakeScale
        h.run("选皮重") {
            h.click("2号包装箱")
            h.waitFor("称重框解锁") { !h.hasText("请选择皮重") }
            h.advance(600)
        }
        h.step("08-weigh-settling") {
            scale.placeOneBox(FakeScale.CASE_NETS_C[0])
            h.advance(300)
        }
        h.step("09-weigh-recorded") { h.advance(1800) }
        h.step("10-weigh-10boxes") {
            for (i in 1 until FakeScale.CASE_NETS_C.size) {
                scale.removeLoad()
                h.advance(1800)
                scale.placeOneBox(FakeScale.CASE_NETS_C[i])
                h.advance(1800)
            }
            h.waitFor("记满 10 箱") { h.hasText("已称 10 箱") }
        }

        // 弹层
        h.step("11-ledger-sheet") { h.click("全部") }
        h.step("12-box-sheet") {
            h.dismissDialogs()
            h.clickExact("第 10 箱")
        }
        h.step("13-box-manual") { h.click("手工录入") }
        h.step("14-devices-sheet") {
            h.dismissDialogs()
            h.clickId(R.id.chipScale)
        }
        h.step("15-leave-dialog") {
            h.dismissDialogs()
            h.clickId(R.id.back)
            h.waitFor("放弃提示") { h.hasText("放弃这一单？") }
        }

        // 汇总、规则、完成
        h.step("16-summary") {
            h.click("继续称")
            h.dismissDialogs()
            h.click("确认计量完毕")
            h.waitFor("汇总页出金额") { h.hasText("提交单据") }
        }
        h.step("17-rule-sheet") {
            h.clickId(R.id.factRule)
            h.waitFor("规则弹层读完") { h.hasDialogText("尾差") }
        }
        h.step("18-done") {
            h.dismissDialogs()
            h.click("提交单据")
            h.waitFor("完成页") { h.hasText("再收一单") }
        }

        h.finish(OUT_ROOT, allowList())
    }

    companion object {
        val RUN: Boolean = System.getProperty("screens.run") != null
        val HEIGHT_DP: Int = System.getProperty("screens.height")?.toIntOrNull() ?: 852
        val OUT_ROOT = File("build/screens")
        val ONLY: Set<String> = System.getProperty("screens.only").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun matrix(): Collection<Array<Any>> {
            val spec = System.getProperty("screens.matrix")
                ?: if (System.getProperty("screens.full") != null) {
                    "360:1.0,360:1.3,393:1.0,393:1.3,411:1.0,411:1.3"
                } else {
                    "393:1.0"
                }
            return spec.split(',').filter { it.isNotBlank() }.map {
                val (w, f) = it.trim().split(':')
                arrayOf<Any>(w.toInt(), f.toFloat())
            }
        }
    }
}

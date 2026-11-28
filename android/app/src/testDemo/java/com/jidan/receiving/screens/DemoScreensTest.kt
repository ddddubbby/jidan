package com.jidan.receiving.screens

import android.widget.EditText
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import com.jidan.receiving.DemoMode
import com.jidan.receiving.DeviceFactory
import com.jidan.receiving.MainActivity
import com.jidan.receiving.R
import com.jidan.receiving.device.fake.FakeScale
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 演示版（F5-3）的渲染冒烟：真跑 MainActivity（模拟版 + 演示构建），没有登录页、没有服务器，
 * 启动即在建单页；走一遍建单 → 称重 → 汇总 → 规则弹层 → 完成，每个状态存一张 PNG。
 * 与 ScreensTest 一样只在带 `-Pscreens` 时跑：
 * `./gradlew :app:testSimDemoUnitTest -Pscreens --tests '*DemoScreensTest'`，图在 `app/build/screens/demo/`。
 * Robolectric 原生图形，不是真机。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DemoScreensTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var h: ScreenHarness

    @Before
    fun setUp() {
        Assume.assumeTrue(System.getProperty("screens.run") != null)
        RuntimeEnvironment.setQualifiers("+w393dp-h852dp-port-xxhdpi")
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        h = ScreenHarness({ controller.get() }, "demo", File("build/screens/demo"), emptySet())
    }

    @Test
    fun launchesSignedInOnCreatePageAndRunsLiSiToDone() {
        val act = { controller.get() }
        assertTrue(DemoMode.enabled)

        // 启动：没有登录页，直接是建单页，账号是演示的磅秤员
        h.waitFor("建单页") { act().findViewById<android.view.View>(R.id.tail)?.isShown == true }
        h.waitFor("建单页数据") { h.hasText("最近") }
        h.advance(1500)
        val nav = (act().supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
        assertEquals(R.id.createFragment, nav.currentDestination?.id)
        assertTrue(act().findViewById<TextView>(R.id.account).text.contains("张师傅"))
        h.step("01-create-launch") { }

        h.step("02-create-1234") {
            act().findViewById<EditText>(R.id.tail).setText("1234")
            h.waitFor("尾号 1234 的农户") { h.hasText("王秀英") }
        }
        h.step("03-create-lisi") {
            h.click("李四")
            // 规则不绑农户：按名称选，选了就带出商品、规格与包装箱
            h.waitFor("列出收购规则") { h.hasText("粉蛋李四收购定价方案") }
            h.click("粉蛋李四收购定价方案")
        }
        h.step("04-create-ready") {
            h.click("1号仓库")
            h.waitFor("三步齐了") { h.hasText("开始收蛋") }
        }
        h.run("开始收蛋") {
            h.click("开始收蛋")
            // 皮重已取自规则打包的 2号包装箱
            h.waitFor("进入称重页") { h.hasText("2号包装箱") }
            h.advance(1500)
        }
        val scale = DeviceFactory.fakeScale
        h.run("选皮重") {
            h.click("2号包装箱")
            h.waitFor("称重框解锁") { !h.hasText("请选择皮重") }
            h.advance(600)
        }
        h.step("05-weigh-10boxes") {
            for (i in FakeScale.LISI_NETS_C.indices) {
                if (i > 0) scale.removeLoad().also { h.advance(1800) }
                scale.placeOneBox(FakeScale.LISI_NETS_C[i], FakeScale.DEMO_BOX_TARE_C)
                h.advance(1800)
            }
            h.waitFor("记满 10 箱") { h.hasText("已称 10 箱") }
        }
        h.step("06-summary") {
            h.click("确认计量完毕")
            h.waitFor("汇总页出金额") { h.hasText("提交单据") }
            h.waitFor("181 元/箱、1,810 元") { h.hasText("1,810") }
        }
        h.step("07-rule-sheet") {
            h.clickId(R.id.factRule)
            h.waitFor("规则弹层读完") { h.hasDialogText("尾差") }
        }
        h.step("08-done") {
            h.dismissDialogs()
            h.click("提交单据")
            h.waitFor("完成页") { h.hasText("再收一单") }
            assertTrue(h.hasText("1,810"))
        }
        assertEquals(emptyList<String>(), h.unreachable)
    }
}

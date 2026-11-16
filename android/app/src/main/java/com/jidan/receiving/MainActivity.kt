package com.jidan.receiving

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.jidan.receiving.ui.login.LoginFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appContainer().onAppStart()

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        val nav = navHost.navController
        if (savedInstanceState == null && appContainer().session.value != null) {
            nav.navigate(R.id.action_login_to_create)
            if (appContainer().sessionStore.exists()) {
                nav.navigate(R.id.action_create_to_weigh)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 在后台时发生的 401 没有人收，回到前台补一次
                if (appContainer().session.value == null) toLogin(nav)
                appContainer().unauthenticated.collect { toLogin(nav) }
            }
        }
    }

    /**
     * 登录失效：任何页面都回登录页（全局动作，清回退栈）。在汇总页失效的，重登后回汇总页；
     * 其余页面重登后回称重页（草稿还在）。完成页的单已提交，没有可回的，不记。
     */
    private fun toLogin(nav: NavController) {
        val dest = nav.currentDestination?.id ?: return
        if (dest == R.id.loginFragment) return
        if (dest == R.id.summaryFragment) {
            appContainer().resumeSummaryAfterLogin = true
        }
        nav.navigate(
            R.id.action_global_to_login,
            Bundle().apply { putBoolean(LoginFragment.ARG_EXPIRED, true) },
        )
    }
}

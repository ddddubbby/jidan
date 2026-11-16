package com.jidan.receiving

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
                appContainer().unauthenticated.collect {
                    val dest = nav.currentDestination?.id
                    if (dest == R.id.summaryFragment || dest == R.id.doneFragment) {
                        appContainer().resumeSummaryAfterLogin = true
                    }
                    if (dest != R.id.loginFragment) {
                        nav.navigate(
                            R.id.action_create_to_login,
                            Bundle().apply { putBoolean(LoginFragment.ARG_EXPIRED, true) },
                        )
                    }
                }
            }
        }
    }
}

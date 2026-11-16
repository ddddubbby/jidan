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

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        val nav = navHost.navController
        if (savedInstanceState == null && appContainer().session.value != null) {
            nav.navigate(R.id.action_login_to_home)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer().unauthenticated.collect {
                    if (nav.currentDestination?.id != R.id.loginFragment) {
                        nav.navigate(
                            R.id.action_home_to_login,
                            Bundle().apply { putBoolean(LoginFragment.ARG_EXPIRED, true) },
                        )
                    }
                }
            }
        }
    }
}

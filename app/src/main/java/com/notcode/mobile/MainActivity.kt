package com.notcode.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notcode.mobile.ui.NotCodeApp

class MainActivity : ComponentActivity() {
    private val model get() = androidx.lifecycle.ViewModelProvider(this)[IdeViewModel::class.java]

    override fun onStart() { super.onStart(); model.startAutoSync() }
    override fun onStop() { model.stopAutoSync(); super.onStop() }
    override fun onDestroy() { model.stopAutoSync(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { val model: IdeViewModel = viewModel(); NotCodeApp(model) }
        // Saved instance state means this is a recreation (rotation, process restore).
        // Deep links and push actions must only run on the original launch.
        if (savedInstanceState == null) {
            handleAuthIntent(intent)
            handlePushIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
        handlePushIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val value = intent ?: return
        val uri = value.data ?: return
        if (uri.scheme != "notcode" || uri.host != "auth") return
        // One-shot link: never replay it after rotation or a later onNewIntent.
        value.data = null
        model.acceptAuthCallback(uri)
    }

    private fun handlePushIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_PUSH_ACTION) ?: return
        intent.removeExtra(EXTRA_PUSH_ACTION)
        model.handlePushAction(action)
    }

    fun openGitHubLogin() {
        val uri = Uri.parse("https://gentle-base-825d.notkovvik.workers.dev/auth/github?source=app")
        CustomTabsIntent.Builder().build().launchUrl(this, uri)
    }

    companion object {
        const val EXTRA_PUSH_ACTION = "push_action"
    }
}

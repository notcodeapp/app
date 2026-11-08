package com.notcode.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notcode.mobile.ui.NotCodeApp

class MainActivity : ComponentActivity() {
    private val model get() = androidx.lifecycle.ViewModelProvider(this)[IdeViewModel::class.java]

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onStart() { super.onStart(); model.startAutoSync() }
    override fun onStop() { model.stopAutoSync(); super.onStop() }
    override fun onDestroy() { model.stopAutoSync(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { val model: IdeViewModel = viewModel(); NotCodeApp(model) }
    }

    /** С targetSdk 34 без разрешения уведомлений индикатор фоновой работы не показывается. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

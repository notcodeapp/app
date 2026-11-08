package com.notcode.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
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
    }
}

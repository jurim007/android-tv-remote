package com.jurim007.androidtvremote

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.ui.RemoteViewModel
import com.jurim007.androidtvremote.ui.RemoteScreen
import com.jurim007.androidtvremote.ui.theme.AndroidTvRemoteTheme

class MainActivity : ComponentActivity() {
    private val remoteViewModel by lazy { ViewModelProvider(this)[RemoteViewModel::class.java] }
    private val tvVolumeKeys = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        setContent {
            AndroidTvRemoteTheme {
                RemoteScreen(modifier = Modifier.fillMaxSize(), viewModel = remoteViewModel)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteCommand.VolumeUp
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteCommand.VolumeDown
            else -> return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    tvVolumeKeys.remove(event.keyCode)
                    if (remoteViewModel.canUsePhoneVolume()) tvVolumeKeys.add(event.keyCode)
                }
                if (event.keyCode in tvVolumeKeys) {
                    if (remoteViewModel.canUsePhoneVolume()) remoteViewModel.send(command)
                    return true
                }
            }
            KeyEvent.ACTION_UP -> if (tvVolumeKeys.remove(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}

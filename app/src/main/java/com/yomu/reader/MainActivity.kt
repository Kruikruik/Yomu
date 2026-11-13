package com.yomu.reader

import android.os.Bundle
import android.view.KeyEvent
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.ReaderKeys
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yomu.reader.ui.YomuRoot
import com.yomu.reader.ui.YomuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startTab = intent?.getStringExtra(EXTRA_TAB)
        setContent {
            YomuTheme {
                YomuRoot(startTab = startTab)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ReaderKeys.active && Prefs.volumeKeys &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                ReaderKeys.send(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

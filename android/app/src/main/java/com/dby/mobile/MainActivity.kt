package com.dby.mobile

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dby.core.coreVersion
import com.dby.mobile.ui.theme.DbyTheme
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the system bars keep light icons even when the phone is in light mode.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Log.i("DBYBENCH", "app=dby event=boot core=${coreVersion()}")
        val app = DbyApp.instance
        // With App lock on, the app's screen is hidden from screenshots and the recents list.
        if (app.prefs.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { DbyTheme(app.prefs) { AppRoot(app) } }
    }

    override fun onStart() {
        super.onStart()
        DbyApp.instance.sessions.activity = WeakReference(this)
        DbyApp.instance.onForeground()
    }

    override fun onStop() {
        super.onStop()
        DbyApp.instance.onBackground()
    }
}

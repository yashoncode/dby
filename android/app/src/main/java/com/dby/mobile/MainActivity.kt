package com.dby.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import com.dby.core.coreVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val version = coreVersion()
        Log.i("DBYBENCH", "app=dby event=boot core=$version")
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Text("DBY core $version") } }
    }
}

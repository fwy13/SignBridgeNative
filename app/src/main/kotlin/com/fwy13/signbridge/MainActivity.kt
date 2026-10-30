package com.fwy13.signbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.fwy13.signbridge.ui.SignBridgeApp

class MainActivity: ComponentActivity() {
    private val model: BridgeViewModel by viewModels()
    private var pendingLink by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingLink=intent.dataString
        setContent { SignBridgeApp(model,pendingLink) { pendingLink=null } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);pendingLink=intent.dataString }
}

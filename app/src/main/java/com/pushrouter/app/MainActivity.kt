package com.pushrouter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pushrouter.app.ui.RouterApp
import com.pushrouter.app.ui.RouterViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as RouterApplication).repository
        setContent {
            if (repo == null) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Saved data could not be opened. Forwarding is stopped. If the device key has been lost, clear Push Router’s app storage in Android Settings to set it up again.")
                    }
                }
            } else {
                val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = RouterViewModel(repo) as T
                })[RouterViewModel::class.java]
                RouterApp(model)
            }
        }
    }
}

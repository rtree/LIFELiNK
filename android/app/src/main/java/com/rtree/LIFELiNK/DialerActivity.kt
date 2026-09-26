package com.rtree.LIFELiNK

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Dial pad for ACTION_DIAL, required of the default phone app. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        setContent {
            LifeLinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DialerScreen(initial = initial, onPlaced = { finish() })
                }
            }
        }
    }
}

@Composable
private fun DialerScreen(initial: String, onPlaced: () -> Unit) {
    val context = LocalContext.current
    var number by remember { mutableStateOf(initial) }
    var message by remember { mutableStateOf<String?>(null) }
    val requestCallPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && placeCall(context, number)) onPlaced() else if (!granted) {
            message = "Phone permission was denied"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LIFELiNK phone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            number.ifEmpty { "Enter a number" },
            style = MaterialTheme.typography.headlineMedium,
            color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        DialPad(onDigit = { number += it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { number += "+" }) { Text("+") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { number = number.dropLast(1) }) { Text("Delete") }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = number.isNotBlank(),
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    if (placeCall(context, number)) onPlaced() else message = "Could not place the call"
                } else {
                    requestCallPermission.launch(Manifest.permission.CALL_PHONE)
                }
            },
        ) {
            Text("Call", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Places a managed SIM call through Telecom, which also covers emergency numbers correctly. */
fun placeCall(context: Context, number: String): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return runCatching {
        context.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("tel", number, null), Bundle())
    }.isSuccess
}

package com.rtree.LIFELiNK

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** Incoming and ongoing call screen. Shown over the lock screen without unlocking the device. */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        handleAction(intent)
        setContent {
            LifeLinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InCallScreen(
                        onAddCall = { startActivity(Intent(this, DialerActivity::class.java)) },
                        onAllEnded = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        if (intent?.action == LifeLinkInCallService.ACTION_ANSWER) {
            CallRegistry.calls.value
                .firstOrNull { it.state == Call.STATE_RINGING }
                ?.let { CallRegistry.answer(it.call) }
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun InCallScreen(onAddCall: () -> Unit, onAllEnded: () -> Unit) {
    val calls by CallRegistry.calls.collectAsStateWithLifecycle()
    val canAddCall by CallRegistry.canAddCall.collectAsStateWithLifecycle()
    val muted by CallRegistry.muted.collectAsStateWithLifecycle()
    val speaker by CallRegistry.speaker.collectAsStateWithLifecycle()
    val sosStatus by ConferenceSosOrchestrator.status.collectAsStateWithLifecycle()
    var showKeypad by remember { mutableStateOf(false) }

    val visible = calls.filter { !it.isChild && it.state != Call.STATE_DISCONNECTED }
    LaunchedEffect(visible.isEmpty()) {
        if (visible.isEmpty()) {
            delay(1_500)
            if (CallRegistry.calls.value.none { it.state != Call.STATE_DISCONNECTED }) onAllEnded()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LIFELiNK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        sosStatus?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (visible.isEmpty()) Text("Call ended")
        visible.forEach { snapshot -> CallCard(snapshot) }

        val active = visible.firstOrNull { it.state == Call.STATE_ACTIVE }
        if (visible.any { it.state in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { CallRegistry.setMuted(!muted) }) {
                    Text(if (muted) "Unmute" else "Mute")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { CallRegistry.setSpeaker(!speaker) }) {
                    Text(if (speaker) "Earpiece" else "Speaker")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { showKeypad = !showKeypad }) {
                    Text("Keypad")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = canAddCall, onClick = onAddCall) {
                    Text("Add call")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = visible.size >= 2,
                    onClick = { CallRegistry.mergeAny() },
                ) {
                    Text("Merge")
                }
            }
        }
        if (showKeypad && active != null) {
            DialPad(onDigit = { CallRegistry.playDtmf(active.call, it) })
        }
    }
}

@Composable
private fun CallCard(snapshot: CallSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (snapshot.isConference) "Conference call" else snapshot.number,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(snapshot.stateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (snapshot.state == Call.STATE_RINGING) {
                    Button(modifier = Modifier.weight(1f), onClick = { CallRegistry.answer(snapshot.call) }) {
                        Text("Answer")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = { CallRegistry.reject(snapshot.call) },
                    ) {
                        Text("Decline")
                    }
                } else {
                    if (snapshot.canHold && snapshot.state in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = { CallRegistry.toggleHold(snapshot) }) {
                            Text(if (snapshot.state == Call.STATE_HOLDING) "Resume" else "Hold")
                        }
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = { CallRegistry.hangUp(snapshot.call) },
                    ) {
                        Text("Hang up")
                    }
                }
            }
        }
    }
}

@Composable
fun DialPad(onDigit: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { digit ->
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { onDigit(digit) }) {
                        Text(digit.toString(), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

package com.rtree.LIFELiNK

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = remember { LifeLinkApiClient() }
    val safetyGate = remember { EmergencySafetyGate(context) }
    val emergencyPreferences = remember { EmergencyPreferences(context) }
    var status by remember { mutableStateOf("Googleでログインしてください") }
    var locationText by remember { mutableStateOf("位置情報はまだ保存されていません") }
    var currentLocation by remember { mutableStateOf(emergencyPreferences.location) }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactId by remember { mutableStateOf(emergencyPreferences.contactId) }
    var contactText by remember {
        mutableStateOf(
            emergencyPreferences.maskedContact?.let { "登録済み: $it" }
                ?: "緊急連絡先は未登録です",
        )
    }
    var initialNote by remember { mutableStateOf("") }
    var armedAt by remember { mutableStateOf<Long?>(null) }
    var emergencyText by remember { mutableStateOf("緊急発信は待機中です") }
    var activeEmergencyEventId by remember { mutableStateOf<String?>(null) }
    var beaconText by remember { mutableStateOf("Beacon監視は停止中です") }

    val requestBluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            beaconText = runCatching {
                BeaconTriggerManager.start(context)
                "Beacon監視中"
            }.getOrElse { error -> "Beacon監視開始失敗: ${error.userMessage()}" }
        } else {
            beaconText = "Bluetooth scan権限が拒否されました"
        }
    }

    suspend fun captureAndSaveLocation() {
        runCatching {
            captureLocation(context).also { location ->
                apiClient.saveLocation(location)
            }
        }.onSuccess { location ->
            currentLocation = location
            emergencyPreferences.location = location
            locationText = location.displayText()
            activeEmergencyEventId?.let { eventId ->
                runCatching {
                    apiClient.sendLocationUpdate(eventId, location)
                }.onSuccess {
                    locationText += "\n通話中のAIへ位置更新を送信しました"
                }.onFailure { error ->
                    locationText += "\nAIへの位置更新失敗: ${error.userMessage()}"
                }
            }
        }.onFailure { error ->
            locationText = "位置保存失敗: ${error.userMessage()}"
        }
    }

    val requestLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            scope.launch {
                captureAndSaveLocation()
            }
        } else {
            locationText = "位置情報の権限が拒否されました"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LIFELiNK", style = MaterialTheme.typography.headlineLarge)
        Text("You are never alone.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Text(status)
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    status = signInWithGoogle(context)
                }
            },
        ) {
            Text("Googleでログイン")
        }
        Spacer(Modifier.height(12.dp))
        Text(locationText)
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val hasLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

                if (hasLocationPermission) {
                    scope.launch {
                        captureAndSaveLocation()
                    }
                } else {
                    requestLocationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            },
        ) {
            Text("現在位置を取得して保存")
        }
        Spacer(Modifier.height(12.dp))
        Text(contactText)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = contactName,
            onValueChange = { contactName = it },
            label = { Text("連絡先名") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = contactPhone,
            onValueChange = { contactPhone = it },
            label = { Text("電話番号（+81...）") },
            singleLine = true,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = contactName.isNotBlank() && contactPhone.isNotBlank(),
            onClick = {
                scope.launch {
                    runCatching {
                        apiClient.createContact(contactName, contactPhone)
                    }.onSuccess { contact ->
                        contactId = contact.contactId
                        emergencyPreferences.contactId = contact.contactId
                        emergencyPreferences.maskedContact = "$contactName / ${contact.maskedPhone}"
                        contactText = "登録済み: $contactName / ${contact.maskedPhone}"
                    }.onFailure { error ->
                        contactText = "連絡先登録失敗: ${error.userMessage()}"
                    }
                }
            },
        ) {
            Text("緊急連絡先を登録")
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = initialNote,
            onValueChange = { initialNote = it },
            label = { Text("状況メモ（任意）") },
        )
        Text(emergencyText)
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            enabled = contactId != null,
            onClick = {
                val now = SystemClock.elapsedRealtime()
                val armed = armedAt
                if (armed == null || now - armed > EMERGENCY_CONFIRM_WINDOW_MS) {
                    armedAt = now
                    emergencyText = "10秒以内にもう一度押すと発信します"
                    return@Button
                }

                armedAt = null
                val selectedContactId = contactId ?: return@Button
                val attempt = safetyGate.begin(selectedContactId)
                emergencyText = if (attempt.isRetry) {
                    "同じ発信要求を再送しています"
                } else {
                    "発信を開始しています"
                }
                scope.launch {
                    runCatching {
                        apiClient.createEmergencyEvent(
                            eventId = attempt.eventId,
                            contactId = selectedContactId,
                            trigger = ScreenButtonEmergencyTrigger,
                            location = currentLocation,
                            initialNote = initialNote,
                        )
                    }.onSuccess { event ->
                        activeEmergencyEventId = event.eventId
                        emergencyText = "発信状態: ${event.state}"
                        while (event.state !in TERMINAL_EVENT_STATES) {
                            delay(EVENT_STATUS_POLL_INTERVAL_MS)
                            val latest = runCatching {
                                apiClient.getEmergencyEvent(event.eventId)
                            }.getOrElse { error ->
                                emergencyText = "状態確認失敗（再試行します）: ${error.userMessage()}"
                                continue
                            }
                            emergencyText = "発信状態: ${latest.state}"
                            if (latest.state in TERMINAL_EVENT_STATES) {
                                safetyGate.clear(event.eventId)
                                activeEmergencyEventId = null
                                break
                            }
                        }
                    }.onFailure { error ->
                        if (error is ApiException && error.statusCode in 400..499) {
                            safetyGate.clear(attempt.eventId)
                        }
                        emergencyText = "発信失敗: ${error.userMessage()}"
                    }
                }
            },
        ) {
            Text(if (armedAt == null) "緊急発信" else "発信を確定")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = activeEmergencyEventId != null && initialNote.isNotBlank(),
            onClick = {
                val eventId = activeEmergencyEventId ?: return@Button
                val note = initialNote
                scope.launch {
                    runCatching {
                        apiClient.sendNoteUpdate(eventId, note)
                    }.onSuccess {
                        initialNote = ""
                        emergencyText = "追加メモを通話中のAIへ送信しました"
                    }.onFailure { error ->
                        emergencyText = "追加メモ送信失敗: ${error.userMessage()}"
                    }
                }
            },
        ) {
            Text("通話中メモを送信")
        }
        Text(beaconText)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = contactId != null,
            onClick = {
                if (BeaconTriggerManager.hasPermission(context)) {
                    beaconText = runCatching {
                        BeaconTriggerManager.start(context)
                        "Beacon監視中"
                    }.getOrElse { error -> "Beacon監視開始失敗: ${error.userMessage()}" }
                } else {
                    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        arrayOf(Manifest.permission.BLUETOOTH_SCAN)
                    } else {
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    requestBluetoothPermission.launch(permissions)
                }
            },
        ) {
            Text("Beacon監視を開始")
        }
        Spacer(Modifier.height(12.dp))
        Text("Backend: ${BuildConfig.BACKEND_URL}", style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun signInWithGoogle(context: android.content.Context): String {
    val webClientId = context.getString(R.string.default_web_client_id)

    return runCatching {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val result = CredentialManager.create(context).getCredential(
            context = context,
            request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build(),
        )
        val customCredential = result.credential as? CustomCredential
            ?: error("Google credential was not returned")
        require(customCredential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val googleCredential = GoogleIdTokenCredential.createFrom(customCredential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        val user = FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await().user
            ?: error("Firebase user was not returned")
        "ログイン済み: ${user.email ?: user.uid}"
    }.getOrElse { error ->
        "ログイン失敗: ${error.message ?: "unknown error"}"
    }
}

private suspend fun captureLocation(context: Context): LocationSnapshot {
    val hasFine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) {
        error("位置情報の権限が必要です")
    }

    val location = LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(
            if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            CancellationTokenSource().token,
        )
        .await()
        ?: error("位置を取得できませんでした")
    val capturedAt = Instant.now().toString()
    val address = reverseGeocode(context, location.latitude, location.longitude)
    return LocationSnapshot(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = location.accuracy,
        capturedAt = capturedAt,
        address = address,
        geocodedAt = address?.let { Instant.now().toString() },
    )
}

@Suppress("DEPRECATION")
private suspend fun reverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        Geocoder(context, Locale.JAPAN)
            .getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
            ?.getAddressLine(0)
    }.getOrNull()
}

private fun LocationSnapshot.displayText(): String =
    "${address ?: "住所不明"}\n緯度 %.6f / 経度 %.6f / 精度 %.0fm".format(
        latitude,
        longitude,
        accuracyMeters,
    )

private fun Throwable.userMessage(): String = when (this) {
    is ApiException -> when (errorCode) {
        "world_id_verification_required" -> "World IDで人間証明してください"
        "contact_not_found" -> "登録した連絡先が見つかりません"
        else -> errorCode
    }
    else -> message ?: "不明なエラー"
}

private const val EMERGENCY_CONFIRM_WINDOW_MS = 10_000L
private const val EVENT_STATUS_POLL_INTERVAL_MS = 2_000L
private val TERMINAL_EVENT_STATES = setOf("completed", "failed")
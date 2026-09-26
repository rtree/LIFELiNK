package com.rtree.LIFELiNK

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifeLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
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
    val initialUser = FirebaseAuth.getInstance().currentUser
    var status by remember {
        mutableStateOf(
            initialUser?.let { "Signed in as ${it.email ?: it.uid}" }
                ?: "Sign in with Google to get started",
        )
    }
    var signedInUid by remember { mutableStateOf(initialUser?.uid) }
    var worldIdStatus by remember { mutableStateOf("Not verified yet") }
    var worldIdVerified by remember { mutableStateOf(false) }
    var pendingWorldIdFlowId by remember { mutableStateOf(emergencyPreferences.worldIdFlowId) }
    var locationText by remember { mutableStateOf("No location saved yet") }
    var currentLocation by remember { mutableStateOf(emergencyPreferences.location) }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactId by remember { mutableStateOf(emergencyPreferences.contactId) }
    var contactText by remember {
        mutableStateOf(
            emergencyPreferences.maskedContact?.let { "Registered: $it" }
                ?: "No emergency contact yet",
        )
    }
    var initialNote by remember { mutableStateOf("") }
    var sosTapCount by remember { mutableStateOf(0) }
    var sosHoldProgress by remember { mutableStateOf(0f) }
    var emergencyText by remember { mutableStateOf("Ready") }
    var conferenceJoin by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activeEmergencyEventId by remember { mutableStateOf<String?>(null) }
    var beaconText by remember { mutableStateOf("Button watch is off") }
    var linkedTriggerDevice by remember { mutableStateOf(emergencyPreferences.linkedTriggerDevice) }
    val observedAdvertisements by AdvertisementRegistry.observations.collectAsStateWithLifecycle()
    val beaconLog by AdvertisementRegistry.beaconLog.collectAsStateWithLifecycle()
    var beaconDryRun by remember { mutableStateOf(emergencyPreferences.beaconDryRun) }
    var micGranted by remember { mutableStateOf(hasMicrophonePermission(context)) }
    val requestMicrophonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
    }

    LaunchedEffect(initialUser?.uid) {
        val claims = initialUser?.getIdToken(false)?.await()?.claims.orEmpty()
        if (claims["human_verified"] == true) {
            worldIdStatus = "Verified as a unique human"
            worldIdVerified = true
        }
    }

    LaunchedEffect(pendingWorldIdFlowId) {
        val flowId = pendingWorldIdFlowId ?: return@LaunchedEffect
        while (true) {
            val flowStatus = runCatching {
                apiClient.getWorldIdFlowStatus(flowId)
            }.getOrElse { error ->
                if (error is ApiException && error.statusCode in setOf(404, 410)) {
                    emergencyPreferences.worldIdFlowId = null
                    pendingWorldIdFlowId = null
                    worldIdStatus = "The verification request expired. Please try again."
                    return@LaunchedEffect
                }
                worldIdStatus = "Connection is unstable. Retrying automatically."
                delay(WORLD_ID_STATUS_POLL_INTERVAL_MS)
                continue
            }
            when (flowStatus.state) {
                "verified" -> {
                    FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
                    emergencyPreferences.worldIdFlowId = null
                    pendingWorldIdFlowId = null
                    worldIdStatus = "Verified as a unique human"
                    worldIdVerified = true
                    return@LaunchedEffect
                }
                "failed" -> {
                    emergencyPreferences.worldIdFlowId = null
                    pendingWorldIdFlowId = null
                    worldIdStatus = "Verification failed: ${flowStatus.error ?: "unknown"}"
                    return@LaunchedEffect
                }
                "waiting_for_connection" -> worldIdStatus = "Waiting for World App to connect"
                "awaiting_confirmation" -> worldIdStatus = "Confirm the request in World App"
            }
            delay(WORLD_ID_STATUS_POLL_INTERVAL_MS)
        }
    }

    val requestBluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (BeaconTriggerManager.hasPermission(context)) {
            BeaconMonitorService.start(context)
            beaconText = if (grants[Manifest.permission.POST_NOTIFICATIONS] == false) {
                "Watch started (notifications denied, so the ongoing notice is hidden)"
            } else {
                "Watch started"
            }
        } else {
            beaconText = "Bluetooth scan permission was denied"
        }
    }
    val monitoringRunning by BeaconMonitorService.running.collectAsStateWithLifecycle()
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(BeaconMonitorService.isIgnoringBatteryOptimizations(context))
    }
    val openBatterySettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        ignoringBatteryOptimizations = BeaconMonitorService.isIgnoringBatteryOptimizations(context)
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
                    locationText += "\nSent the location update to the AI on the call"
                }.onFailure { error ->
                    locationText += "\nCould not send the location update: ${error.userMessage()}"
                }
            }
        }.onFailure { error ->
            locationText = "Could not save the location: ${error.userMessage()}"
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
            locationText = "Location permission was denied"
        }
    }

    fun launchEmergency(mode: String = LifeLinkApiClient.MODE_OUTBOUND) {
        val selectedContactId = contactId ?: return
        val attempt = safetyGate.begin(selectedContactId)
        emergencyText = if (attempt.isRetry) "Resending the same request" else "Sending your SOS"
        scope.launch {
            runCatching {
                apiClient.createEmergencyEvent(
                    eventId = attempt.eventId,
                    contactId = selectedContactId,
                    trigger = ScreenButtonEmergencyTrigger,
                    location = currentLocation,
                    initialNote = initialNote,
                    mode = mode,
                )
            }.onSuccess { event ->
                emergencyText = callStateText(event.state)
                if (event.aiNumber != null && event.joinCode != null) {
                    conferenceJoin = event.aiNumber to event.joinCode
                    emergencyText = "Call the AI, then add and merge your contact"
                    if (isDefaultPhoneApp(context) && event.contactPhone != null) {
                        val started = ConferenceSosOrchestrator.start(
                            context = context,
                            apiClient = apiClient,
                            eventId = event.eventId,
                            aiNumber = event.aiNumber,
                            joinCode = event.joinCode,
                            contactPhone = event.contactPhone,
                        )
                        if (started) emergencyText = "Calling the AI, then your contact"
                    }
                }
                if (event.state in TERMINAL_EVENT_STATES) {
                    safetyGate.clear(event.eventId)
                    activeEmergencyEventId = null
                    conferenceJoin = null
                    return@onSuccess
                }
                activeEmergencyEventId = event.eventId
                while (event.state !in TERMINAL_EVENT_STATES) {
                    delay(EVENT_STATUS_POLL_INTERVAL_MS)
                    val latest = runCatching {
                        apiClient.getEmergencyEvent(event.eventId)
                    }.getOrElse { error ->
                        emergencyText = "Checking status failed, retrying: ${error.userMessage()}"
                        continue
                    }
                    if (conferenceJoin == null || latest.state != "accepted") {
                        emergencyText = callStateText(latest.state)
                    }
                    if (latest.state != "accepted") conferenceJoin = null
                    if (latest.state in TERMINAL_EVENT_STATES) {
                        latest.failureCode?.let { emergencyText += " ($it)" }
                        safetyGate.clear(event.eventId)
                        activeEmergencyEventId = null
                        break
                    }
                }
            }.onFailure { error ->
                if (error is ApiException && error.statusCode in 400..499) {
                    safetyGate.clear(attempt.eventId)
                }
                emergencyText = "Could not send: ${error.userMessage()}"
            }
        }
    }

    // Foreground-only reporting: every minute normally, every 10s while an SOS is live.
    // Backgrounded, `getCurrentLocation` never completes, so the loop is both
    // lifecycle-scoped and time-boxed to stop it from stalling forever.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(signedInUid, activeEmergencyEventId) {
        if (signedInUid == null) return@LaunchedEffect
        val intervalMs = if (activeEmergencyEventId != null) {
            LOCATION_INTERVAL_ACTIVE_MS
        } else {
            LOCATION_INTERVAL_IDLE_MS
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            MotionMonitor.start(context)
            try {
                while (true) {
                    if (hasLocationPermission(context)) {
                        withTimeoutOrNull(LOCATION_FIX_TIMEOUT_MS) { captureAndSaveLocation() }
                    }
                    delay(intervalMs)
                }
            } finally {
                MotionMonitor.stop()
            }
        }
    }

    LaunchedEffect(sosTapCount) {
        if (sosTapCount == 0) return@LaunchedEffect
        delay(SOS_TAP_WINDOW_MS)
        sosTapCount = 0
    }

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var beaconBurstSeconds by remember { mutableStateOf(emergencyPreferences.beaconBurstSeconds) }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
                ) {
                    Text(
                        "LIFELiNK",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "You are never alone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TabRow(selectedTabIndex = tab.ordinal) {
                    AppTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                AppTab.HOME -> {
                    ReadinessCard(
                        signedIn = signedInUid != null,
                        worldIdVerified = worldIdVerified,
                        contactRegistered = contactId != null,
                        buttonLinked = linkedTriggerDevice != null,
                        watching = monitoringRunning,
                    )
                    Text(emergencyText, style = MaterialTheme.typography.titleMedium)
                    val sosInteraction = remember { MutableInteractionSource() }
                    val sosPressed by sosInteraction.collectIsPressedAsState()
                    var longPressFired by remember { mutableStateOf(false) }
                    LaunchedEffect(sosPressed) {
                        if (!sosPressed) {
                            sosHoldProgress = 0f
                            return@LaunchedEffect
                        }
                        longPressFired = false
                        val startedAt = SystemClock.elapsedRealtime()
                        while (sosHoldProgress < 1f) {
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            sosHoldProgress = (elapsed / SOS_HOLD_MS.toFloat()).coerceAtMost(1f)
                            delay(16)
                        }
                        longPressFired = true
                        sosTapCount = 0
                        launchEmergency()
                    }
                    val armProgress by animateFloatAsState(
                        targetValue = maxOf(
                            sosTapCount.toFloat() / SOS_TAP_COUNT,
                            sosHoldProgress,
                        ).coerceIn(0f, 1f),
                        label = "sosArmProgress",
                    )
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        enabled = contactId != null,
                        interactionSource = sosInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = lerp(
                                MaterialTheme.colorScheme.error,
                                SOS_ARMED_COLOR,
                                armProgress,
                            ),
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        onClick = {
                            if (longPressFired) {
                                longPressFired = false
                                return@Button
                            }
                            sosTapCount += 1
                            if (sosTapCount >= SOS_TAP_COUNT) {
                                sosTapCount = 0
                                launchEmergency()
                            } else {
                                emergencyText =
                                    "Tap ${SOS_TAP_COUNT - sosTapCount} more times, or hold for 2 seconds"
                            }
                        },
                    ) {
                        Text(
                            when {
                                armProgress >= 1f -> "Sending"
                                armProgress > 0f -> "Keep going"
                                else -> "SOS"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        if (contactId == null) {
                            "Add an emergency contact in Members before you can send an SOS."
                        } else {
                            "Tap $SOS_TAP_COUNT times or hold for 2 seconds. The button darkens as it arms. " +
                                "LIFELiNK then calls your contact, an AI explains where you are, and your " +
                                "Discord members get a DM at the same time."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConferenceSosSection(
                        enabled = contactId != null && activeEmergencyEventId == null,
                        join = conferenceJoin,
                        onStart = { launchEmergency(LifeLinkApiClient.MODE_CARRIER_CONFERENCE) },
                        onCallAi = { (number, code) ->
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "$number,,$code", null)),
                            )
                        },
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = initialNote,
                        onValueChange = { initialNote = it },
                        label = { Text("What is happening? (optional)") },
                    )
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
                                    emergencyText = "Sent your note to the AI on the call"
                                }.onFailure { error ->
                                    emergencyText = "Could not send the note: ${error.userMessage()}"
                                }
                            }
                        },
                    ) {
                        Text("Tell the AI on the call")
                    }
                    HorizontalDivider()
                    EmergencyFeedSection(uid = signedInUid)
                }

                AppTab.MEMBERS -> {
                    Text("Emergency contact", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "This is the phone number LIFELiNK calls. The AI speaks on your behalf.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(contactText)
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = contactPhone,
                        onValueChange = { contactPhone = it },
                        label = { Text("Phone number (+81...)") },
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
                                    emergencyPreferences.maskedContact =
                                        "$contactName / ${contact.maskedPhone}"
                                    contactText = "Registered: $contactName / ${contact.maskedPhone}"
                                }.onFailure { error ->
                                    contactText = "Could not register: ${error.userMessage()}"
                                }
                            }
                        },
                    ) {
                        Text("Save emergency contact")
                    }
                    DiscordContactsSection(apiClient = apiClient)
                }

                AppTab.SETTINGS -> {
                    Text("Account", style = MaterialTheme.typography.titleLarge)
                    Text(status)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                status = signInWithGoogle(context)
                                signedInUid = FirebaseAuth.getInstance().currentUser?.uid
                            }
                        },
                    ) {
                        Text("Sign in with Google")
                    }
                    Text(
                        "Profile (nickname and area): coming soon",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()
                    Text("Proof of humanity", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "World ID proves one real person is behind this account, so an SOS cannot be spammed by bots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(worldIdStatus)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = FirebaseAuth.getInstance().currentUser != null,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val flow = apiClient.startWorldIdFlow()
                                    emergencyPreferences.worldIdFlowId = flow.flowId
                                    pendingWorldIdFlowId = flow.flowId
                                    openWorldIdConnector(context, flow.connectorUri)
                                    worldIdStatus = "Finish the verification in World App"
                                }.onFailure { error ->
                                    worldIdStatus = "Verification failed: ${error.userMessage()}"
                                }
                            }
                        },
                    ) {
                        Text("Verify with World ID")
                    }

                    HorizontalDivider()
                    Text("Location", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Only your prefecture ever leaves this phone. Coordinates are never stored or spoken.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(locationText)
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
                        Text("Update my location")
                    }

                    HorizontalDivider()
                    PhoneAppSection()

                    HorizontalDivider()
                    Text("Listening (coming soon)", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "During an emergency LIFELiNK will listen to what is happening around you and " +
                            "turn it into short notes for your contact and your members. Recordings are " +
                            "never saved. Grant the microphone now so no permission dialog appears when " +
                            "you actually need help.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (micGranted) "Microphone: allowed" else "Microphone: not allowed yet",
                        color = if (micGranted) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !micGranted,
                        onClick = { requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                    ) {
                        Text("Allow microphone")
                    }
                    Text(
                        "Listening itself is not implemented yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()
                    Text("Physical button", style = MaterialTheme.typography.titleLarge)
                    Text(beaconText)
                    Text(
                        if (monitoringRunning) {
                            "Watching: your button works while the LIFELiNK notice is in the status bar"
                        } else {
                            "Not watching: pressing the button does nothing"
                        },
                        color = if (monitoringRunning) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = contactId != null,
                        onClick = {
                            if (monitoringRunning) {
                                BeaconMonitorService.stop(context)
                                beaconText = "Watch stopped"
                                return@Button
                            }
                            val permissions = buildList {
                                add(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Manifest.permission.BLUETOOTH_SCAN
                                    } else {
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    },
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            requestBluetoothPermission.launch(permissions.toTypedArray())
                        },
                    ) {
                        Text(if (monitoringRunning) "Stop watching" else "Start watching")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (beaconDryRun) {
                                "Dry run: ON (no real call)"
                            } else {
                                "Dry run: OFF (a press places a real call)"
                            },
                            color = if (beaconDryRun) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Switch(
                            checked = beaconDryRun,
                            onCheckedChange = { checked ->
                                emergencyPreferences.beaconDryRun = checked
                                beaconDryRun = checked
                                if (monitoringRunning) BeaconMonitorService.start(context)
                            },
                        )
                    }
                    Text("Button broadcast time (match the vendor app): ${beaconBurstSeconds}s")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(10, 60).forEach { seconds ->
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = beaconBurstSeconds != seconds,
                                onClick = {
                                    emergencyPreferences.beaconBurstSeconds = seconds
                                    beaconBurstSeconds = seconds
                                    AdvertisementRegistry.appendBeaconLog(
                                        "Broadcast time set to ${seconds}s (gap threshold ${seconds + 15}s)",
                                    )
                                },
                            ) {
                                Text("${seconds}s")
                            }
                        }
                    }
                    Text(
                        if (ignoringBatteryOptimizations) {
                            "Battery optimization: excluded"
                        } else {
                            "Battery optimization: not excluded (the watch can be killed while locked)"
                        },
                        color = if (ignoringBatteryOptimizations) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (!ignoringBatteryOptimizations) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                openBatterySettings.launch(
                                    Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            },
                        ) {
                            Text("Exclude from battery optimization")
                        }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            openBatterySettings.launch(
                                Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    ) {
                        Text("Open battery settings")
                    }
                    Text(
                        "On Galaxy: App info > Battery > Unrestricted. Then Settings > Battery > " +
                            "Background usage limits > Never sleeping apps > add LIFELiNK.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    AdvertisementLinkSection(
                        observations = observedAdvertisements,
                        linkedDevice = linkedTriggerDevice,
                        onLink = { observation ->
                            emergencyPreferences.linkTrigger(observation)
                            linkedTriggerDevice = emergencyPreferences.linkedTriggerDevice
                            if (BeaconTriggerManager.hasPermission(context)) {
                                BeaconTriggerManager.stop(context)
                                BeaconTriggerManager.start(context)
                                beaconText = "Linked and watching (${observation.suggestedTransport?.label})"
                            }
                        },
                        onUnlink = {
                            emergencyPreferences.linkedTriggerDevice = null
                            linkedTriggerDevice = null
                            if (BeaconTriggerManager.hasPermission(context)) {
                                BeaconTriggerManager.stop(context)
                                BeaconTriggerManager.start(context)
                                beaconText = "Unlinked (observing only, no trigger)"
                            }
                        },
                    )
                    BeaconLogSection(
                        entries = beaconLog,
                        onClear = AdvertisementRegistry::clearBeaconLog,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Backend: ${BuildConfig.BACKEND_URL}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    signedIn: Boolean,
    worldIdVerified: Boolean,
    contactRegistered: Boolean,
    buttonLinked: Boolean,
    watching: Boolean,
) {
    val steps = listOf(
        "Signed in" to signedIn,
        "Verified with World ID" to worldIdVerified,
        "Emergency contact added" to contactRegistered,
        "Physical button linked" to buttonLinked,
        "Button watch running" to watching,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (steps.all { it.second }) "You are covered" else "Finish setting up",
                style = MaterialTheme.typography.titleMedium,
            )
            steps.forEach { (label, done) ->
                Text(
                    if (done) "\u2713  $label" else "\u2013  $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun DiscordContactsSection(apiClient: LifeLinkApiClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<DiscordContact>>(emptyList()) }
    var statusText by remember { mutableStateOf("Members also get a Discord DM when you send an SOS") }

    suspend fun refresh() {
        runCatching { apiClient.listDiscordContacts() }
            .onSuccess { contacts = it }
            .onFailure { statusText = "Could not load members: ${it.userMessage()}" }
    }

    LaunchedEffect(Unit) {
        if (FirebaseAuth.getInstance().currentUser != null) refresh()
    }

    HorizontalDivider()
    Text("Discord members", style = MaterialTheme.typography.titleLarge)
    Text(
        "Setup\n" +
            "1. Create a Discord server for LIFELiNK (left sidebar “+” > Create My Own > For me and my friends). An existing server works too.\n" +
            "2. Tap “Add the bot to your server” below and authorize it for that server. The bot and your friend must share a server, otherwise Discord blocks the DM with error 50278.\n" +
            "3. Invite the friends you want to be notified to that same server.\n" +
            "4. Tap “Create invite” and send the link. Your friend opens it and confirms their Discord identity.\n" +
            "5. Tap “Refresh”, then “Test DM”. When your friend taps “Confirm” in the DM, they are ready.\n" +
            "During an SOS each member gets a DM, and whatever they reply is passed to the AI on the call.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://discord.com/oauth2/authorize?client_id=1553217776179486882&scope=bot&permissions=0&integration_type=0"),
                ),
            )
        },
    ) {
        Text("Add the bot to your server")
    }
    Text(statusText, style = MaterialTheme.typography.bodySmall)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = {
                scope.launch {
                    runCatching { apiClient.createDiscordInvite() }
                        .onSuccess { url ->
                            statusText = "Invite created. It is valid for 24 hours and can be used once."
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(
                                            Intent.EXTRA_TEXT,
                                            "You have been invited to be a LIFELiNK emergency member: $url",
                                        ),
                                    "Share the invite",
                                ),
                            )
                        }
                        .onFailure { statusText = "Could not create the invite: ${it.userMessage()}" }
                }
            },
        ) {
            Text("Create invite")
        }
        Button(modifier = Modifier.weight(1f), onClick = { scope.launch { refresh() } }) {
            Text("Refresh")
        }
    }
    if (contacts.isEmpty()) {
        Text("No approved members yet", style = MaterialTheme.typography.bodySmall)
    }
    contacts.forEach { contact ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        contact.testStatus == null -> "Test DM not sent yet"
                        contact.testStatus == "failed" ->
                            "Test DM failed (Discord error ${contact.testErrorCode ?: "unknown"})"
                        contact.testAcknowledged -> "Test DM confirmed"
                        else -> "Test DM sent, waiting for them to confirm"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                statusText = runCatching { apiClient.sendDiscordTest(contact.id) }
                                    .fold(
                                        { "Sent a test DM to ${contact.displayName}" },
                                        { "Test DM failed: ${it.userMessage()}" },
                                    )
                                refresh()
                            }
                        },
                    ) {
                        Text("Test DM")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                statusText = runCatching { apiClient.revokeDiscordContact(contact.id) }
                                    .fold(
                                        { "Removed ${contact.displayName}" },
                                        { "Could not remove: ${it.userMessage()}" },
                                    )
                                refresh()
                            }
                        },
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvertisementLinkSection(
    observations: List<AdvertisementObservation>,
    linkedDevice: LinkedTriggerDevice?,
    onLink: (AdvertisementObservation) -> Unit,
    onUnlink: () -> Unit,
) {
    HorizontalDivider()
    Text("Link your button", style = MaterialTheme.typography.titleLarge)
    Text(
        linkedDevice?.let { device ->
            "Linked: ${device.transport.label} / ${device.title}\n" +
                device.beaconSlots.joinToString("\n") {
                    "• ${it.label}${if (it == device.beaconIdleSlot) " (idle, never calls)" else " (press calls)"}"
                } +
                if (device.transport == TriggerTransport.BEACON && device.beaconIdleSlot == null) {
                    "\nThe idle slot was not detected, so nothing will be sent. Please link again."
                } else {
                    ""
                }
        } ?: "Not linked. Your button cannot trigger an SOS until you link it.",
    )
    Text(
        "How to link: short-press button 1 and button 2 once each. When the card below shows more " +
            "observed slots, link it. Packet counts are not press counts — one press broadcasts about " +
            "once a second for up to 60 seconds.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (linkedDevice != null) {
        Button(onClick = onUnlink, modifier = Modifier.fillMaxWidth()) {
            Text("Unlink")
        }
    }

    AdvertisementKind.entries.forEach { kind ->
        val entries = observations
            .filter { it.kind == kind }
            .sortedWith(
                compareBy<AdvertisementObservation, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy { it.key },
            )
        if (entries.isEmpty()) return@forEach
        Text(kind.label, style = MaterialTheme.typography.titleMedium)
        entries.forEach { observation ->
            AdvertisementObservationCard(
                observation = observation,
                linkedDevice = linkedDevice?.takeIf { it.key == observation.key },
                onLink = { onLink(observation) },
            )
        }
    }
}

@Composable
private fun BeaconLogSection(
    entries: List<BeaconLogEntry>,
    onClear: () -> Unit,
) {
    HorizontalDivider()
    Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
    Text(
        "Button state changes, accepted long presses, latency and API timings (newest first, up to " +
            "300 entries). Long-press to select and copy; the same lines go to the logcat tag " +
            "LIFELiNK.BeaconLog.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val clipboard = LocalClipboardManager.current
    val logText = entries.joinToString("\n") { "${formatLogTime(it.atMillis)} ${it.text}" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { clipboard.setText(AnnotatedString(logText)) }, modifier = Modifier.weight(1f)) {
            Text("Copy log")
        }
        Button(onClick = onClear, modifier = Modifier.weight(1f)) {
            Text("Clear log")
        }
    }
    SelectionContainer {
        Text(logText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AdvertisementObservationCard(
    observation: AdvertisementObservation,
    linkedDevice: LinkedTriggerDevice?,
    onLink: () -> Unit,
) {
    val lastSeenTime = remember(observation.lastSeenAtMillis) {
        LAST_SEEN_TIME_FORMATTER.format(
            Instant.ofEpochMilli(observation.lastSeenAtMillis)
                .atZone(ZoneId.systemDefault()),
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(observation.title, style = MaterialTheme.typography.titleMedium)
                Text("Last seen $lastSeenTime")
            }
            Text(observation.detail, style = MaterialTheme.typography.bodySmall)
            if (observation.beaconSlots.isNotEmpty()) {
                Text(
                    "${observation.beaconSlots.size} observed slots (the busiest one is idle):\n" +
                        observation.beaconSlots.joinToString("\n") { slot ->
                            "• ${slot.label} ×${observation.beaconSlotCounts[slot] ?: 0}" +
                                if (slot == observation.inferredIdleSlot) "  <- idle" else ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("RSSI ${observation.rssi} dBm / ${observation.seenCount} packets")
            if (observation.beaconLongPress == true) {
                Text("Latest packet: long press")
            }
            if (observation.beaconBatteryLow == true) {
                Text("Battery low", color = MaterialTheme.colorScheme.error)
            }
            val hasNewSlots = linkedDevice != null &&
                (!linkedDevice.beaconSlots.containsAll(observation.beaconSlots) ||
                    (linkedDevice.transport == TriggerTransport.BEACON && linkedDevice.beaconIdleSlot == null))
            when {
                hasNewSlots -> {
                    Text("Linked, but new slots were observed")
                    Button(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                        Text("Update the link with these slots")
                    }
                }
                linkedDevice != null -> Text("Linked (${observation.suggestedTransport?.label})")
                observation.suggestedTransport == TriggerTransport.GATT -> {
                    Button(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                        Text("Link as a GATT candidate")
                    }
                    Text("GATT connections are not enabled yet", style = MaterialTheme.typography.bodySmall)
                }
                observation.suggestedTransport == TriggerTransport.BEACON -> {
                    Button(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                        Text("Link this button")
                    }
                    Text(
                        "Once linked, an SOS is sent the moment the button leaves its idle slot.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> Text("Observed only (cannot be linked)", style = MaterialTheme.typography.bodySmall)
            }
        }
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
        "Signed in as ${user.email ?: user.uid}"
    }.getOrElse { error ->
        "Sign-in failed: ${error.message ?: "unknown error"}"
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
        error("Location permission is required")
    }

    val location = LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(
            if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            CancellationTokenSource().token,
        )
        .await()
        ?: error("Could not get a location fix")
    val capturedAt = Instant.now().toString()
    val address = reverseGeocode(context, location.latitude, location.longitude)
    val signals = readDeviceSignals(context)
    return LocationSnapshot(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = location.accuracy,
        capturedAt = capturedAt,
        address = address,
        geocodedAt = address?.let { Instant.now().toString() },
        batteryPercent = signals.batteryPercent,
        batteryCharging = signals.batteryCharging,
        motionState = signals.motionState,
        motionPeakG = signals.motionPeakG,
    )
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

private fun hasMicrophonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
private suspend fun reverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? = withContext(Dispatchers.IO) {
    // Hackathon privacy policy: only the prefecture leaves the device. Never
    // send/persist a full street-level address (getAddressLine would include it).
    runCatching {
        Geocoder(context, Locale.JAPAN)
            .getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
            ?.adminArea
    }.getOrNull()
}

// Coordinates stay on the device; only this prefecture-level text is ever shared.
private fun LocationSnapshot.displayText(): String = buildString {
    append(address ?: "Address unknown")
    append("\nAccuracy +/-%.0f m".format(accuracyMeters))
    batteryPercent?.let { percent ->
        append("\nBattery $percent%")
        if (batteryCharging == true) append(" (charging)")
    }
    when (motionState) {
        MotionMonitor.STATE_SHAKING -> append("\nBeing shaken hard")
        MotionMonitor.STATE_MOVING -> append("\nMoving")
    }
}

@Composable
private fun ConferenceSosSection(
    enabled: Boolean,
    join: Pair<String, String>?,
    onStart: () -> Unit,
    onCallAi: (Pair<String, String>) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Experimental: conference SOS", style = MaterialTheme.typography.titleMedium)
            val autoStatus by ConferenceSosOrchestrator.status.collectAsStateWithLifecycle()
            autoStatus?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Instead of LIFELiNK calling your contact, you call the AI yourself, then use " +
                    "\"Add call\" for your contact and \"Merge\". Your Discord members still get a DM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (join == null) {
                Button(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onStart) {
                    Text("Start conference SOS")
                }
            } else {
                Text(
                    "Join code: ${join.second} (valid for 10 minutes)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Button(modifier = Modifier.fillMaxWidth(), onClick = { onCallAi(join) }) {
                    Text("Call the AI")
                }
                Text(
                    "The code is typed automatically after the AI answers. If not, enter it on the keypad.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhoneAppSection() {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(isDefaultPhoneApp(context)) }
    val requestRole = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefault = isDefaultPhoneApp(context)
    }
    Text("Phone app (experimental)", style = MaterialTheme.typography.titleLarge)
    Text(
        "Make LIFELiNK your phone app so a conference SOS can call the AI and your contact and " +
            "merge them for you. LIFELiNK then also handles your normal calls. Emergency numbers " +
            "always use the system phone app. You can switch back any time.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        if (isDefault) "LIFELiNK is your phone app" else "LIFELiNK is not your phone app",
        color = if (isDefault) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (!isDefault && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                requestRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            } else {
                requestRole.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        },
    ) {
        Text(if (isDefault) "Change the phone app" else "Make LIFELiNK the phone app")
    }
}

fun isDefaultPhoneApp(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
    } else {
        context.getSystemService(TelecomManager::class.java).defaultDialerPackage == context.packageName
    }

private fun callStateText(state: String): String = when (state) {
    "accepted" -> "SOS accepted"
    "dialing" -> "Calling your emergency contact"
    "in_progress" -> "On the call"
    "completed" -> "Call ended"
    "failed" -> "The call failed"
    else -> state
}

private fun Throwable.userMessage(): String = when (this) {
    is ApiException -> when (errorCode) {
        "world_id_verification_required" -> "Verify with World ID first"
        "contact_not_found" -> "That emergency contact no longer exists"
        else -> errorCode
    }
    else -> message ?: "unknown error"
}

private fun openWorldIdConnector(context: Context, connectorUri: String) {
    val uri = Uri.parse(connectorUri)
    val packageManager = context.packageManager
    val worldPackages = listOf("org.world.id", "com.worldcoin")
    val worldIntent = worldPackages
        .asSequence()
        .map { packageName ->
            Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
        }
        .firstOrNull { intent -> intent.resolveActivity(packageManager) != null }
    context.startActivity(worldIntent ?: Intent(Intent.ACTION_VIEW, uri))
}

private enum class AppTab(val label: String) {
    HOME("Home"),
    MEMBERS("Members"),
    SETTINGS("Settings"),
}

private const val SOS_TAP_COUNT = 3
private const val SOS_TAP_WINDOW_MS = 1_500L
private const val SOS_HOLD_MS = 2_000L
private const val LOCATION_INTERVAL_IDLE_MS = 60_000L
private const val LOCATION_INTERVAL_ACTIVE_MS = 10_000L
private const val LOCATION_FIX_TIMEOUT_MS = 20_000L
private val SOS_ARMED_COLOR = Color(0xFF7A0F0A)
private const val EVENT_STATUS_POLL_INTERVAL_MS = 2_000L
private const val WORLD_ID_STATUS_POLL_INTERVAL_MS = 2_000L
private val TERMINAL_EVENT_STATES = setOf("completed", "failed")
private val LAST_SEEN_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
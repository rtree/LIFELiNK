package com.rtree.LIFELiNK

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BeaconTriggerManager {
    const val ACTION_BEACON_RESULT = "com.rtree.LIFELiNK.BEACON_RESULT"
    private var foregroundScanner: BluetoothLeScanner? = null
    private var foregroundCallback: ScanCallback? = null

    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun start(context: Context): Int {
        check(hasPermission(context)) { "Bluetooth scan permission is required" }
        val scanner = context.getSystemService(BluetoothManager::class.java)
            .adapter
            ?.bluetoothLeScanner
            ?: error("Bluetooth scanner is unavailable")
        val filters = buildFilters(context)
        if (filters.isNotEmpty()) {
            val result = scanner.startScan(
                filters,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build(),
                pendingIntent(context),
            )
            AdvertisementRegistry.appendBeaconLog(
                "Scan started: PendingIntent LOW_LATENCY filter=${filters.size} result=$result " +
                    "linkedSlots=${linkedSlots(context).joinToString { it.label }}",
            )
        } else {
            scanner.stopScan(pendingIntent(context))
            AdvertisementRegistry.appendBeaconLog("Scan started: no linked Beacon, alert trigger disabled (observation only)")
        }
        startForegroundCallback(context, scanner)
        return filters.size
    }

    fun stop(context: Context) {
        if (!hasPermission(context)) return
        val scanner = context.getSystemService(BluetoothManager::class.java)
            .adapter
            ?.bluetoothLeScanner
        scanner?.stopScan(pendingIntent(context))
        foregroundCallback?.let { callback -> scanner?.stopScan(callback) }
        foregroundCallback = null
        foregroundScanner = null
    }

    private fun startForegroundCallback(
        context: Context,
        scanner: BluetoothLeScanner,
    ) {
        foregroundCallback?.let(scanner::stopScan)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                AdvertisementRegistry.observe(context, result)
                if (!isLinkedAdvertisement(context, result)) return
                context.sendBroadcast(
                    Intent(context, BeaconReceiver::class.java)
                        .setAction(ACTION_BEACON_RESULT)
                        .putExtra(EXTRA_PATH, PATH_FOREGROUND)
                        .putParcelableArrayListExtra(
                            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                            arrayListOf(result),
                        ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                AdvertisementRegistry.appendBeaconLog("Foreground scan failed: errorCode=$errorCode")
            }
        }
        foregroundScanner = scanner
        foregroundCallback = callback
        scanner.startScan(
            emptyList(),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            callback,
        )
    }

    // Linked slots in any state (bit14 long press / bit15 battery ignored) so the receiver can see state transitions.
    private fun buildFilters(context: Context): List<ScanFilter> =
        linkedSlots(context).map { slot ->
            val data = byteArrayOf(0x02, 0x15) +
                uuidBytes(slot.uuid) +
                byteArrayOf(
                    (slot.major shr 8).toByte(),
                    slot.major.toByte(),
                    (slot.minor shr 8).toByte(),
                    slot.minor.toByte(),
                )
            val mask = ByteArray(data.size) { 0xff.toByte() }.also { it[18] = 0x3f }
            ScanFilter.Builder()
                .setManufacturerData(APPLE_COMPANY_ID, data, mask)
                .build()
        }

    private fun linkedSlots(context: Context): List<BeaconSlot> =
        EmergencyPreferences(context).linkedTriggerDevice
            ?.takeIf { it.transport == TriggerTransport.BEACON }
            ?.beaconSlots
            .orEmpty()

    fun isLinkedAdvertisement(context: Context, result: ScanResult): Boolean {
        val identity = AdvertisementRegistry.parseIBeacon(result) ?: return false
        return identity.slot in linkedSlots(context)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, BeaconReceiver::class.java).setAction(ACTION_BEACON_RESULT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun uuidBytes(uuid: String): ByteArray = uuid
        .replace("-", "")
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    const val EXTRA_PATH = "com.rtree.LIFELiNK.BEACON_PATH"
    const val PATH_FOREGROUND = "foreground"
    const val PATH_PENDING_INTENT = "pending_intent"
    private const val APPLE_COMPANY_ID = 0x004c
}

class BeaconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BeaconTriggerManager.ACTION_BEACON_RESULT) return
        val path = intent.getStringExtra(BeaconTriggerManager.EXTRA_PATH)
            ?: BeaconTriggerManager.PATH_PENDING_INTENT
        val matched = scanResults(intent)
            .filter { BeaconTriggerManager.isLinkedAdvertisement(context, it) }
        if (matched.isEmpty()) return
        val fresh = matched.filter {
            AdvertisementRegistry.packetAgeMillis(it) <= MAX_ADVERTISEMENT_AGE_MS
        }
        if (fresh.isEmpty()) {
            AdvertisementRegistry.appendBeaconLog(
                "Dropped (stale) [$path] newestDelay=${matched.minOf(AdvertisementRegistry::packetAgeMillis)}ms " +
                    "count=${matched.size} ${AdvertisementRegistry.screenState(context)}",
            )
            return
        }
        val safetyGate = EmergencySafetyGate(context)
        val preferences = EmergencyPreferences(context)
        val idleSlot = preferences.linkedTriggerDevice?.beaconIdleSlot
        if (idleSlot == null) {
            Log.w(LOG_TAG, "Ignored Beacon: linked device has no idle slot, relink required")
            return
        }
        val burstSeconds = preferences.beaconBurstSeconds
        var pressed: Pair<ScanResult, String>? = null
        fresh.sortedBy { it.timestampNanos }.forEach { packet ->
            val identity = AdvertisementRegistry.parseIBeacon(packet) ?: return@forEach
            AdvertisementRegistry.noteLinkedPacket(context, AdvertisementRegistry.packetWallMillis(packet))
            val reason = safetyGate.observeBeaconState(
                stateKey = "${identity.slot.label}|${identity.longPress}",
                pressed = identity.slot != idleSlot,
                packetAtMillis = AdvertisementRegistry.packetWallMillis(packet),
                burstSeconds = burstSeconds,
            )
            Log.i(
                LOG_TAG,
                "linked pkt path=$path state=${identity.slot.major}${if (identity.longPress) "/long" else ""} " +
                    "age=${AdvertisementRegistry.packetAgeMillis(packet)}ms rssi=${packet.rssi} event=${reason ?: "none"}",
            )
            if (reason != null) pressed = packet to reason
        }
        val (packet, reason) = pressed ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dispatchBeacon(context.applicationContext, path, packet, reason)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatchBeacon(context: Context, path: String, packet: ScanResult, reason: String) {
        val safetyGate = EmergencySafetyGate(context)
        val packetAt = AdvertisementRegistry.packetWallMillis(packet)
        val identity = AdvertisementRegistry.parseIBeacon(packet)
        AdvertisementRegistry.appendBeaconLog(
            "Press accepted($reason) [$path] [${AdvertisementRegistry.deviceLabel(runCatching { packet.device.address }.getOrNull())}] " +
                "${identity?.slot?.label} pkt=${formatLogTime(packetAt)} " +
                "delay=${AdvertisementRegistry.packetAgeMillis(packet)}ms RSSI=${packet.rssi} " +
                AdvertisementRegistry.screenState(context),
        )
        val preferences = EmergencyPreferences(context)
        if (preferences.beaconDryRun) {
            AdvertisementRegistry.appendBeaconLog("Dry run: not sending (production would call the API here)")
            return
        }
        val contactId = preferences.contactId
        if (contactId == null) {
            AdvertisementRegistry.appendBeaconLog("Alert aborted: no emergency contact registered")
            return
        }
        val apiClient = LifeLinkApiClient()

        var attempt = safetyGate.begin(contactId)
        if (attempt.isRetry) {
            val existing = runCatching {
                apiClient.getEmergencyEvent(attempt.eventId)
            }.getOrNull()
            if (existing?.state !in TERMINAL_EVENT_STATES) {
                AdvertisementRegistry.appendBeaconLog("Alert aborted: event already in progress state=${existing?.state}")
                return
            }
            safetyGate.clear(attempt.eventId)
            attempt = safetyGate.begin(contactId)
        }

        val requestStartedAt = System.currentTimeMillis()
        val wantsAmbient = preferences.beaconSosMode == EmergencyPreferences.SOS_V2_AMBIENT
        val canConference = isDefaultPhoneApp(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        // V2 needs the phone app role to place and merge calls; otherwise keep the proven V1 path.
        val mode = if (wantsAmbient && canConference) {
            LifeLinkApiClient.MODE_CARRIER_CONFERENCE
        } else {
            LifeLinkApiClient.MODE_OUTBOUND
        }
        if (wantsAmbient && !canConference) {
            AdvertisementRegistry.appendBeaconLog("SOSV2 needs LIFELiNK as the phone app; using SOSV1")
        }
        AdvertisementRegistry.appendBeaconLog("API request started mode=$mode (${requestStartedAt - packetAt}ms after press packet)")
        runCatching {
            apiClient.createEmergencyEvent(
                eventId = attempt.eventId,
                contactId = contactId,
                trigger = BleEmergencyTrigger,
                location = preferences.location,
                initialNote = null,
                mode = mode,
            )
        }.onSuccess { result ->
            val now = System.currentTimeMillis()
            AdvertisementRegistry.appendBeaconLog(
                "API response state=${result.state} apiTook=${now - requestStartedAt}ms ${now - packetAt}ms after press packet",
            )
            val aiNumber = result.aiNumber
            val joinCode = result.joinCode
            val contactPhone = result.contactPhone
            if (aiNumber != null && joinCode != null && contactPhone != null) {
                val started = withContext(Dispatchers.Main) {
                    ConferenceSosOrchestrator.start(
                        context = context,
                        apiClient = apiClient,
                        eventId = result.eventId,
                        aiNumber = aiNumber,
                        joinCode = joinCode,
                        contactPhone = contactPhone,
                    )
                }
                AdvertisementRegistry.appendBeaconLog("SOSV2 conference ${if (started) "started" else "could not start"}")
            }
        }.onFailure { error ->
            if (error is ApiException && error.statusCode in 400..499) {
                safetyGate.clear(attempt.eventId)
            }
            val errorCode = (error as? ApiException)?.errorCode ?: error::class.simpleName
            AdvertisementRegistry.appendBeaconLog(
                "API failed $errorCode apiTook=${System.currentTimeMillis() - requestStartedAt}ms",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun scanResults(intent: Intent): List<ScanResult> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            ).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<ScanResult>(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            ).orEmpty()
        }

    private companion object {
        const val LOG_TAG = "LIFELiNK.Beacon"
        const val MAX_ADVERTISEMENT_AGE_MS = 10_000L
        val TERMINAL_EVENT_STATES = setOf("completed", "failed")
    }
}
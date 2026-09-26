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
        val result = scanner.startScan(
            buildFilters(context),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build(),
            pendingIntent(context),
        )
        startForegroundCallback(context, scanner)
        Log.i("LIFELiNK.Beacon", "Beacon scans started with result=$result")
        return result
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
                AdvertisementRegistry.observe(result)
                if (!isTriggerAdvertisement(context, result)) return
                Log.i("LIFELiNK.Beacon", "Foreground long-press Beacon match received")
                context.sendBroadcast(
                    Intent(context, BeaconReceiver::class.java)
                        .setAction(ACTION_BEACON_RESULT)
                        .putParcelableArrayListExtra(
                            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                            arrayListOf(result),
                        ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("LIFELiNK.Beacon", "Foreground Beacon scan failed: $errorCode")
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

    fun diagnoseNextIBeacon(
        context: Context,
        onResult: (String) -> Unit,
    ) {
        check(BuildConfig.DEBUG) { "Beacon diagnostics are debug-only" }
        check(hasPermission(context)) { "Bluetooth scan permission is required" }
        val scanner = context.getSystemService(BluetoothManager::class.java)
            .adapter
            ?.bluetoothLeScanner
            ?: error("Bluetooth scanner is unavailable")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord
                    ?.getManufacturerSpecificData(APPLE_COMPANY_ID)
                    ?: return
                val identity = parseIBeaconIdentity(data) ?: return
                scanner.stopScan(this)
                Log.i("LIFELiNK.Beacon", "Observed iBeacon identity: $identity")
                onResult(identity)
            }
        }
        scanner.startScan(
            listOf(
                ScanFilter.Builder()
                    .setManufacturerData(
                        APPLE_COMPANY_ID,
                        byteArrayOf(0x02, 0x15),
                        byteArrayOf(0xff.toByte(), 0xff.toByte()),
                    )
                    .build(),
            ),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            callback,
        )
    }

    private fun buildFilters(context: Context): List<ScanFilter> {
        val linkedAddress = linkedBeaconAddress(context)
        if (linkedAddress != null) {
            // Any slot of the linked device, as long as the long-press bit is set.
            val data = ByteArray(22).also {
                it[0] = 0x02
                it[1] = 0x15
                it[18] = 0x40
            }
            val mask = ByteArray(22).also {
                it[0] = 0xff.toByte()
                it[1] = 0xff.toByte()
                it[18] = 0x40
            }
            return listOf(
                ScanFilter.Builder()
                    .setDeviceAddress(linkedAddress)
                    .setManufacturerData(APPLE_COMPANY_ID, data, mask)
                    .build(),
            )
        }
        return DEFAULT_TRIGGER_SLOTS.map { slot ->
            val major = slot.major or 0x4000
            val data = byteArrayOf(0x02, 0x15) +
                uuidBytes(slot.uuid) +
                byteArrayOf(
                    (major shr 8).toByte(),
                    major.toByte(),
                    (slot.minor shr 8).toByte(),
                    slot.minor.toByte(),
                )
            val mask = ByteArray(data.size) { 0xff.toByte() }.also { it[18] = 0x7f }
            ScanFilter.Builder()
                .setManufacturerData(APPLE_COMPANY_ID, data, mask)
                .build()
        }
    }

    private fun linkedBeaconAddress(context: Context): String? =
        EmergencyPreferences(context).linkedTriggerDevice
            ?.takeIf { it.transport == TriggerTransport.BEACON }
            ?.deviceAddress

    fun isTriggerAdvertisement(context: Context, result: ScanResult): Boolean {
        val identity = AdvertisementRegistry.parseIBeacon(result) ?: return false
        if (!identity.longPress) return false
        val linkedAddress = linkedBeaconAddress(context)
        if (linkedAddress != null) {
            return runCatching { result.device.address }.getOrNull() == linkedAddress
        }
        return DEFAULT_TRIGGER_SLOTS.any { slot ->
            slot.uuid == identity.uuid && slot.major == identity.major && slot.minor == identity.minor
        }
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

    private fun parseIBeaconIdentity(data: ByteArray): String? {
        if (data.size < 22 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) {
            return null
        }
        val uuidHex = data.copyOfRange(2, 18).joinToString("") { byte ->
            "%02X".format(byte.toInt() and 0xff)
        }
        val uuid = "${uuidHex.substring(0, 8)}-${uuidHex.substring(8, 12)}-" +
            "${uuidHex.substring(12, 16)}-${uuidHex.substring(16, 20)}-" +
            uuidHex.substring(20)
        val major = ((data[18].toInt() and 0xff) shl 8) or (data[19].toInt() and 0xff)
        val minor = ((data[20].toInt() and 0xff) shl 8) or (data[21].toInt() and 0xff)
        return "$uuid / $major / $minor"
    }

    private data class TriggerSlot(val uuid: String, val major: Int, val minor: Int)

    private const val APPLE_COMPANY_ID = 0x004c

    // Beacon0 (BB192440-.../11665/31295) is the idle advertisement and must never trigger.
    private val DEFAULT_TRIGGER_SLOTS = listOf(
        TriggerSlot("581E31D6-E7BA-407A-B12E-949ACE475485", 7290, 36652),
        TriggerSlot("AA82CE42-BFC7-4182-B760-1CCA10116876", 12975, 16823),
    )
}

class BeaconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BeaconTriggerManager.ACTION_BEACON_RESULT) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val results = scanResults(intent)
            .filter { nowNanos - it.timestampNanos <= MAX_ADVERTISEMENT_AGE_NANOS }
            .filter { BeaconTriggerManager.isTriggerAdvertisement(context, it) }
        if (results.isEmpty()) return
        Log.i(LOG_TAG, "Long-press Beacon advertisement received")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dispatchBeacon(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatchBeacon(context: Context) {
        val safetyGate = EmergencySafetyGate(context)
        if (!safetyGate.acceptBeaconBurst()) {
            Log.i(LOG_TAG, "Ignored duplicate Beacon advertisement burst")
            return
        }
        val preferences = EmergencyPreferences(context)
        if (preferences.beaconDryRun) {
            Log.i(LOG_TAG, "Dry-run: linked Beacon burst accepted, emergency call skipped")
            AdvertisementRegistry.appendBeaconLog("ドライラン: 長押しを検知（本番なら発信候補、発信せず）")
            return
        }
        val contactId = preferences.contactId
        if (contactId == null) {
            Log.w(LOG_TAG, "Ignored Beacon because no emergency contact is registered")
            return
        }
        val apiClient = LifeLinkApiClient()

        var attempt = safetyGate.begin(contactId)
        if (attempt.isRetry) {
            val existing = runCatching {
                apiClient.getEmergencyEvent(attempt.eventId)
            }.getOrNull()
            if (existing?.state !in TERMINAL_EVENT_STATES) {
                Log.i(LOG_TAG, "Ignored Beacon while another emergency event is active")
                return
            }
            safetyGate.clear(attempt.eventId)
            attempt = safetyGate.begin(contactId)
        }

        runCatching {
            apiClient.createEmergencyEvent(
                eventId = attempt.eventId,
                contactId = contactId,
                trigger = BleEmergencyTrigger,
                location = preferences.location,
                initialNote = null,
            )
        }.onSuccess { result ->
            Log.i(LOG_TAG, "Beacon emergency event accepted with state=${result.state}")
        }.onFailure { error ->
            if (error is ApiException && error.statusCode in 400..499) {
                safetyGate.clear(attempt.eventId)
            }
            val errorCode = (error as? ApiException)?.errorCode ?: error::class.simpleName
            Log.e(LOG_TAG, "Beacon emergency event failed: $errorCode")
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

        const val MAX_ADVERTISEMENT_AGE_NANOS = 10_000_000_000L
    private companion object {
        const val LOG_TAG = "LIFELiNK.Beacon"
        val TERMINAL_EVENT_STATES = setOf("completed", "failed")
    }
}
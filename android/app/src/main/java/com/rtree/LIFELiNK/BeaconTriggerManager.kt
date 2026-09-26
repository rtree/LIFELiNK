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
                val observation = AdvertisementRegistry.observe(result)
                if (!matchesLinkedBeacon(context, observation)) return
                Log.i("LIFELiNK.Beacon", "Foreground linked Beacon match received")
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
        val linked = EmergencyPreferences(context).linkedTriggerDevice
        val uuid = linked
            ?.takeIf { it.transport == TriggerTransport.BEACON }
            ?.beaconUuid
            ?: BEACON_UUID
        val major = (linked?.beaconMajor ?: BEACON_MAJOR) and 0x7fff
        val minor = linked?.beaconMinor ?: BEACON_MINOR
        return listOf(major, major or 0x8000).map { advertisedMajor ->
            buildFilter(linked, uuid, advertisedMajor, minor)
        }
    }

    private fun buildFilter(
        linked: LinkedTriggerDevice?,
        uuid: String,
        major: Int,
        minor: Int,
    ): ScanFilter {
        val manufacturerData = byteArrayOf(0x02, 0x15) +
            uuidBytes(uuid) +
            byteArrayOf(
                (major shr 8).toByte(),
                major.toByte(),
                (minor shr 8).toByte(),
                minor.toByte(),
            )
        return ScanFilter.Builder()
            .apply { linked?.deviceAddress?.let(::setDeviceAddress) }
            .setManufacturerData(
                APPLE_COMPANY_ID,
                manufacturerData,
                ByteArray(manufacturerData.size) { 0xff.toByte() },
            )
            .build()
    }

    private fun matchesLinkedBeacon(
        context: Context,
        observation: AdvertisementObservation,
    ): Boolean {
        if (observation.kind != AdvertisementKind.IBEACON) return false
        val linked = EmergencyPreferences(context).linkedTriggerDevice
        if (linked != null && linked.transport == TriggerTransport.BEACON) {
            return observation.deviceAddress == linked.deviceAddress &&
                observation.beaconUuid == linked.beaconUuid &&
                observation.beaconMajor == linked.beaconMajor?.and(0x7fff) &&
                observation.beaconMinor == linked.beaconMinor
        }
        return observation.beaconUuid == BEACON_UUID &&
            observation.beaconMajor == BEACON_MAJOR &&
            observation.beaconMinor == BEACON_MINOR
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

    private const val APPLE_COMPANY_ID = 0x004c
    private const val BEACON_UUID = "BB192440-9E4F-497D-8ACE-7B2BA67CC2FE"
    private const val BEACON_MAJOR = 11665
    private const val BEACON_MINOR = 31295
}

class BeaconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BeaconTriggerManager.ACTION_BEACON_RESULT) return
        val results = scanResults(intent)
        if (results.isEmpty()) return
        Log.i(LOG_TAG, "Matched Beacon advertisement received")

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
            AdvertisementRegistry.appendBeaconLog("ドライラン: リンク済みBeaconの新規バースト（本番なら発信候補、発信せず）")
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

    private companion object {
        const val LOG_TAG = "LIFELiNK.Beacon"
        val TERMINAL_EVENT_STATES = setOf("completed", "failed")
    }
}
package com.rtree.LIFELiNK

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object BeaconTriggerManager {
    const val ACTION_BEACON_RESULT = "com.rtree.LIFELiNK.BEACON_RESULT"

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
        return scanner.startScan(
            listOf(buildFilter()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build(),
            pendingIntent(context),
        )
    }

    fun stop(context: Context) {
        if (!hasPermission(context)) return
        context.getSystemService(BluetoothManager::class.java)
            .adapter
            ?.bluetoothLeScanner
            ?.stopScan(pendingIntent(context))
    }

    private fun buildFilter(): ScanFilter {
        val manufacturerData = byteArrayOf(0x02, 0x15) +
            uuidBytes(BEACON_UUID) +
            byteArrayOf(
                (BEACON_MAJOR shr 8).toByte(),
                BEACON_MAJOR.toByte(),
                (BEACON_MINOR shr 8).toByte(),
                BEACON_MINOR.toByte(),
            )
        return ScanFilter.Builder()
            .setManufacturerData(
                APPLE_COMPANY_ID,
                manufacturerData,
                ByteArray(manufacturerData.size) { 0xff.toByte() },
            )
            .build()
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

    private const val APPLE_COMPANY_ID = 0x004c
    private const val BEACON_UUID = "D93DBA9A-40E6-4C73-A0DA-BF416BFE0DBF"
    private const val BEACON_MAJOR = 1
    private const val BEACON_MINOR = 1
}

class BeaconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BeaconTriggerManager.ACTION_BEACON_RESULT) return
        val results = scanResults(intent)
        if (results.isEmpty()) return

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
        if (!safetyGate.acceptBeaconBurst()) return
        val preferences = EmergencyPreferences(context)
        val contactId = preferences.contactId ?: return
        val apiClient = LifeLinkApiClient()

        var attempt = safetyGate.begin(contactId)
        if (attempt.isRetry) {
            val existing = runCatching {
                apiClient.getEmergencyEvent(attempt.eventId)
            }.getOrNull()
            if (existing?.state !in TERMINAL_EVENT_STATES) return
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
        }.onFailure { error ->
            if (error is ApiException && error.statusCode in 400..499) {
                safetyGate.clear(attempt.eventId)
            }
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
        val TERMINAL_EVENT_STATES = setOf("completed", "failed")
    }
}
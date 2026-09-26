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
                "スキャン開始: PendingIntent LOW_LATENCY filter=${filters.size} result=$result " +
                    "リンクスロット=${linkedSlots(context).joinToString { it.label }}",
            )
        } else {
            scanner.stopScan(pendingIntent(context))
            AdvertisementRegistry.appendBeaconLog("スキャン開始: リンク済みBeaconが無いため発信トリガーは無効（観測のみ）")
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
                if (!isTriggerAdvertisement(context, result)) return
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
                AdvertisementRegistry.appendBeaconLog("前面スキャン失敗: errorCode=$errorCode")
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

    // Each linked slot matches only when Major bit14 (long press) is set; bit15 (battery low) is ignored.
    private fun buildFilters(context: Context): List<ScanFilter> =
        linkedSlots(context).map { slot ->
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

    private fun linkedSlots(context: Context): List<BeaconSlot> =
        EmergencyPreferences(context).linkedTriggerDevice
            ?.takeIf { it.transport == TriggerTransport.BEACON }
            ?.beaconSlots
            .orEmpty()

    fun isTriggerAdvertisement(context: Context, result: ScanResult): Boolean {
        val identity = AdvertisementRegistry.parseIBeacon(result) ?: return false
        return identity.longPress && identity.slot in linkedSlots(context)
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
            .filter { BeaconTriggerManager.isTriggerAdvertisement(context, it) }
        if (matched.isEmpty()) return
        val fresh = matched.filter {
            AdvertisementRegistry.packetAgeMillis(it) <= MAX_ADVERTISEMENT_AGE_MS
        }
        if (fresh.isEmpty()) {
            AdvertisementRegistry.appendBeaconLog(
                "長押し破棄(古い) [$path] 最新遅延=${matched.minOf(AdvertisementRegistry::packetAgeMillis)}ms " +
                    "件数=${matched.size} ${AdvertisementRegistry.screenState(context)}",
            )
            return
        }
        val packet = fresh.maxBy { it.timestampNanos }
        Log.i(
            LOG_TAG,
            "long-press pkt path=$path age=${AdvertisementRegistry.packetAgeMillis(packet)}ms rssi=${packet.rssi}",
        )

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dispatchBeacon(context.applicationContext, path, packet)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatchBeacon(context: Context, path: String, packet: ScanResult) {
        val safetyGate = EmergencySafetyGate(context)
        if (!safetyGate.acceptBeaconBurst()) return
        val packetAt = AdvertisementRegistry.packetWallMillis(packet)
        val identity = AdvertisementRegistry.parseIBeacon(packet)
        AdvertisementRegistry.appendBeaconLog(
            "長押し受理 [$path] [${AdvertisementRegistry.deviceLabel(runCatching { packet.device.address }.getOrNull())}] " +
                "${identity?.slot?.label} pkt=${formatLogTime(packetAt)} " +
                "受信遅延=${AdvertisementRegistry.packetAgeMillis(packet)}ms RSSI=${packet.rssi} " +
                AdvertisementRegistry.screenState(context),
        )
        val preferences = EmergencyPreferences(context)
        if (preferences.beaconDryRun) {
            AdvertisementRegistry.appendBeaconLog("ドライラン: 発信せず（本番ならここでAPI送信）")
            return
        }
        val contactId = preferences.contactId
        if (contactId == null) {
            AdvertisementRegistry.appendBeaconLog("発信中止: 緊急連絡先が未登録")
            return
        }
        val apiClient = LifeLinkApiClient()

        var attempt = safetyGate.begin(contactId)
        if (attempt.isRetry) {
            val existing = runCatching {
                apiClient.getEmergencyEvent(attempt.eventId)
            }.getOrNull()
            if (existing?.state !in TERMINAL_EVENT_STATES) {
                AdvertisementRegistry.appendBeaconLog("発信中止: 進行中のイベントあり state=${existing?.state}")
                return
            }
            safetyGate.clear(attempt.eventId)
            attempt = safetyGate.begin(contactId)
        }

        val requestStartedAt = System.currentTimeMillis()
        AdvertisementRegistry.appendBeaconLog("API送信開始 (押下パケットから${requestStartedAt - packetAt}ms)")
        runCatching {
            apiClient.createEmergencyEvent(
                eventId = attempt.eventId,
                contactId = contactId,
                trigger = BleEmergencyTrigger,
                location = preferences.location,
                initialNote = null,
            )
        }.onSuccess { result ->
            val now = System.currentTimeMillis()
            AdvertisementRegistry.appendBeaconLog(
                "API応答 state=${result.state} API所要=${now - requestStartedAt}ms 押下パケットから${now - packetAt}ms",
            )
        }.onFailure { error ->
            if (error is ApiException && error.statusCode in 400..499) {
                safetyGate.clear(attempt.eventId)
            }
            val errorCode = (error as? ApiException)?.errorCode ?: error::class.simpleName
            AdvertisementRegistry.appendBeaconLog(
                "API失敗 $errorCode API所要=${System.currentTimeMillis() - requestStartedAt}ms",
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
package com.rtree.LIFELiNK

import android.app.KeyguardManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AdvertisementKind(val label: String) {
    IBEACON("iBeacon (+Beacon button candidate)"),
    GATT_SERVICE("GATT (XIAO nRF52840)"),
}

enum class TriggerTransport(val label: String) {
    BEACON("Beacon"),
    GATT("GATT"),
}

data class BeaconSlot(
    val uuid: String,
    val major: Int,
    val minor: Int,
) {
    val label: String get() = "$uuid / $major / $minor"
}

data class AdvertisementObservation(
    val key: String,
    val kind: AdvertisementKind,
    val title: String,
    val detail: String,
    val rssi: Int,
    val lastSeenAtMillis: Long,
    val seenCount: Int,
    val suggestedTransport: TriggerTransport?,
    val deviceAddress: String?,
    val beaconSlot: BeaconSlot? = null,
    val beaconSlots: List<BeaconSlot> = emptyList(),
    val beaconSlotCounts: Map<BeaconSlot, Int> = emptyMap(),
    val beaconBatteryLow: Boolean? = null,
    val beaconLongPress: Boolean? = null,
    val gattServiceUuid: String? = null,
) {
    // The idle advertisement dominates packet counts because presses only override it briefly.
    val inferredIdleSlot: BeaconSlot? get() = beaconSlotCounts.maxByOrNull { it.value }?.key
}

// +Beacon Major: bit15 = battery low, bit14 = long press, bits0-13 = configured slot Major.
data class IBeaconIdentity(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val batteryLow: Boolean,
    val longPress: Boolean,
) {
    val slot: BeaconSlot get() = BeaconSlot(uuid, major, minor)
}

data class BeaconLogEntry(
    val atMillis: Long,
    val text: String,
)

object AdvertisementRegistry {
    private const val APPLE_COMPANY_ID = 0x004c
    private const val MAX_LOG_ENTRIES = 300
    private const val RECEPTION_GAP_LOG_MS = 3_000L
    private const val LOG_TAG = "LIFELiNK.BeaconLog"
    const val XIAO_MODEL_INFO = "Seeed XIAO nRF52840 / FCC ID Z4T-XIAONRF52840 / 技適 211-220207"

    private val mutableObservations = MutableStateFlow<List<AdvertisementObservation>>(emptyList())
    val observations = mutableObservations.asStateFlow()

    private val mutableBeaconLog = MutableStateFlow<List<BeaconLogEntry>>(emptyList())
    val beaconLog = mutableBeaconLog.asStateFlow()
    private val lastIBeaconStateByAddress = mutableMapOf<String, String>()
    private var lastLinkedPacketAtMillis = 0L

    // Logs when linked-button reception resumes after a silence; the idle slot advertises about every 1s.
    @Synchronized
    fun noteLinkedPacket(context: Context, packetAtMillis: Long) {
        val previous = lastLinkedPacketAtMillis
        if (packetAtMillis <= previous) return
        lastLinkedPacketAtMillis = packetAtMillis
        val gapMs = packetAtMillis - previous
        if (previous != 0L && gapMs >= RECEPTION_GAP_LOG_MS) {
            appendBeaconLog("Scan resumed after ${"%.1f".format(gapMs / 1000.0)}s ${screenState(context)}")
        }
    }

    @Synchronized
    fun appendBeaconLog(text: String, atMillis: Long = System.currentTimeMillis()) {
        Log.i(LOG_TAG, text)
        mutableBeaconLog.value = (listOf(BeaconLogEntry(atMillis, text)) + mutableBeaconLog.value)
            .take(MAX_LOG_ENTRIES)
    }

    @Synchronized
    fun clearBeaconLog() {
        mutableBeaconLog.value = emptyList()
        lastIBeaconStateByAddress.clear()
    }

    fun packetWallMillis(result: ScanResult): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000

    fun packetAgeMillis(result: ScanResult): Long =
        (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000

    fun deviceLabel(address: String?): String = address?.takeLast(5)?.replace(":", "") ?: "----"

    fun screenState(context: Context): String {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
        return "screen=${if (interactive == true) "ON" else "OFF"} lock=${if (locked == true) "locked" else "unlocked"}"
    }

    @Synchronized
    fun observe(context: Context, result: ScanResult): AdvertisementObservation? {
        val now = System.currentTimeMillis()
        val parsed = parse(result, now) ?: return null
        val previous = mutableObservations.value.firstOrNull { it.key == parsed.key }
        val slots = (previous?.beaconSlots.orEmpty() + listOfNotNull(parsed.beaconSlot)).distinct()
        val counts = previous?.beaconSlotCounts.orEmpty().toMutableMap()
        parsed.beaconSlot?.let { counts[it] = (counts[it] ?: 0) + 1 }
        val updated = parsed.copy(
            seenCount = (previous?.seenCount ?: 0) + 1,
            beaconSlots = slots,
            beaconSlotCounts = counts,
        )
        recordIBeaconTransition(context, result, updated)
        mutableObservations.value = (mutableObservations.value.filterNot { it.key == updated.key } + updated)
            .sortedWith(
                compareBy<AdvertisementObservation, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy { it.key },
            )
        return updated
    }

    // Records state changes per physical address so button operations can be mapped to slots and timed.
    private fun recordIBeaconTransition(
        context: Context,
        result: ScanResult,
        observation: AdvertisementObservation,
    ) {
        val slot = observation.beaconSlot ?: return
        val address = observation.deviceAddress ?: return
        val state = slot.label +
            (if (observation.beaconLongPress == true) " (long press)" else "") +
            if (observation.beaconBatteryLow == true) " (battery low)" else ""
        val previous = lastIBeaconStateByAddress.put(address, state)
        if (previous == state) return
        val timing = "pkt=${formatLogTime(packetWallMillis(result))} " +
            "delay=${packetAgeMillis(result)}ms RSSI=${result.rssi} ${screenState(context)}"
        appendBeaconLog(
            if (previous == null) {
                "[${deviceLabel(address)}] first: $state / $timing"
            } else {
                "[${deviceLabel(address)}] change: $previous -> $state / $timing"
            },
        )
    }

    private fun parse(result: ScanResult, now: Long): AdvertisementObservation? {
        val record = result.scanRecord ?: return null
        val deviceAddress = runCatching { result.device.address }.getOrNull()
        val label = deviceLabel(deviceAddress)
        val iBeacon = parseIBeacon(result)
        if (iBeacon != null) {
            return AdvertisementObservation(
                key = "ibeacon:${deviceAddress.orEmpty()}",
                kind = AdvertisementKind.IBEACON,
                title = "iBeacon • $label",
                detail = "Current: ${iBeacon.slot.label}",
                rssi = result.rssi,
                lastSeenAtMillis = now,
                seenCount = 0,
                suggestedTransport = TriggerTransport.BEACON,
                deviceAddress = deviceAddress,
                beaconSlot = iBeacon.slot,
                beaconBatteryLow = iBeacon.batteryLow,
                beaconLongPress = iBeacon.longPress,
            )
        }

        val name = record.deviceName.orEmpty()
        if (XIAO_NAME_MARKERS.any { name.contains(it, ignoreCase = true) }) {
            val serviceUuid = record.serviceUuids?.firstOrNull()?.uuid?.toString()?.uppercase()
            return AdvertisementObservation(
                key = "gatt:${deviceAddress.orEmpty()}",
                kind = AdvertisementKind.GATT_SERVICE,
                title = "$name • $label",
                detail = "$XIAO_MODEL_INFO\nService: ${serviceUuid ?: "not advertised"}",
                rssi = result.rssi,
                lastSeenAtMillis = now,
                seenCount = 0,
                suggestedTransport = TriggerTransport.GATT,
                deviceAddress = deviceAddress,
                gattServiceUuid = serviceUuid,
            )
        }
        return null
    }

    fun parseIBeacon(result: ScanResult): IBeaconIdentity? =
        result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID)?.let(::parseIBeacon)

    private fun parseIBeacon(data: ByteArray): IBeaconIdentity? {
        if (data.size < 22 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) {
            return null
        }
        val uuidHex = data.copyOfRange(2, 18).joinToString("") { byte ->
            "%02X".format(byte.toInt() and 0xff)
        }
        val uuid = "${uuidHex.substring(0, 8)}-${uuidHex.substring(8, 12)}-" +
            "${uuidHex.substring(12, 16)}-${uuidHex.substring(16, 20)}-" +
            uuidHex.substring(20)
        val rawMajor = ((data[18].toInt() and 0xff) shl 8) or (data[19].toInt() and 0xff)
        val minor = ((data[20].toInt() and 0xff) shl 8) or (data[21].toInt() and 0xff)
        return IBeaconIdentity(
            uuid = uuid,
            major = rawMajor and 0x3fff,
            minor = minor,
            batteryLow = rawMajor and 0x8000 != 0,
            longPress = rawMajor and 0x4000 != 0,
        )
    }

    private val XIAO_NAME_MARKERS = listOf("XIAO", "LIFELiNK")
}

val LOG_TIME_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(java.time.ZoneId.systemDefault())

fun formatLogTime(epochMillis: Long): String =
    LOG_TIME_FORMATTER.format(java.time.Instant.ofEpochMilli(epochMillis))

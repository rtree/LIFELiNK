package com.rtree.LIFELiNK

import android.bluetooth.le.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AdvertisementKind(val label: String) {
    IBEACON("iBeacon"),
    GATT_SERVICE("GATT Service"),
    MANUFACTURER("Manufacturer"),
    OTHER("Other"),
}

enum class TriggerTransport(val label: String) {
    BEACON("Beacon"),
    GATT("GATT"),
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
    val beaconUuid: String? = null,
    val beaconMajor: Int? = null,
    val beaconMinor: Int? = null,
    val beaconBatteryLow: Boolean? = null,
    val gattServiceUuid: String? = null,
)

private data class IBeaconIdentity(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val batteryLow: Boolean,
)

data class BeaconLogEntry(
    val atMillis: Long,
    val text: String,
)

object AdvertisementRegistry {
    private const val APPLE_COMPANY_ID = 0x004c
    private const val MAX_LOG_ENTRIES = 100

    private val mutableObservations = MutableStateFlow<List<AdvertisementObservation>>(emptyList())
    val observations = mutableObservations.asStateFlow()

    private val mutableBeaconLog = MutableStateFlow<List<BeaconLogEntry>>(emptyList())
    val beaconLog = mutableBeaconLog.asStateFlow()
    private val lastIBeaconIdentityByAddress = mutableMapOf<String, String>()

    @Synchronized
    fun appendBeaconLog(text: String, atMillis: Long = System.currentTimeMillis()) {
        mutableBeaconLog.value = (listOf(BeaconLogEntry(atMillis, text)) + mutableBeaconLog.value)
            .take(MAX_LOG_ENTRIES)
    }

    @Synchronized
    fun clearBeaconLog() {
        mutableBeaconLog.value = emptyList()
        lastIBeaconIdentityByAddress.clear()
    }

    @Synchronized
    fun observe(result: ScanResult): AdvertisementObservation {
        val now = System.currentTimeMillis()
        val parsed = parse(result, now)
        val previous = mutableObservations.value.firstOrNull { it.key == parsed.key }
        val updated = parsed.copy(seenCount = (previous?.seenCount ?: 0) + 1)
        recordIBeaconTransition(updated, now)
        mutableObservations.value = (mutableObservations.value.filterNot { it.key == updated.key } + updated)
            .sortedWith(
                compareBy<AdvertisementObservation, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy { it.key },
            )
        return updated
    }

    // Records identity changes per physical address so button operations can be mapped to UUID/Major/Minor states.
    private fun recordIBeaconTransition(observation: AdvertisementObservation, now: Long) {
        if (observation.kind != AdvertisementKind.IBEACON) return
        val address = observation.deviceAddress ?: return
        val identity = "${observation.beaconUuid} / ${observation.beaconMajor} / ${observation.beaconMinor}" +
            if (observation.beaconBatteryLow == true) " (電池低下)" else ""
        val previous = lastIBeaconIdentityByAddress.put(address, identity)
        if (previous == identity) return
        val label = address.takeLast(5).replace(":", "")
        appendBeaconLog(
            if (previous == null) "[$label] 初回: $identity" else "[$label] 変化: $previous → $identity",
            now,
        )
    }

    private fun parse(result: ScanResult, now: Long): AdvertisementObservation {
        val record = result.scanRecord
        val deviceAddress = runCatching { result.device.address }.getOrNull()
        val deviceLabel = deviceAddress?.takeLast(5)?.replace(":", "")?.let { " • $it" }.orEmpty()
        val appleData = record?.getManufacturerSpecificData(APPLE_COMPANY_ID)
        val iBeacon = appleData?.let(::parseIBeacon)
        if (iBeacon != null) {
            return AdvertisementObservation(
                key = "ibeacon:${iBeacon.uuid}:${iBeacon.major}:${iBeacon.minor}:${deviceAddress.orEmpty()}",
                kind = AdvertisementKind.IBEACON,
                title = "iBeacon ${iBeacon.major} / ${iBeacon.minor}$deviceLabel",
                detail = iBeacon.uuid,
                rssi = result.rssi,
                lastSeenAtMillis = now,
                seenCount = 0,
                suggestedTransport = TriggerTransport.BEACON,
                deviceAddress = deviceAddress,
                beaconUuid = iBeacon.uuid,
                beaconMajor = iBeacon.major,
                beaconMinor = iBeacon.minor,
                beaconBatteryLow = iBeacon.batteryLow,
            )
        }

        val serviceUuid = record?.serviceUuids?.firstOrNull()?.uuid?.toString()?.uppercase()
        if (serviceUuid != null) {
            return AdvertisementObservation(
                key = "service:$serviceUuid:${deviceAddress.orEmpty()}",
                kind = AdvertisementKind.GATT_SERVICE,
                title = record.deviceName?.takeIf(String::isNotBlank) ?: "GATT device",
                detail = serviceUuid,
                rssi = result.rssi,
                lastSeenAtMillis = now,
                seenCount = 0,
                suggestedTransport = TriggerTransport.GATT,
                deviceAddress = deviceAddress,
                gattServiceUuid = serviceUuid,
            )
        }

        val manufacturerData = record?.manufacturerSpecificData
        if (manufacturerData != null && manufacturerData.size() > 0) {
            val companyId = manufacturerData.keyAt(0)
            return AdvertisementObservation(
                key = "manufacturer:$companyId:${record.deviceName.orEmpty()}:${deviceAddress.orEmpty()}",
                kind = AdvertisementKind.MANUFACTURER,
                title = record.deviceName?.takeIf(String::isNotBlank) ?: "Manufacturer advertisement",
                detail = "Company ID 0x%04X".format(companyId),
                rssi = result.rssi,
                lastSeenAtMillis = now,
                seenCount = 0,
                suggestedTransport = null,
                deviceAddress = deviceAddress,
            )
        }

        val name = record?.deviceName?.takeIf(String::isNotBlank) ?: "Unknown advertisement"
        return AdvertisementObservation(
            key = "other:$name:${deviceAddress.orEmpty()}",
            kind = AdvertisementKind.OTHER,
            title = name,
            detail = "No linkable Beacon or GATT identity",
            rssi = result.rssi,
            lastSeenAtMillis = now,
            seenCount = 0,
            suggestedTransport = null,
            deviceAddress = deviceAddress,
        )
    }

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
            major = rawMajor and 0x7fff,
            minor = minor,
            batteryLow = rawMajor and 0x8000 != 0,
        )
    }
}
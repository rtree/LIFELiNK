package com.rtree.LIFELiNK

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LinkedTriggerDevice(
    val key: String,
    val transport: TriggerTransport,
    val title: String,
    val detail: String,
    val deviceAddress: String?,
    val beaconSlots: List<BeaconSlot>,
    val beaconIdleSlot: BeaconSlot?,
    val gattServiceUuid: String?,
)

class EmergencyPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(
        "lifelink_emergency_data",
        Context.MODE_PRIVATE,
    )

    var contactId: String?
        get() = preferences.getString(CONTACT_ID, null)
        set(value) {
            preferences.edit().putString(CONTACT_ID, value).apply()
        }

    var maskedContact: String?
        get() = preferences.getString(MASKED_CONTACT, null)
        set(value) {
            preferences.edit().putString(MASKED_CONTACT, value).apply()
        }

    var location: LocationSnapshot?
        get() = preferences.getString(LOCATION, null)?.let { encoded ->
            runCatching { LocationSnapshot.fromJson(JSONObject(encoded)) }.getOrNull()
        }
        set(value) {
            preferences.edit()
                .putString(LOCATION, value?.toJson()?.toString())
                .apply()
        }

    var worldIdFlowId: String?
        get() = preferences.getString(WORLD_ID_FLOW_ID, null)
        set(value) {
            preferences.edit().putString(WORLD_ID_FLOW_ID, value).apply()
        }

    var beaconDryRun: Boolean
        get() = preferences.getBoolean(BEACON_DRY_RUN, true)
        set(value) {
            preferences.edit().putBoolean(BEACON_DRY_RUN, value).apply()
        }

    // Must match the maker app's advertising duration (10s or 60s) on the physical button.
    var beaconBurstSeconds: Int
        get() = preferences.getInt(BEACON_BURST_SECONDS, 60)
        set(value) {
            preferences.edit().putInt(BEACON_BURST_SECONDS, value).apply()
        }

    var linkedTriggerDevice: LinkedTriggerDevice?
        get() = preferences.getString(LINKED_TRIGGER_DEVICE, null)?.let { encoded ->
            runCatching {
                val json = JSONObject(encoded)
                LinkedTriggerDevice(
                    key = json.getString("key"),
                    transport = TriggerTransport.valueOf(json.getString("transport")),
                    title = json.getString("title"),
                    detail = json.getString("detail"),
                    deviceAddress = json.optString("device_address").takeIf(String::isNotBlank),
                    beaconSlots = json.optJSONArray("beacon_slots")?.let { slots ->
                        (0 until slots.length()).map { index ->
                            val slot = slots.getJSONObject(index)
                            BeaconSlot(slot.getString("uuid"), slot.getInt("major"), slot.getInt("minor"))
                        }
                    }.orEmpty(),
                    beaconIdleSlot = json.optJSONObject("beacon_idle_slot")?.let { slot ->
                        BeaconSlot(slot.getString("uuid"), slot.getInt("major"), slot.getInt("minor"))
                    },
                    gattServiceUuid = json.optString("gatt_service_uuid").takeIf(String::isNotBlank),
                )
            }.getOrNull()
        }
        set(value) {
            val encoded = value?.let { device ->
                JSONObject()
                    .put("key", device.key)
                    .put("transport", device.transport.name)
                    .put("title", device.title)
                    .put("detail", device.detail)
                    .put("device_address", device.deviceAddress.orEmpty())
                    .put(
                        "beacon_slots",
                        JSONArray(
                            device.beaconSlots.map { slot ->
                                JSONObject()
                                    .put("uuid", slot.uuid)
                                    .put("major", slot.major)
                                    .put("minor", slot.minor)
                            },
                        ),
                    )
                    .apply {
                        device.beaconIdleSlot?.let { slot ->
                            put(
                                "beacon_idle_slot",
                                JSONObject()
                                    .put("uuid", slot.uuid)
                                    .put("major", slot.major)
                                    .put("minor", slot.minor),
                            )
                        }
                    }
                    .put("gatt_service_uuid", device.gattServiceUuid.orEmpty())
                    .toString()
            }
            preferences.edit().putString(LINKED_TRIGGER_DEVICE, encoded).apply()
        }

    fun linkTrigger(observation: AdvertisementObservation) {
        val transport = observation.suggestedTransport ?: return
        linkedTriggerDevice = LinkedTriggerDevice(
            key = observation.key,
            transport = transport,
            title = observation.title,
            detail = observation.detail,
            deviceAddress = observation.deviceAddress,
            beaconSlots = observation.beaconSlots,
            beaconIdleSlot = observation.inferredIdleSlot,
            gattServiceUuid = observation.gattServiceUuid,
        )
    }

    private companion object {
        const val CONTACT_ID = "contact_id"
        const val MASKED_CONTACT = "masked_contact"
        const val LOCATION = "location"
        const val WORLD_ID_FLOW_ID = "world_id_flow_id"
        const val LINKED_TRIGGER_DEVICE = "linked_trigger_device"
        const val BEACON_DRY_RUN = "beacon_dry_run"
        const val BEACON_BURST_SECONDS = "beacon_burst_seconds"
    }
}
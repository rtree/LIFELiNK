package com.rtree.LIFELiNK

import android.content.Context
import org.json.JSONObject

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

    private companion object {
        const val CONTACT_ID = "contact_id"
        const val MASKED_CONTACT = "masked_contact"
        const val LOCATION = "location"
        const val WORLD_ID_FLOW_ID = "world_id_flow_id"
    }
}
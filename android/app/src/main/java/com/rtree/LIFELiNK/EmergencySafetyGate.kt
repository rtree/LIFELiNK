package com.rtree.LIFELiNK

import android.content.Context
import java.util.UUID

data class EmergencyAttempt(
    val eventId: String,
    val isRetry: Boolean,
)

class EmergencySafetyGate(context: Context) {
    private val preferences = context.getSharedPreferences(
        "lifelink_emergency_gate",
        Context.MODE_PRIVATE,
    )

    val activeEventId: String?
        get() = preferences.getString(ACTIVE_EVENT_ID, null)

    @Synchronized
    fun begin(contactId: String): EmergencyAttempt {
        val activeEventId = preferences.getString(ACTIVE_EVENT_ID, null)
        val activeContactId = preferences.getString(ACTIVE_CONTACT_ID, null)
        if (activeEventId != null && activeContactId == contactId) {
            return EmergencyAttempt(eventId = activeEventId, isRetry = true)
        }

        val eventId = UUID.randomUUID().toString()
        preferences.edit()
            .putString(ACTIVE_EVENT_ID, eventId)
            .putString(ACTIVE_CONTACT_ID, contactId)
            .apply()
        return EmergencyAttempt(eventId = eventId, isRetry = false)
    }

    @Synchronized
    fun clear(eventId: String) {
        if (preferences.getString(ACTIVE_EVENT_ID, null) != eventId) {
            return
        }
        preferences.edit()
            .remove(ACTIVE_EVENT_ID)
            .remove(ACTIVE_CONTACT_ID)
            .apply()
    }

    // Returns the press reason when a pressed (non-idle) packet starts a new press, or null otherwise.
    @Synchronized
    fun observeBeaconState(stateKey: String, pressed: Boolean, packetAtMillis: Long, burstSeconds: Int): String? {
        val lastKey = preferences.getString(LAST_BEACON_STATE, null)
        val lastAt = preferences.getLong(LAST_BEACON_SEEN_AT, 0L)
        if (packetAtMillis < lastAt) return null
        preferences.edit()
            .putString(LAST_BEACON_STATE, stateKey)
            .putLong(LAST_BEACON_SEEN_AT, packetAtMillis)
            .apply()
        if (!pressed) return null
        // Gap must exceed the button's burst: screen-off delivery can gap >40s inside a single 60s burst.
        val gapMs = (burstSeconds + BEACON_GAP_MARGIN_SECONDS) * 1000L
        return when {
            lastKey != stateKey -> "state change"
            packetAtMillis - lastAt > gapMs -> "after ${gapMs / 1000}s"
            else -> null
        }
    }

    private companion object {
        const val ACTIVE_EVENT_ID = "active_event_id"
        const val ACTIVE_CONTACT_ID = "active_contact_id"
        const val LAST_BEACON_SEEN_AT = "last_beacon_seen_at"
        const val LAST_BEACON_STATE = "last_beacon_state"
        const val BEACON_GAP_MARGIN_SECONDS = 15
    }
}
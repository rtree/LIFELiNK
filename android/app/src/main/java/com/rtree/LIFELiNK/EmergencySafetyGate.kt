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

    @Synchronized
    fun acceptBeaconBurst(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val previous = preferences.getLong(LAST_BEACON_SEEN_AT, 0L)
        preferences.edit().putLong(LAST_BEACON_SEEN_AT, nowMillis).apply()
        return previous == 0L || nowMillis - previous > BEACON_BURST_GAP_MS
    }

    private companion object {
        const val ACTIVE_EVENT_ID = "active_event_id"
        const val ACTIVE_CONTACT_ID = "active_contact_id"
        const val LAST_BEACON_SEEN_AT = "last_beacon_seen_at"
        const val BEACON_BURST_GAP_MS = 30_000L
    }
}
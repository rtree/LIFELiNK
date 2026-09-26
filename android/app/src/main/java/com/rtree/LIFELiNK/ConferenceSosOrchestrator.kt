package com.rtree.LIFELiNK

import android.content.Context
import android.telecom.Call
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a carrier-conference SOS as the default phone app:
 * call the AI number with the join code, wait until the backend has bound the call,
 * call the contact (giving up after 60 s), then merge through the carrier.
 */
object ConferenceSosOrchestrator {
    private const val TAG = "LIFELiNK.ConfSOS"
    private const val AI_JOIN_TIMEOUT_MS = 45_000L
    private const val CALL_START_TIMEOUT_MS = 10_000L
    private const val CONTACT_RING_TIMEOUT_MS = 60_000L
    private const val MERGE_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    val running: Boolean
        get() = job?.isActive == true

    fun start(
        context: Context,
        apiClient: LifeLinkApiClient,
        eventId: String,
        aiNumber: String,
        joinCode: String,
        contactPhone: String,
    ): Boolean {
        if (running || CallRegistry.hasLiveCall) {
            report("Finish your current call before starting a conference SOS")
            return false
        }
        val appContext = context.applicationContext
        if (!placeCall(appContext, "$aiNumber,,$joinCode")) {
            report("Could not call the AI. Check the phone permission.")
            return false
        }
        report("Calling the AI…")
        job = scope.launch {
            val aiCall = awaitCall(aiNumber, CALL_START_TIMEOUT_MS)
            if (aiCall == null) {
                report("The call to the AI did not start")
                return@launch
            }
            if (!awaitAiJoined(apiClient, eventId, aiCall)) {
                report("The AI did not join. Stay on the line or call your contact yourself.")
                return@launch
            }
            report("The AI joined. Calling your contact…")
            if (!placeCall(appContext, contactPhone)) {
                report("Could not call your contact")
                return@launch
            }
            val contactCall = awaitCall(contactPhone, CALL_START_TIMEOUT_MS)
            if (contactCall == null) {
                report("The call to your contact did not start")
                aiCall.unhold()
                return@launch
            }
            val answered = withTimeoutOrNull(CONTACT_RING_TIMEOUT_MS) {
                CallRegistry.calls.first { calls ->
                    val state = calls.firstOrNull { it.call == contactCall }?.state ?: Call.STATE_DISCONNECTED
                    state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED
                }
                CallRegistry.callState(contactCall) == Call.STATE_ACTIVE
            } ?: false
            if (!answered) {
                report("Your contact did not answer. Back with the AI.")
                contactCall.disconnect()
                delay(1_000)
                aiCall.unhold()
                return@launch
            }
            report("Your contact answered. Merging…")
            // Telecom offers the pairing a moment after the answer, so keep asking until the merge lands.
            val merged = withTimeoutOrNull(MERGE_TIMEOUT_MS) {
                while (!isMerged(CallRegistry.calls.value, aiCall, contactCall)) {
                    CallRegistry.merge(contactCall, aiCall)
                    withTimeoutOrNull(1_000) {
                        CallRegistry.calls.first { calls -> isMerged(calls, aiCall, contactCall) }
                    }
                }
                true
            } ?: false
            report(
                if (merged) "You, your contact, and the AI are on one call"
                else "Could not merge automatically. Tap Merge.",
            )
        }
        return true
    }

    // IMS replaces both calls with a new conference call instead of parenting them.
    private fun isMerged(calls: List<CallSnapshot>, aiCall: Call, contactCall: Call): Boolean {
        if (aiCall.parent != null || contactCall.parent != null) return true
        val conferenceActive = calls.any { it.isConference && it.state == Call.STATE_ACTIVE }
        val mergedAway = listOf(aiCall, contactCall).any {
            CallRegistry.callState(it) == Call.STATE_DISCONNECTED &&
                it.details.disconnectCause?.reason?.contains("MERGED") == true
        }
        return conferenceActive || mergedAway
    }

    private suspend fun awaitCall(number: String, timeoutMs: Long): Call? = withTimeoutOrNull(timeoutMs) {
        CallRegistry.calls.first { calls -> calls.any { matches(it.number, number) } }
            .first { matches(it.number, number) }.call
    }

    private suspend fun awaitAiJoined(apiClient: LifeLinkApiClient, eventId: String, aiCall: Call): Boolean =
        withTimeoutOrNull(AI_JOIN_TIMEOUT_MS) {
            while (true) {
                if (CallRegistry.callState(aiCall) == Call.STATE_DISCONNECTED) return@withTimeoutOrNull false
                val state = runCatching { apiClient.getEmergencyEvent(eventId).state }
                    .onFailure { Log.w(TAG, "Event status check failed", it) }
                    .getOrNull()
                if (state == "in_progress") return@withTimeoutOrNull true
                if (state == "failed" || state == "completed") return@withTimeoutOrNull false
                delay(1_000)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    private fun matches(handle: String, number: String): Boolean {
        val a = handle.filter(Char::isDigit)
        val b = number.substringBefore(',').filter(Char::isDigit)
        return a.isNotEmpty() && b.isNotEmpty() && (a.endsWith(b.takeLast(9)) || b.endsWith(a.takeLast(9)))
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        _status.value = message
    }
}

package com.rtree.LIFELiNK

import android.os.Build
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallSnapshot(
    val call: Call,
    val number: String,
    val state: Int,
    val isConference: Boolean,
    val isChild: Boolean,
    val canMerge: Boolean,
    val canHold: Boolean,
) {
    val stateLabel: String
        get() = when (state) {
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> "Calling…"
            Call.STATE_ACTIVE -> "On the call"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTING -> "Hanging up…"
            Call.STATE_DISCONNECTED -> "Ended"
            else -> "State $state"
        }
}

/** Process-wide view of the calls Telecom hands to [LifeLinkInCallService]. */
object CallRegistry {
    private val _calls = MutableStateFlow<List<CallSnapshot>>(emptyList())
    val calls: StateFlow<List<CallSnapshot>> = _calls.asStateFlow()

    private val _canAddCall = MutableStateFlow(false)
    val canAddCall: StateFlow<Boolean> = _canAddCall.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _speaker = MutableStateFlow(false)
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    private val trackedCalls = mutableListOf<Call>()
    private var service: InCallService? = null
    private var endpoints: List<Any> = emptyList()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = publish()
        override fun onParentChanged(call: Call, parent: Call?) = publish()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = publish()
    }

    val hasLiveCall: Boolean
        get() = _calls.value.any { it.state != Call.STATE_DISCONNECTED }

    fun attach(service: InCallService) {
        this.service = service
    }

    fun detach(service: InCallService) {
        if (this.service === service) this.service = null
    }

    fun add(call: Call) {
        if (call !in trackedCalls) {
            trackedCalls += call
            call.registerCallback(callback)
        }
        publish()
    }

    fun remove(call: Call) {
        call.unregisterCallback(callback)
        trackedCalls -= call
        publish()
    }

    fun setCanAddCall(value: Boolean) {
        _canAddCall.value = value
    }

    fun onMuteChanged(value: Boolean) {
        _muted.value = value
    }

    fun onEndpointsChanged(available: List<Any>) {
        endpoints = available
    }

    fun onSpeakerChanged(value: Boolean) {
        _speaker.value = value
    }

    fun answer(call: Call) = call.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun reject(call: Call) = call.reject(false, null)

    fun hangUp(call: Call) = call.disconnect()

    fun toggleHold(snapshot: CallSnapshot) {
        if (snapshot.state == Call.STATE_HOLDING) snapshot.call.unhold() else snapshot.call.hold()
    }

    fun setMuted(value: Boolean) {
        service?.setMuted(value)
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        val inCallService = service ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val wanted = if (on) CallEndpoint.TYPE_SPEAKER else CallEndpoint.TYPE_EARPIECE
            val target = endpoints.filterIsInstance<CallEndpoint>().firstOrNull { it.endpointType == wanted }
                ?: return
            inCallService.requestCallEndpointChange(
                target,
                inCallService.mainExecutor,
                object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                    override fun onResult(result: Void?) = Unit
                    override fun onError(error: android.telecom.CallEndpointException) = Unit
                },
            )
        } else {
            inCallService.setAudioRoute(
                if (on) android.telecom.CallAudioState.ROUTE_SPEAKER
                else android.telecom.CallAudioState.ROUTE_WIRED_OR_EARPIECE,
            )
        }
    }

    fun playDtmf(call: Call, digit: Char) {
        call.playDtmfTone(digit)
        call.stopDtmfTone()
    }

    /** Merges the two calls through the carrier; returns false if Telecom does not offer the pairing. */
    fun merge(a: Call, b: Call): Boolean {
        return when {
            b in a.conferenceableCalls -> {
                a.conference(b)
                true
            }
            a in b.conferenceableCalls -> {
                b.conference(a)
                true
            }
            a.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) -> {
                a.mergeConference()
                true
            }
            b.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) -> {
                b.mergeConference()
                true
            }
            else -> false
        }
    }

    fun mergeAny(): Boolean {
        val live = _calls.value.filter { !it.isChild && it.state in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING) }
        if (live.size < 2) return false
        return merge(live[0].call, live[1].call)
    }

    private fun publish() {
        _calls.value = trackedCalls.map { call ->
            val details = call.details
            CallSnapshot(
                call = call,
                number = details.handle?.schemeSpecificPart
                    ?: if (details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) "Conference" else "Unknown",
                state = callState(call),
                isConference = details.hasProperty(Call.Details.PROPERTY_CONFERENCE),
                isChild = call.parent != null,
                canMerge = call.conferenceableCalls.isNotEmpty() ||
                    details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE),
                canHold = details.can(Call.Details.CAPABILITY_HOLD),
            )
        }
    }

    @Suppress("DEPRECATION")
    fun callState(call: Call): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else call.state
}

package com.rtree.LIFELiNK

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call

/** Handles the Decline / Hang up buttons on the call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = CallRegistry.calls.value.filter { !it.isChild }
        when (intent.action) {
            LifeLinkInCallService.ACTION_REJECT -> calls.firstOrNull { it.state == Call.STATE_RINGING }?.let {
                CallRegistry.reject(it.call)
            }
            LifeLinkInCallService.ACTION_HANG_UP -> calls
                .firstOrNull { it.state == Call.STATE_ACTIVE }
                ?.let { CallRegistry.hangUp(it.call) }
        }
    }
}

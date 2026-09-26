package com.rtree.LIFELiNK

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** In-call service Telecom binds while LIFELiNK holds the default phone app role. */
class LifeLinkInCallService : InCallService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        CallRegistry.attach(this)
        scope.launch {
            CallRegistry.calls.collect { updateNotification(it) }
        }
    }

    override fun onDestroy() {
        CallRegistry.detach(this)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        scope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        CallRegistry.add(call)
        CallRegistry.setCanAddCall(canAddCall())
        if (CallRegistry.callState(call) != Call.STATE_RINGING) {
            startActivity(InCallActivity.intent(this))
        }
    }

    override fun onCallRemoved(call: Call) {
        CallRegistry.remove(call)
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) {
        CallRegistry.setCanAddCall(canAddCall)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        startActivity(InCallActivity.intent(this))
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        CallRegistry.onMuteChanged(isMuted)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        CallRegistry.onEndpointsChanged(availableEndpoints)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        CallRegistry.onSpeakerChanged(callEndpoint.endpointType == CallEndpoint.TYPE_SPEAKER)
    }

    @Deprecated("Replaced by call endpoints on API 34+")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CallRegistry.onMuteChanged(audioState.isMuted)
            CallRegistry.onSpeakerChanged(audioState.route == CallAudioState.ROUTE_SPEAKER)
        }
    }

    private fun updateNotification(calls: List<CallSnapshot>) {
        val manager = getSystemService(NotificationManager::class.java)
        val live = calls.filter { it.state != Call.STATE_DISCONNECTED && !it.isChild }
        if (live.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannels(this)
        val ringing = live.firstOrNull { it.state == Call.STATE_RINGING }
        val openUi = PendingIntent.getActivity(
            this,
            0,
            InCallActivity.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, if (ringing != null) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openUi)
        if (ringing != null) {
            builder
                .setContentTitle("Incoming call")
                .setContentText(ringing.number)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(openUi, true)
                .addAction(
                    0,
                    "Answer",
                    PendingIntent.getActivity(
                        this,
                        1,
                        InCallActivity.intent(this).setAction(ACTION_ANSWER),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .addAction(0, "Decline", callAction(ACTION_REJECT, 2))
        } else {
            val main = live.first()
            builder
                .setContentTitle(main.stateLabel)
                .setContentText(if (live.size > 1) "${live.size} calls" else main.number)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, "Hang up", callAction(ACTION_HANG_UP, 3))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun callAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this,
        requestCode,
        Intent(this, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_ANSWER = "com.rtree.LIFELiNK.call.ANSWER"
        const val ACTION_REJECT = "com.rtree.LIFELiNK.call.REJECT"
        const val ACTION_HANG_UP = "com.rtree.LIFELiNK.call.HANG_UP"
        private const val CHANNEL_INCOMING = "calls_incoming"
        private const val CHANNEL_ONGOING = "calls_ongoing"
        private const val NOTIFICATION_ID = 4201

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    // Telecom plays the ringtone because the service does not declare IN_CALL_SERVICE_RINGING.
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}

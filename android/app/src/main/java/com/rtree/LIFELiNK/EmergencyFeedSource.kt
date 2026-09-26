package com.rtree.LIFELiNK

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Live feed of the newest emergency event owned by [uid], read straight from Firestore.
 * The client only reads; every write still goes through the backend API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun emergencyFeed(uid: String): Flow<EmergencyFeedState> =
    latestEmergencyEvent(uid)
        .flatMapLatest { result ->
            val event = result.getOrNull()
            when {
                result.isFailure -> flowOf(
                    EmergencyFeedState(errorMessage = feedErrorMessage(result.exceptionOrNull())),
                )
                event == null -> flowOf(EmergencyFeedState())
                else -> emergencyUpdates(event.id).map { updates ->
                    EmergencyFeedState(
                        eventId = event.id,
                        eventState = event.getString("state"),
                        triggerType = event.getString("trigger_type"),
                        eventCreatedAt = event.instant("created_at"),
                        entries = updates.getOrDefault(emptyList()),
                        errorMessage = updates.exceptionOrNull()?.let(::feedErrorMessage),
                    )
                }
            }
        }
        .onStart { emit(EmergencyFeedState(loading = true)) }

private fun latestEmergencyEvent(uid: String): Flow<Result<DocumentSnapshot?>> = callbackFlow {
    val registration = FirebaseFirestore.getInstance()
        .collection("emergency_events")
        .whereEqualTo("uid", uid)
        .orderBy("created_at", Query.Direction.DESCENDING)
        .limit(1)
        .addSnapshotListener { snapshot, error ->
            when {
                error != null -> trySend(Result.failure(error))
                else -> trySend(Result.success(snapshot?.documents?.firstOrNull()))
            }
        }
    awaitClose(registration::remove)
}

private fun emergencyUpdates(eventId: String): Flow<Result<List<EmergencyFeedEntry>>> = callbackFlow {
    val registration = FirebaseFirestore.getInstance()
        .collection("emergency_events")
        .document(eventId)
        .collection("updates")
        .orderBy("created_at", Query.Direction.ASCENDING)
        .addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(Result.failure(error))
                return@addSnapshotListener
            }
            val entries = snapshot?.documents.orEmpty().mapNotNull { document ->
                emergencyFeedEntry(
                    id = document.id,
                    type = document.getString("type"),
                    authorName = document.getString("author_name"),
                    text = document.getString("text"),
                    payload = (document.get("payload") as? Map<*, *>)
                        ?.entries
                        ?.associate { (key, value) -> key.toString() to value },
                    createdAt = document.instant("created_at"),
                    deliveredToAiAt = document.instant("delivered_to_ai_at"),
                )
            }
            trySend(Result.success(entries))
        }
    awaitClose(registration::remove)
}

private fun DocumentSnapshot.instant(field: String): Instant? =
    getTimestamp(field)?.toDate()?.toInstant()

private fun feedErrorMessage(error: Throwable?): String =
    "Could not load the live feed: ${error?.message ?: "unknown error"}"

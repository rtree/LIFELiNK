package com.rtree.LIFELiNK

import java.time.Instant

enum class EmergencyFeedKind {
    OWN_NOTE,
    OWN_LOCATION,
    CONTACT_SPEECH,
    AI_SPEECH,
    FRIEND_COMMENT,
    SYSTEM,
}

data class EmergencyFeedEntry(
    val id: String,
    val kind: EmergencyFeedKind,
    val authorLabel: String,
    val text: String,
    val createdAt: Instant?,
    val deliveredToAi: Boolean,
) {
    val isOwn: Boolean
        get() = kind == EmergencyFeedKind.OWN_NOTE || kind == EmergencyFeedKind.OWN_LOCATION
}

data class EmergencyFeedState(
    val eventId: String? = null,
    val eventState: String? = null,
    val triggerType: String? = null,
    val eventCreatedAt: Instant? = null,
    val entries: List<EmergencyFeedEntry> = emptyList(),
    val errorMessage: String? = null,
    val loading: Boolean = false,
)

/**
 * Formatting is kept free of Firestore types so the P2 `timeline` collection can feed the same UI.
 */
fun emergencyFeedEntry(
    id: String,
    type: String?,
    authorName: String?,
    text: String?,
    payload: Map<String, Any?>?,
    createdAt: Instant?,
    deliveredToAiAt: Instant?,
): EmergencyFeedEntry? {
    val kind = when (type) {
        "note" -> EmergencyFeedKind.OWN_NOTE
        "location" -> EmergencyFeedKind.OWN_LOCATION
        "transcript_contact" -> EmergencyFeedKind.CONTACT_SPEECH
        "transcript_ai" -> EmergencyFeedKind.AI_SPEECH
        "friend_comment" -> EmergencyFeedKind.FRIEND_COMMENT
        else -> EmergencyFeedKind.SYSTEM
    }
    val body = when (kind) {
        EmergencyFeedKind.OWN_LOCATION ->
            formatLocationPayload(payload) ?: text?.takeIf(String::isNotBlank)
        EmergencyFeedKind.OWN_NOTE ->
            text?.takeIf(String::isNotBlank) ?: payload?.get("text") as? String
        else -> text?.takeIf(String::isNotBlank)
    } ?: return null

    val label = when (kind) {
        EmergencyFeedKind.OWN_NOTE -> "You"
        EmergencyFeedKind.OWN_LOCATION -> "Your location"
        EmergencyFeedKind.CONTACT_SPEECH -> "Emergency contact"
        EmergencyFeedKind.AI_SPEECH -> "LIFELiNK AI"
        EmergencyFeedKind.FRIEND_COMMENT ->
            authorName?.takeIf(String::isNotBlank)?.let { "$it (Discord)" } ?: "Friend (Discord)"
        EmergencyFeedKind.SYSTEM -> "System"
    }

    return EmergencyFeedEntry(
        id = id,
        kind = kind,
        authorLabel = label,
        text = body.trim(),
        createdAt = createdAt,
        deliveredToAi = deliveredToAiAt != null,
    )
}

private fun formatLocationPayload(payload: Map<String, Any?>?): String? {
    // Coordinates and accuracy are never persisted, so only the prefecture-level address exists here.
    val address = (payload?.get("address") as? String)?.takeIf(String::isNotBlank) ?: return null
    return "Shared location: $address"
}

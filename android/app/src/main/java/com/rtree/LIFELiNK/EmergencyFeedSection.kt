package com.rtree.LIFELiNK

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FEED_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
fun EmergencyFeedSection(uid: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Live feed", style = MaterialTheme.typography.titleLarge)
        if (uid == null) {
            Text("Sign in to see the live feed of your emergency calls.")
            return@Column
        }

        val feed by remember(uid) { emergencyFeed(uid) }
            .collectAsStateWithLifecycle(EmergencyFeedState(loading = true))

        Text(feedHeadline(feed), style = MaterialTheme.typography.bodyMedium)
        feed.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        if (feed.eventId == null) {
            return@Column
        }
        if (feed.entries.isEmpty()) {
            Text("No messages yet. Call transcripts and Discord replies appear here as they arrive.")
            return@Column
        }

        val listState = rememberLazyListState()
        LaunchedEffect(feed.entries.size) {
            listState.animateScrollToItem(feed.entries.lastIndex.coerceAtLeast(0))
        }
        LazyColumn(
            // Bounded max height keeps the lazy list measurable inside the parent's verticalScroll.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(feed.entries, key = EmergencyFeedEntry::id) { entry ->
                EmergencyFeedBubble(entry)
            }
        }
    }
}

@Composable
private fun EmergencyFeedBubble(entry: EmergencyFeedEntry) {
    if (entry.kind == EmergencyFeedKind.SYSTEM) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = "${entry.timeLabel()} ${entry.text}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (entry.isOwn) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            colors = CardDefaults.cardColors(containerColor = entry.bubbleColor()),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.authorLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(entry.timeLabel(), style = MaterialTheme.typography.labelSmall)
                }
                Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                if (entry.kind == EmergencyFeedKind.FRIEND_COMMENT && entry.deliveredToAi) {
                    Text(
                        "Passed to the AI on the call",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmergencyFeedEntry.bubbleColor(): Color = when (kind) {
    EmergencyFeedKind.OWN_NOTE, EmergencyFeedKind.OWN_LOCATION ->
        MaterialTheme.colorScheme.primaryContainer
    EmergencyFeedKind.AI_SPEECH -> MaterialTheme.colorScheme.tertiaryContainer
    EmergencyFeedKind.FRIEND_COMMENT -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

private fun EmergencyFeedEntry.timeLabel(): String = createdAt.formatTime()

private fun Instant?.formatTime(): String =
    this?.let(FEED_TIME_FORMATTER::format) ?: "--:--:--"

private fun feedHeadline(feed: EmergencyFeedState): String = when {
    feed.loading -> "Loading..."
    feed.eventId == null -> "No emergency events yet. Your live feed appears here after an SOS."
    else -> buildString {
        append("Latest SOS: ")
        append(feed.eventState ?: "unknown")
        feed.triggerType?.let { append(" · ").append(it) }
        feed.eventCreatedAt?.let { append(" · started ").append(it.formatTime()) }
    }
}

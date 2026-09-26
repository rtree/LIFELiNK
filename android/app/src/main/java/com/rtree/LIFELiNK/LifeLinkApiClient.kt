package com.rtree.LIFELiNK

import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAt: String,
    val address: String? = null,
    val geocodedAt: String? = null,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val motionState: String? = null,
    val motionPeakG: Float? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracy_m", accuracyMeters.toDouble())
        .put("captured_at", capturedAt)
        .put("address", address ?: JSONObject.NULL)
        .put("geocoded_at", geocodedAt ?: JSONObject.NULL)
        .put("battery_percent", batteryPercent ?: JSONObject.NULL)
        .put("battery_charging", batteryCharging ?: JSONObject.NULL)
        .put("motion_state", motionState ?: JSONObject.NULL)
        .put("motion_peak_g", motionPeakG?.toDouble() ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): LocationSnapshot = LocationSnapshot(
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            accuracyMeters = json.getDouble("accuracy_m").toFloat(),
            capturedAt = json.getString("captured_at"),
            address = if (json.isNull("address")) null else json.getString("address"),
            geocodedAt = if (json.isNull("geocoded_at")) null else json.getString("geocoded_at"),
            batteryPercent = if (json.isNull("battery_percent")) null else json.optInt("battery_percent"),
            batteryCharging = if (json.isNull("battery_charging")) null else json.optBoolean("battery_charging"),
            motionState = if (json.isNull("motion_state")) null else json.optString("motion_state"),
            motionPeakG = if (json.isNull("motion_peak_g")) null else json.optDouble("motion_peak_g").toFloat(),
        )
    }
}

data class ContactRegistration(
    val contactId: String,
    val maskedPhone: String,
)

data class EmergencyEventResult(
    val eventId: String,
    val state: String,
    val idempotentReplay: Boolean,
    val aiNumber: String? = null,
    val joinCode: String? = null,
)

data class EmergencyEventStatus(
    val eventId: String,
    val state: String,
    val failureCode: String?,
)

data class WorldIdFlow(
    val flowId: String,
    val connectorUri: String,
)

data class WorldIdFlowStatus(
    val state: String,
    val error: String?,
)

data class DiscordContact(
    val id: String,
    val displayName: String,
    val testStatus: String?,
    val testErrorCode: Int?,
    val testAcknowledged: Boolean,
)

class ApiException(
    val statusCode: Int,
    val errorCode: String,
) : Exception("API request failed ($statusCode): $errorCode")

class LifeLinkApiClient(
    private val backendUrl: String = BuildConfig.BACKEND_URL,
) {
    companion object {
        const val MODE_OUTBOUND = "outbound"
        const val MODE_CARRIER_CONFERENCE = "carrier_conference"
    }

    suspend fun saveLocation(location: LocationSnapshot): String {
        val response = post("/v1/locations", location.toJson())
        return response.getString("location_id")
    }

    suspend fun createContact(name: String, phone: String): ContactRegistration {
        val response = post(
            "/v1/contacts",
            JSONObject().put("name", name).put("phone", phone),
        )
        return ContactRegistration(
            contactId = response.getString("contact_id"),
            maskedPhone = response.getString("phone_masked"),
        )
    }

    suspend fun createEmergencyEvent(
        eventId: String,
        contactId: String,
        trigger: EmergencyTrigger,
        location: LocationSnapshot?,
        initialNote: String?,
        mode: String = MODE_OUTBOUND,
    ): EmergencyEventResult {
        val response = post(
            "/v1/emergency-events",
            JSONObject()
                .put("emergency_event_id", eventId)
                .put("contact_id", contactId)
                .put("trigger_type", trigger.wireValue)
                .put("location_snapshot", location?.toJson() ?: JSONObject.NULL)
                .put("initial_note", initialNote?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                .put("mode", mode),
        )
        return EmergencyEventResult(
            eventId = response.getString("emergency_event_id"),
            state = response.getString("state"),
            idempotentReplay = response.getBoolean("idempotent_replay"),
            aiNumber = response.optString("ai_number").takeIf(String::isNotBlank),
            joinCode = response.optString("join_code").takeIf(String::isNotBlank),
        )
    }

    suspend fun sendNoteUpdate(eventId: String, text: String) {
        post(
            "/v1/emergency-events/$eventId/updates",
            JSONObject()
                .put("update_id", UUID.randomUUID().toString())
                .put("type", "note")
                .put("text", text),
        )
    }

    suspend fun sendLocationUpdate(eventId: String, location: LocationSnapshot) {
        post(
            "/v1/emergency-events/$eventId/updates",
            JSONObject()
                .put("update_id", UUID.randomUUID().toString())
                .put("type", "location")
                .put("location", location.toJson()),
        )
    }

    suspend fun getEmergencyEvent(eventId: String): EmergencyEventStatus {
        val response = request("GET", "/v1/emergency-events/$eventId")
        return EmergencyEventStatus(
            eventId = response.getString("emergency_event_id"),
            state = response.getString("state"),
            failureCode = response.optString("failure_code").takeIf(String::isNotBlank),
        )
    }

    suspend fun startWorldIdFlow(): WorldIdFlow {
        val response = post("/v1/world-id/start", JSONObject())
        return WorldIdFlow(
            flowId = response.getString("flow_id"),
            connectorUri = response.getString("connector_uri"),
        )
    }

    suspend fun getWorldIdFlowStatus(flowId: String): WorldIdFlowStatus {
        val response = request("GET", "/v1/world-id/status/$flowId")
        return WorldIdFlowStatus(
            state = response.getString("state"),
            error = response.optString("error").takeIf(String::isNotBlank),
        )
    }

    suspend fun createDiscordInvite(): String =
        post("/v1/discord/invites", JSONObject()).getString("invite_url")

    suspend fun listDiscordContacts(): List<DiscordContact> {
        val contacts = request("GET", "/v1/discord/contacts").getJSONArray("contacts")
        return (0 until contacts.length()).map { index ->
            val contact = contacts.getJSONObject(index)
            val test = contact.optJSONObject("last_test_dm")
            DiscordContact(
                id = contact.getString("id"),
                displayName = contact.getString("display_name"),
                testStatus = test?.getString("status"),
                testErrorCode = test?.takeUnless { it.isNull("error_code") }?.optInt("error_code"),
                testAcknowledged = test?.optBoolean("acknowledged") == true,
            )
        }
    }

    suspend fun sendDiscordTest(contactId: String) {
        post("/v1/discord/contacts/$contactId/test", JSONObject())
    }

    suspend fun revokeDiscordContact(contactId: String) {
        request("DELETE", "/v1/discord/contacts/$contactId")
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        return request("POST", path, body)
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Google login is required")
        val idToken = user.getIdToken(false).await().token
            ?: throw IllegalStateException("Firebase ID token is unavailable")

        return withContext(Dispatchers.IO) {
            val connection = URL("${backendUrl.trimEnd('/')}$path")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 65_000
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(body.toString())
                    }
                }

                val statusCode = connection.responseCode
                val responseText = (if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val response = responseText.takeIf(String::isNotBlank)
                    ?.let(::JSONObject)
                    ?: JSONObject()
                if (statusCode !in 200..299) {
                    throw ApiException(
                        statusCode = statusCode,
                        errorCode = response.optString("error", "http_error"),
                    )
                }
                response
            } finally {
                connection.disconnect()
            }
        }
    }
}

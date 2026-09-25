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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracy_m", accuracyMeters.toDouble())
        .put("captured_at", capturedAt)
        .put("address", address ?: JSONObject.NULL)
        .put("geocoded_at", geocodedAt ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): LocationSnapshot = LocationSnapshot(
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            accuracyMeters = json.getDouble("accuracy_m").toFloat(),
            capturedAt = json.getString("captured_at"),
            address = if (json.isNull("address")) null else json.getString("address"),
            geocodedAt = if (json.isNull("geocoded_at")) null else json.getString("geocoded_at"),
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
)

data class EmergencyEventStatus(
    val eventId: String,
    val state: String,
    val failureCode: String?,
)

class ApiException(
    val statusCode: Int,
    val errorCode: String,
) : Exception("API request failed ($statusCode): $errorCode")

class LifeLinkApiClient(
    private val backendUrl: String = BuildConfig.BACKEND_URL,
) {
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
    ): EmergencyEventResult {
        val response = post(
            "/v1/emergency-events",
            JSONObject()
                .put("emergency_event_id", eventId)
                .put("contact_id", contactId)
                .put("trigger_type", trigger.wireValue)
                .put("location_snapshot", location?.toJson() ?: JSONObject.NULL)
                .put("initial_note", initialNote?.takeIf(String::isNotBlank) ?: JSONObject.NULL),
        )
        return EmergencyEventResult(
            eventId = response.getString("emergency_event_id"),
            state = response.getString("state"),
            idempotentReplay = response.getBoolean("idempotent_replay"),
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

package de.wrch.health

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class WrchSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresAtEpoch: Long
)

class SupabaseRest(private val context: Context) {
    private val prefs = context.getSharedPreferences("wrch_auth", Context.MODE_PRIVATE)

    fun savedSession(): WrchSession? {
        val access = prefs.getString("access", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        val user = prefs.getString("user", null) ?: return null
        return WrchSession(access, refresh, user, prefs.getLong("expires", 0))
    }

    fun clearSession() = prefs.edit().clear().apply()

    fun signIn(email: String, password: String): WrchSession {
        val body = JSONObject().put("email", email).put("password", password)
        val response = request(
            "POST",
            "/auth/v1/token?grant_type=password",
            body.toString(),
            accessToken = null
        )
        return saveAuthResponse(JSONObject(response))
    }

    fun validSession(): WrchSession {
        val current = savedSession() ?: error("Bitte zuerst bei WRCH anmelden.")
        if (System.currentTimeMillis() / 1000 < current.expiresAtEpoch - 90) return current
        val body = JSONObject().put("refresh_token", current.refreshToken)
        val response = request(
            "POST",
            "/auth/v1/token?grant_type=refresh_token",
            body.toString(),
            accessToken = null
        )
        return saveAuthResponse(JSONObject(response))
    }

    private fun saveAuthResponse(json: JSONObject): WrchSession {
        val access = json.getString("access_token")
        val refresh = json.getString("refresh_token")
        val user = json.getJSONObject("user").getString("id")
        val expires = System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)
        val session = WrchSession(access, refresh, user, expires)
        prefs.edit()
            .putString("access", access)
            .putString("refresh", refresh)
            .putString("user", user)
            .putLong("expires", expires)
            .apply()
        return session
    }

    fun upsertDailyActivity(session: WrchSession, rows: JSONArray) {
        if (rows.length() == 0) return
        rest(
            session,
            "POST",
            "/rest/v1/daily_activity?on_conflict=user_id,activity_date,provider",
            rows.toString(),
            "resolution=merge-duplicates,return=minimal"
        )
    }

    fun upsertWeightSamples(session: WrchSession, rows: JSONArray) {
        if (rows.length() == 0) return
        rest(
            session,
            "POST",
            "/rest/v1/health_weight_samples?on_conflict=user_id,provider,external_id",
            rows.toString(),
            "resolution=merge-duplicates,return=minimal"
        )
    }

    fun upsertHealthActivities(session: WrchSession, rows: JSONArray) {
        for (i in 0 until rows.length()) {
            val x = rows.getJSONObject(i)
            val body = JSONObject()
                .put("p_external_id", x.getString("external_id"))
                .put("p_activity_type", x.getString("activity_type"))
                .put("p_title", x.getString("title"))
                .put("p_duration_minutes", x.optInt("duration_minutes", 0))
                .put("p_distance_km", if (x.isNull("distance_km")) JSONObject.NULL else x.getDouble("distance_km"))
                .put("p_performed_on", x.getString("performed_on"))
                .put("p_source_label", x.optString("source_label", "Health Connect"))
                .put("p_notes", x.optString("notes", null))
            rest(
                session,
                "POST",
                "/rest/v1/rpc/upsert_health_activity",
                body.toString(),
                "return=minimal"
            )
        }
    }

    fun updateConnection(
        session: WrchSession,
        status: String,
        permissions: JSONArray,
        errorText: String? = null
    ) {
        val row = JSONObject()
            .put("user_id", session.userId)
            .put("provider", "health_connect")
            .put("status", status)
            .put("permissions", permissions)
            .put("last_sync_at", if (status == "connected") Instant.now().toString() else JSONObject.NULL)
            .put("last_error", errorText ?: JSONObject.NULL)

        rest(
            session,
            "POST",
            "/rest/v1/health_connections?on_conflict=user_id,provider",
            JSONArray().put(row).toString(),
            "resolution=merge-duplicates,return=minimal"
        )
    }

    private fun rest(
        session: WrchSession,
        method: String,
        path: String,
        body: String? = null,
        prefer: String? = null
    ): String = request(method, path, body, session.accessToken, prefer)

    private fun request(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        prefer: String? = null
    ): String {
        val conn = URL(WrchConfig.SUPABASE_URL + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("apikey", WrchConfig.SUPABASE_PUBLISHABLE_KEY)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (accessToken != null) conn.setRequestProperty("Authorization", "Bearer $accessToken")
        if (prefer != null) conn.setRequestProperty("Prefer", prefer)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            val message = try {
                val json = JSONObject(text)
                json.optString("msg").ifBlank {
                    json.optString("message").ifBlank { json.optString("error_description") }
                }
            } catch (_: Exception) { text }
            error("Supabase $code: ${message.ifBlank { "Unbekannter Fehler" }}")
        }
        return text
    }
}

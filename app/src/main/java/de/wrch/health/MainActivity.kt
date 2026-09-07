package de.wrch.health

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val api by lazy { SupabaseRest(this) }
    private val providerPackage = "com.google.android.apps.healthdata"

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class)
    )

    private val permissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            lifecycleScope.launch { reportHealthState() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(NativeBridge(), "WRCHNative")
        }
        setContentView(webView)
        val html = loadBundledHtml()
        webView.loadDataWithBaseURL("https://wrch.local/", html, "text/html", "UTF-8", null)
    }

    private fun loadBundledHtml(): String {
        val encoded = buildString {
            for (i in 0..10) {
                val name = "index_%02d.txt".format(i)
                append(assets.open(name).bufferedReader().use { it.readText() })
            }
        }
        val compressed = Base64.decode(encoded, Base64.DEFAULT)
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun setSession(accessToken: String, refreshToken: String, userId: String, expiresAtEpoch: Long) {
            getSharedPreferences("wrch_auth", MODE_PRIVATE).edit()
                .putString("access", accessToken)
                .putString("refresh", refreshToken)
                .putString("user", userId)
                .putLong("expires", expiresAtEpoch)
                .apply()
            lifecycleScope.launch { reportHealthState() }
        }

        @JavascriptInterface
        fun clearSession() {
            api.clearSession()
        }

        @JavascriptInterface
        fun requestHealthPermissions() {
            runOnUiThread {
                val sdk = HealthConnectClient.getSdkStatus(this@MainActivity, providerPackage)
                if (sdk == HealthConnectClient.SDK_AVAILABLE) {
                    permissionLauncher.launch(healthPermissions)
                } else {
                    sendStatus("Health Connect ist auf diesem Gerät nicht verfügbar.")
                }
            }
        }

        @JavascriptInterface
        fun syncHealth() {
            lifecycleScope.launch { syncAll() }
        }
    }

    private fun sendStatus(message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onNativeHealthStatus && window.onNativeHealthStatus(${JSONObject.quote(message)});",
                null
            )
        }
    }

    private fun sendSyncResult(ok: Boolean, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onNativeHealthSyncResult && window.onNativeHealthSyncResult($ok,${JSONObject.quote(message)});",
                null
            )
        }
    }

    private suspend fun reportHealthState() {
        if (api.savedSession() == null) return
        val sdk = HealthConnectClient.getSdkStatus(this, providerPackage)
        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            sendStatus("WRCH ist angemeldet, aber Health Connect ist nicht verfügbar.")
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(healthPermissions)) {
            sendStatus("Health Connect ist noch nicht vollständig freigegeben.")
        } else {
            sendStatus("Health Connect ist verbunden und bereit.")
        }
    }

    private suspend fun syncAll() {
        try {
            sendStatus("Health-Daten werden synchronisiert …")
            val session = withContext(Dispatchers.IO) { api.validSession() }
            val sdk = HealthConnectClient.getSdkStatus(this, providerPackage)
            if (sdk != HealthConnectClient.SDK_AVAILABLE) error("Health Connect ist nicht verfügbar.")

            val client = HealthConnectClient.getOrCreate(this)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(healthPermissions)) {
                withContext(Dispatchers.IO) {
                    api.updateConnection(session, "needs_permission", JSONArray(granted.toList()))
                }
                error("Bitte zuerst die Health-Connect-Berechtigungen erteilen.")
            }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val activityRows = JSONArray()

            for (offset in 0..29) {
                val date = today.minusDays(offset.toLong())
                val start = date.atStartOfDay(zone).toInstant()
                val end = if (offset == 0) Instant.now() else date.plusDays(1).atStartOfDay(zone).toInstant()
                val steps = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )[StepsRecord.COUNT_TOTAL] ?: 0L

                activityRows.put(
                    JSONObject()
                        .put("user_id", session.userId)
                        .put("activity_date", date.toString())
                        .put("provider", "health_connect")
                        .put("steps", steps)
                        .put("source_label", "Health Connect")
                        .put("synced_at", Instant.now().toString())
                )
            }

            val start30 = today.minusDays(29).atStartOfDay(zone).toInstant()
            val now = Instant.now()

            val weightRows = JSONArray()
            val weights = client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start30, now),
                    pageSize = 1000
                )
            ).records
            weights.forEach { w ->
                val origin = friendlyOrigin(w.metadata.dataOrigin.packageName)
                weightRows.put(
                    JSONObject()
                        .put("user_id", session.userId)
                        .put("provider", "health_connect")
                        .put("external_id", w.metadata.id)
                        .put("measured_at", w.time.toString())
                        .put("weight_kg", w.weight.inKilograms)
                        .put("source_label", origin)
                        .put("synced_at", Instant.now().toString())
                )
            }

            val healthActivities = JSONArray()
            val workouts = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start30, now),
                    pageSize = 1000
                )
            ).records
            workouts.forEach { w ->
                val minutes = max(0, Duration.between(w.startTime, w.endTime).toMinutes().toInt())
                val performedDate = w.startTime.atZone(zone).toLocalDate()
                val distanceMeters = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(w.startTime, w.endTime)
                    )
                )[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                val mapped = mapExercise(w.exerciseType)
                val origin = friendlyOrigin(w.metadata.dataOrigin.packageName)
                healthActivities.put(
                    JSONObject()
                        .put("external_id", w.metadata.id)
                        .put("activity_type", mapped.first)
                        .put("title", w.title?.takeIf { it.isNotBlank() } ?: mapped.second)
                        .put("duration_minutes", minutes)
                        .put("distance_km", if (distanceMeters != null && distanceMeters > 0) distanceMeters / 1000.0 else JSONObject.NULL)
                        .put("performed_on", performedDate.toString())
                        .put("source_label", origin)
                        .put("notes", "Automatisch aus Health Connect importiert")
                )
            }

            withContext(Dispatchers.IO) {
                api.upsertDailyActivity(session, activityRows)
                api.upsertWeightSamples(session, weightRows)
                api.upsertHealthActivities(session, healthActivities)
                api.updateConnection(
                    session,
                    "connected",
                    JSONArray().apply {
                        put("steps"); put("weight"); put("exercise"); put("distance")
                    }
                )
            }

            val todaySteps = activityRows.getJSONObject(0).getLong("steps")
            sendSyncResult(
                true,
                "Synchronisiert: ${todaySteps.toString().reversed().chunked(3).joinToString(".").reversed()} Schritte · ${healthActivities.length()} Aktivitäten"
            )
        } catch (t: Throwable) {
            val message = "Synchronisierung fehlgeschlagen: ${t.message ?: "Unbekannter Fehler"}"
            val s = api.savedSession()
            if (s != null) {
                try {
                    withContext(Dispatchers.IO) { api.updateConnection(s, "error", JSONArray(), t.message) }
                } catch (_: Throwable) { }
            }
            sendSyncResult(false, message)
        }
    }

    private fun mapExercise(type: Int): Pair<String, String> = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk" to "Spaziergang"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run" to "Laufen"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "bike" to "Radfahren"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim" to "Schwimmen"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "workout" to "Training"
        else -> "other" to "Aktivität"
    }

    private fun friendlyOrigin(packageName: String): String = when {
        packageName.contains("shealth", ignoreCase = true) -> "Samsung Health"
        packageName.contains("fitbit", ignoreCase = true) -> "Fitbit"
        packageName.contains("google", ignoreCase = true) -> "Google"
        else -> "Health Connect"
    }
}

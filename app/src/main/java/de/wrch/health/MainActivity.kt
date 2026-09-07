package de.wrch.health

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val api by lazy { SupabaseRest(this) }
    private val providerPackage = "com.google.android.apps.healthdata"

    private val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    private val weightPermission = HealthPermission.getReadPermission(WeightRecord::class)
    private val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val distancePermission = HealthPermission.getReadPermission(DistanceRecord::class)

    private val healthPermissions = setOf(
        stepsPermission,
        weightPermission,
        exercisePermission,
        distancePermission
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
            clipToPadding = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    injectNativeUiHints()
                }
            }
            addJavascriptInterface(NativeBridge(), "WRCHNative")
        }

        // Android 15/16 erzwingt bei neueren Target-SDKs Edge-to-Edge.
        // Die Systemleisten werden deshalb als echtes Padding an die WRCH-WebView weitergegeben.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setContentView(webView)
        ViewCompat.requestApplyInsets(webView)

        val html = loadBundledHtml()
        webView.loadDataWithBaseURL("https://wrch.local/", html, "text/html", "UTF-8", null)
    }

    private fun injectNativeUiHints() {
        val js = """
            (function(){
              try{
                document.body.classList.add('native-android');
                const input=document.getElementById('moveGoalInput');
                if(input && !document.getElementById('nativeStepGoalSource')){
                  const note=document.createElement('div');
                  note.id='nativeStepGoalSource';
                  note.className='small muted';
                  note.style.marginTop='8px';
                  note.textContent='Quelle: WRCH · Health Connect überträgt kein Schrittziel. Das Ziel deiner Uhr kann deshalb nicht automatisch übernommen werden.';
                  const row=input.closest('.row');
                  if(row && row.parentNode) row.parentNode.insertBefore(note,row.nextSibling);
                }
              }catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun loadBundledHtml(): String {
        val encoded = buildString {
            for (i in 0..10) {
                val name = "index_%02d.txt".format(i)
                append(assets.open(name).bufferedReader().use { it.readText() })
            }
        }
        val compressed = Base64.decode(encoded, Base64.DEFAULT)
        return GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    @Deprecated("Deprecated in Java")
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
        val enabled = mutableListOf<String>()
        if (stepsPermission in granted) enabled += "Schritte"
        if (exercisePermission in granted) enabled += "Training"
        if (distancePermission in granted) enabled += "Distanz"
        if (weightPermission in granted) enabled += "Gewicht"

        if (enabled.isEmpty()) {
            sendStatus("Health Connect ist noch nicht freigegeben.")
        } else {
            sendStatus("Health Connect verbunden · ${enabled.joinToString(", ")}")
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

            val canSteps = stepsPermission in granted
            val canWeight = weightPermission in granted
            val canExercise = exercisePermission in granted
            val canDistance = distancePermission in granted

            if (!canSteps && !canWeight && !canExercise) {
                withContext(Dispatchers.IO) {
                    api.updateConnection(session, "needs_permission", JSONArray(granted.toList()))
                }
                error("Bitte mindestens Schritte, Training oder Gewicht in Health Connect freigeben.")
            }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val activityRows = JSONArray()

            if (canSteps) {
                for (offset in 0..29) {
                    val date = today.minusDays(offset.toLong())
                    val start = date.atStartOfDay(zone).toInstant()
                    val end = if (offset == 0) {
                        Instant.now()
                    } else {
                        date.plusDays(1).atStartOfDay(zone).toInstant()
                    }
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
            }

            val start30 = today.minusDays(29).atStartOfDay(zone).toInstant()
            val now = Instant.now()

            val weightRows = JSONArray()
            if (canWeight) {
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
            }

            val healthActivities = JSONArray()
            if (canExercise) {
                val workouts = client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start30, now),
                        pageSize = 1000
                    )
                ).records

                workouts.forEach { w ->
                    val minutes = max(
                        0,
                        Duration.between(w.startTime, w.endTime).toMinutes().toInt()
                    )
                    val performedDate = w.startTime.atZone(zone).toLocalDate()
                    val distanceMeters = if (canDistance) {
                        client.aggregate(
                            AggregateRequest(
                                metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                                timeRangeFilter = TimeRangeFilter.between(w.startTime, w.endTime)
                            )
                        )[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                    } else {
                        null
                    }
                    val mapped = mapExercise(w.exerciseType)
                    val origin = friendlyOrigin(w.metadata.dataOrigin.packageName)

                    healthActivities.put(
                        JSONObject()
                            .put("external_id", w.metadata.id)
                            .put("activity_type", mapped.first)
                            .put("title", w.title?.takeIf { it.isNotBlank() } ?: mapped.second)
                            .put("duration_minutes", minutes)
                            .put(
                                "distance_km",
                                if (distanceMeters != null && distanceMeters > 0) {
                                    distanceMeters / 1000.0
                                } else {
                                    JSONObject.NULL
                                }
                            )
                            .put("performed_on", performedDate.toString())
                            .put("source_label", origin)
                            .put("notes", "Automatisch aus Health Connect importiert")
                    )
                }
            }

            withContext(Dispatchers.IO) {
                if (activityRows.length() > 0) api.upsertDailyActivity(session, activityRows)
                if (weightRows.length() > 0) api.upsertWeightSamples(session, weightRows)
                if (healthActivities.length() > 0) api.upsertHealthActivities(session, healthActivities)

                val names = JSONArray()
                if (canSteps) names.put("steps")
                if (canWeight) names.put("weight")
                if (canExercise) names.put("exercise")
                if (canDistance) names.put("distance")
                api.updateConnection(session, "connected", names)
            }

            val resultParts = mutableListOf<String>()
            if (canSteps && activityRows.length() > 0) {
                val todaySteps = activityRows.getJSONObject(0).getLong("steps")
                resultParts += "${todaySteps.toString().reversed().chunked(3).joinToString(".").reversed()} Schritte"
            }
            if (canExercise) resultParts += "${healthActivities.length()} Aktivitäten"
            if (canWeight) resultParts += "${weightRows.length()} Gewichtseinträge"

            sendSyncResult(true, "Synchronisiert: ${resultParts.joinToString(" · ")}")
        } catch (t: Throwable) {
            val message = "Synchronisierung fehlgeschlagen: ${t.message ?: "Unbekannter Fehler"}"
            val s = api.savedSession()
            if (s != null) {
                try {
                    withContext(Dispatchers.IO) {
                        api.updateConnection(s, "error", JSONArray(), t.message)
                    }
                } catch (_: Throwable) {
                }
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

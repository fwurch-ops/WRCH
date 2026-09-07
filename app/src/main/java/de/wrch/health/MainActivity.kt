package de.wrch.health

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.DistanceRecord
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var permissionButton: Button
    private lateinit var syncButton: Button

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
            lifecycleScope.launch { updateState() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 70, 48, 70)
        }
        scroll.addView(box)

        box.addView(TextView(this).apply {
            text = "WRCH · Health Connect"
            textSize = 28f
        })
        box.addView(TextView(this).apply {
            text = "Melde dich mit demselben WRCH-Konto an wie auf der Website. Danach kann dieser Android-Baustein Health Connect in dein bestehendes Konto synchronisieren."
            textSize = 15f
            setPadding(0, 12, 0, 20)
        })

        email = EditText(this).apply {
            hint = "WRCH E-Mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        password = EditText(this).apply {
            hint = "Passwort"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        loginButton = Button(this).apply {
            text = "Bei WRCH anmelden"
            setOnClickListener { login() }
        }
        logoutButton = Button(this).apply {
            text = "Abmelden"
            setOnClickListener {
                api.clearSession()
                updateUiForSession(false)
                status.text = "Abgemeldet."
            }
        }
        permissionButton = Button(this).apply {
            text = "Health Connect freigeben"
            setOnClickListener { permissionLauncher.launch(healthPermissions) }
        }
        syncButton = Button(this).apply {
            text = "Jetzt mit WRCH synchronisieren"
            setOnClickListener { lifecycleScope.launch { syncAll() } }
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 18, 0, 14)
        }
        values = TextView(this).apply {
            textSize = 20f
            setPadding(0, 12, 0, 0)
        }

        listOf(email,password,loginButton,logoutButton,status,permissionButton,syncButton,values)
            .forEach(box::addView)

        setContentView(scroll)
        updateUiForSession(api.savedSession() != null)
        lifecycleScope.launch { updateState() }
    }

    private fun login() {
        val e = email.text.toString().trim()
        val p = password.text.toString()
        if (e.isBlank() || p.isBlank()) {
            status.text = "Bitte E-Mail und Passwort eingeben."
            return
        }
        loginButton.isEnabled = false
        status.text = "Anmeldung läuft…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.signIn(e, p) }
                password.text.clear()
                updateUiForSession(true)
                status.text = "WRCH-Konto verbunden."
                updateState()
            } catch (t: Throwable) {
                status.text = "Anmeldung fehlgeschlagen: ${t.message}"
            } finally {
                loginButton.isEnabled = true
            }
        }
    }

    private fun updateUiForSession(loggedIn: Boolean) {
        email.visibility = if (loggedIn) View.GONE else View.VISIBLE
        password.visibility = if (loggedIn) View.GONE else View.VISIBLE
        loginButton.visibility = if (loggedIn) View.GONE else View.VISIBLE
        logoutButton.visibility = if (loggedIn) View.VISIBLE else View.GONE
        permissionButton.isEnabled = loggedIn
        syncButton.isEnabled = loggedIn
    }

    private suspend fun updateState() {
        if (api.savedSession() == null) {
            status.text = "Noch nicht mit deinem WRCH-Konto verbunden."
            return
        }
        val sdk = HealthConnectClient.getSdkStatus(this, providerPackage)
        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            status.text = "WRCH ist angemeldet, aber Health Connect ist auf diesem Gerät noch nicht verfügbar."
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(healthPermissions)) {
            status.text = "WRCH ist angemeldet. Als Nächstes Health Connect freigeben."
            return
        }
        status.text = "WRCH + Health Connect sind bereit. Du kannst jetzt synchronisieren."
        readTodayPreview(client)
    }

    private suspend fun readTodayPreview(client: HealthConnectClient) {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant()
        val end = Instant.now()
        val steps = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )[StepsRecord.COUNT_TOTAL] ?: 0L
        values.text = "Heute bisher: ${steps.toString().chunked(3).joinToString(".")} Schritte"
    }

    private suspend fun syncAll() {
        syncButton.isEnabled = false
        status.text = "Health-Daten werden gelesen und mit WRCH synchronisiert…"

        try {
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

                activityRows.put(JSONObject()
                    .put("user_id", session.userId)
                    .put("activity_date", date.toString())
                    .put("provider", "health_connect")
                    .put("steps", steps)
                    .put("source_label", "Health Connect")
                    .put("synced_at", Instant.now().toString()))
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
                weightRows.put(JSONObject()
                    .put("user_id", session.userId)
                    .put("provider", "health_connect")
                    .put("external_id", w.metadata.id)
                    .put("measured_at", w.time.toString())
                    .put("weight_kg", w.weight.inKilograms)
                    .put("source_label", origin)
                    .put("synced_at", Instant.now().toString()))
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
                healthActivities.put(JSONObject()
                    .put("external_id", w.metadata.id)
                    .put("activity_type", mapped.first)
                    .put("title", w.title?.takeIf { it.isNotBlank() } ?: mapped.second)
                    .put("duration_minutes", minutes)
                    .put("distance_km", if (distanceMeters != null && distanceMeters > 0) distanceMeters / 1000.0 else JSONObject.NULL)
                    .put("performed_on", performedDate.toString())
                    .put("source_label", origin)
                    .put("notes", "Automatisch aus Health Connect importiert"))
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
            status.text = "Synchronisierung abgeschlossen ✓"
            values.text = buildString {
                append("Heute: ${todaySteps.toString().reversed().chunked(3).joinToString(".").reversed()} Schritte\n")
                append("Gewichtseinträge: ${weightRows.length()}\n")
                append("Aktivitäten (30 Tage): ${healthActivities.length()}\n\n")
                append("Öffne jetzt WRCH – importierte Aktivitäten erscheinen in Move/Train und können in Challenges sowie im Freunde-Feed zählen.")
            }
        } catch (t: Throwable) {
            status.text = "Synchronisierung fehlgeschlagen: ${t.message}"
            val s = api.savedSession()
            if (s != null) {
                try {
                    withContext(Dispatchers.IO) {
                        api.updateConnection(s, "error", JSONArray(), t.message)
                    }
                } catch (_: Throwable) {}
            }
        } finally {
            syncButton.isEnabled = true
        }
    }

    private fun mapExercise(type: Int): Pair<String,String> = when(type) {
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

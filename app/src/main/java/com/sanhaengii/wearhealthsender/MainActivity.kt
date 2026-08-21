package com.sanhaengii.wearhealthsender

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val credentialStore by lazy { SecureCredentialStore(this) }
    private var trackingService: HealthTrackingService? = null
    private var trackingStateJob: Job? = null
    private var isTrackingServiceBound = false

    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var etaInput: EditText
    private lateinit var distanceInput: EditText
    private lateinit var payloadText: TextView
    private lateinit var resultText: TextView
    private lateinit var startExerciseButton: Button
    private lateinit var stopExerciseButton: Button
    private lateinit var sendButton: Button
    private lateinit var syncWatchButton: Button
    private lateinit var startAndSendButton: Button
    private lateinit var measureSpo2Button: Button
    private lateinit var autoSendButton: Button
    private lateinit var finishRescueButton: Button
    private lateinit var rescueLayout: LinearLayout

    private var currentPayload = HealthServicesPayload.empty()
    private var isExerciseRunning = false
    private var isSending = false
    private var isRescueMode = false
    private var isHikingActive = false
    private var isPeriodicSendingEnabled = false
    private var sendOnNextUpdate = false
    private var pendingPermissionAction = PendingPermissionAction.NONE
    private var spo2SourceText = "service 연결 중"

    private val trackingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? HealthTrackingService.LocalBinder)?.service ?: return
            trackingService = service
            isTrackingServiceBound = true
            trackingStateJob?.cancel()
            trackingStateJob = activityScope.launch {
                service.state.collect { tracking ->
                    val previousPayload = currentPayload
                    currentPayload = tracking.payload
                    isExerciseRunning = tracking.isActive
                    isHikingActive = tracking.isActive
                    isPeriodicSendingEnabled = tracking.isActive && !tracking.isPaused
                    spo2SourceText = tracking.sensorStatus
                    updateExerciseButtons()
                    updateAutoSendButton()
                    renderPayload()
                    if (tracking.payload != previousPayload) syncDataToWatch()
                    if (sendOnNextUpdate && tracking.payload.hasCollectedRequiredValues()) {
                        sendOnNextUpdate = false
                        sendCollectedPayload(requireHiking = true)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingStateJob?.cancel()
            trackingService = null
            isTrackingServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        renderPayload()
        updateExerciseButtons()
        bindService(
            Intent(this, HealthTrackingService::class.java),
            trackingServiceConnection,
            Context.BIND_AUTO_CREATE,
        )
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onDestroy() {
        trackingStateJob?.cancel()
        if (isTrackingServiceBound) {
            unbindService(trackingServiceConnection)
            isTrackingServiceBound = false
        }
        Wearable.getMessageClient(this).removeListener(this)
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/sos_triggered") {
            mainHandler.post {
                enterRescueMode()
            }
        }
    }

    private fun enterRescueMode() {
        isRescueMode = true
        rescueLayout.visibility = ViewGroup.VISIBLE
        appendResult("🚨 SOS TRIGGERED FROM WATCH! Rescue protocol initiated.")
    }

    private fun finishRescueProtocol() {
        isRescueMode = false
        rescueLayout.visibility = ViewGroup.GONE
        appendResult("✅ Rescue protocol finished. Notifying watch.")
        
        val putDataMapReq = PutDataMapRequest.create("/sos_status")
        putDataMapReq.dataMap.putString("status", "finished")
        putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(this).putDataItem(putDataReq)
    }

    private fun syncDataToWatch() {
        val eta = etaInput.text.toString().ifBlank { "-" }
        val distance = distanceInput.text.toString().ifBlank { "-" }
        val bpm = currentPayload.heartRate ?: 0

        val putDataMapReq = PutDataMapRequest.create("/hiking_info")
        putDataMapReq.dataMap.putString("eta", eta)
        putDataMapReq.dataMap.putString("distance", distance)
        putDataMapReq.dataMap.putInt("bpm", bpm)
        putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
        
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(this).putDataItem(putDataReq)
    }

    private fun createContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp(), 16.dp(), 18.dp(), 24.dp())
            setBackgroundColor(Color.rgb(15, 23, 42))
        }

        rescueLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            setBackgroundColor(Color.RED)
            visibility = ViewGroup.GONE
            
            addView(text("🚨 구조 프로토콜 실행 중", 16f, bold = true))
            addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, 8.dp()))
            finishRescueButton = button("구조 완료 및 워치 해제", Color.WHITE) {
                finishRescueProtocol()
            }
            addView(finishRescueButton)
        }
        root.addView(rescueLayout, fullWidthParams())
        root.addSpace(12)

        root.addView(text("SanHaengii", 18f, bold = true))
        root.addView(text("Wear Health Services sender", 12f, color = Color.rgb(203, 213, 225)))
        root.addSpace(12)

        root.addView(label("Backend URL"))
        baseUrlInput = input(BuildConfig.HEALTH_API_BASE_URL)
        root.addView(baseUrlInput)

        root.addSpace(8)
        root.addView(label("JWT credential"))
        tokenInput = input(credentialStore.load()?.token?.let { "저장됨 ···${it.takeLast(6)}" }.orEmpty()).apply {
            isEnabled = false
        }
        root.addView(tokenInput)

        root.addSpace(8)
        root.addView(label("User ID"))
        userIdInput = input(credentialStore.load()?.userId?.toString().orEmpty())
        root.addView(userIdInput)

        root.addSpace(12)
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(label("ETA (min)"))
            etaInput = input("30")
            addView(etaInput)
        }
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8.dp(), 0, 0, 0)
            addView(label("Distance (km)"))
            distanceInput = input("2.5")
            addView(distanceInput)
        }
        row.addView(col1)
        row.addView(col2)
        root.addView(row)

        root.addSpace(12)
        payloadText = text("", 12f, color = Color.rgb(226, 232, 240)).apply {
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.rgb(30, 41, 59))
        }
        root.addView(payloadText, fullWidthParams())

        root.addSpace(10)
        
        syncWatchButton = button("Sync to Watch Now", Color.rgb(167, 139, 250)) {
            syncDataToWatch()
            appendResult("Manually synced hiking info to watch.")
        }
        root.addView(syncWatchButton)
        root.addSpace(8)

        startExerciseButton = button("Start hiking", Color.rgb(56, 189, 248)) {
            startExercise()
        }
        root.addView(startExerciseButton)

        root.addSpace(8)
        stopExerciseButton = button("Stop hiking", Color.rgb(148, 163, 184)) {
            stopExercise()
        }
        root.addView(stopExerciseButton)

        root.addSpace(8)
        measureSpo2Button = button("Measure SpO2", Color.rgb(125, 211, 252)) {
            requestSpo2Measurement()
        }
        root.addView(measureSpo2Button)

        root.addSpace(8)
        sendButton = button("Send once", Color.rgb(34, 197, 94)) {
            sendCollectedPayload(requireHiking = true)
        }
        root.addView(sendButton)

        root.addSpace(8)
        startAndSendButton = button("Start & send next", Color.rgb(250, 204, 21)) {
            sendOnNextUpdate = true
            startExercise()
            appendResult("Will send after the next Health Services update.")
        }
        root.addView(startAndSendButton)

        root.addSpace(8)
        autoSendButton = button("3s backend send: OFF", Color.rgb(148, 163, 184)) {
            togglePeriodicSending()
        }
        root.addView(autoSendButton)

        root.addSpace(8)
        val openDashboardButton = button("Open Dashboard (Compose)", Color.rgb(99, 102, 241)) {
            val intent = Intent(this@MainActivity, ComposeMainActivity::class.java)
            intent.putExtra("bpm", currentPayload.heartRate ?: 0)
            intent.putExtra("eta", etaInput.text.toString())
            intent.putExtra("distance", distanceInput.text.toString())
            startActivity(intent)
        }
        root.addView(openDashboardButton)

        root.addSpace(12)
        resultText = text(
            "Ready. Start a Health Services exercise to receive emulator/sensor updates.",
            11f,
            color = Color.rgb(226, 232, 240),
        ).apply {
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.rgb(2, 6, 23))
        }
        root.addView(resultText, fullWidthParams())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun startExercise() {
        if (!hasRequiredPermissions()) {
            pendingPermissionAction = PendingPermissionAction.START_EXERCISE
            requestPermissions(requiredPermissions(), HEALTH_SERVICES_PERMISSION_REQUEST)
            return
        }

        if (isExerciseRunning) {
            appendResult("Health Services exercise is already running.")
            return
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, HealthTrackingService::class.java).setAction(HealthTrackingService.ACTION_START),
        )
        appendResult("Foreground service starting. Health data will POST every 3 seconds.")
    }

    private fun stopExercise() {
        if (!isExerciseRunning) {
            appendResult("Health Services exercise is not running.")
            return
        }

        startService(Intent(this, HealthTrackingService::class.java).setAction(HealthTrackingService.ACTION_STOP))
        sendOnNextUpdate = false
        appendResult("Foreground service stop requested.")
    }

    private fun requestSpo2Measurement() {
        if (!hasRequiredPermissions()) {
            pendingPermissionAction = PendingPermissionAction.MEASURE_SPO2
            requestPermissions(requiredPermissions(), HEALTH_SERVICES_PERMISSION_REQUEST)
            return
        }

        if (trackingService?.requestSpo2Measurement() == true) {
            appendResult("Samsung SpO2 measurement requested through foreground service.")
        } else {
            appendResult("SpO2 provider is not ready or unavailable.")
        }
    }

    private fun sendCollectedPayload(requireHiking: Boolean): Boolean {
        if (requireHiking && !isHikingActive) {
            appendResult("Start hiking before sending health data.")
            return false
        }

        if (isSending) {
            appendResult("Send skipped because a previous request is still running.")
            return false
        }

        currentPayload = stablePayloadForSend()
        renderPayload()

        if (!currentPayload.hasCollectedRequiredValues()) {
            appendResult("Waiting for collected payload. Missing: ${currentPayload.missingRequiredFields()}")
            return false
        }

        sendCurrentPayload()
        return true
    }

    private fun sendCurrentPayload() {
        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
        val token = credentialStore.load()?.token.orEmpty()
        val userId = userIdInput.text.toString().trim().toLongOrNull()

        if (baseUrl.isBlank()) {
            appendResult("Backend URL is empty.")
            return
        }

        if (userId == null || userId <= 0L) {
            appendResult("User ID must be a positive number.")
            return
        }

        setSending(true)

        val payloadForSend = stablePayloadForSend()
        currentPayload = payloadForSend
        renderPayload()

        appendResult("POST $baseUrl/health/data")

        Thread {
            val result = runCatching {
                val response = HealthDataRepository(
                    apiBaseUrl = baseUrl,
                    relayBaseUrl = "",
                    allowCleartext = BuildConfig.DEBUG,
                ).postHealthData(payloadForSend, userId, token)
                "HTTP ${response.code}\n${response.body}"
            }

            mainHandler.post {
                setSending(false)
                result
                    .onSuccess { appendResult(it) }
                    .onFailure { appendResult("ERROR: ${it.message ?: it.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun stablePayloadForSend(): HealthServicesPayload {
        return currentPayload.copy(
            measuredAt = nowKstIsoString(),
            bodyTemp = null,
        )
    }

    private fun renderPayload() {
        val sendState = if (isHikingActive && isPeriodicSendingEnabled) "ON" else "OFF"
        payloadText.text = """
            ${currentPayload.toDisplayText()}
            User ID: ${userIdInput.text.toString().ifBlank { "-" }}
            Hiking: ${if (isHikingActive) "active" else "inactive"}
            3s backend send: $sendState
            SpO2 source: $spo2SourceText
        """.trimIndent()
    }

    private fun setSending(isSending: Boolean) {
        this.isSending = isSending
        sendButton.isEnabled = !isSending
        startAndSendButton.isEnabled = !isSending
        sendButton.text = if (isSending) "Sending..." else "Send once"
    }

    private fun updateExerciseButtons() {
        startExerciseButton.isEnabled = !isExerciseRunning
        stopExerciseButton.isEnabled = isExerciseRunning
    }

    private fun updateSpo2Button() {
        if (!::measureSpo2Button.isInitialized) {
            return
        }
        measureSpo2Button.text = "Measure SpO2"
    }

    private fun togglePeriodicSending() {
        if (!isHikingActive) {
            appendResult("Start hiking before enabling 3-second backend sending.")
            startExercise()
            return
        }

        if (isPeriodicSendingEnabled) {
            startService(Intent(this, HealthTrackingService::class.java).setAction(HealthTrackingService.ACTION_PAUSE))
        } else {
            startService(Intent(this, HealthTrackingService::class.java).setAction(HealthTrackingService.ACTION_RESUME))
        }
    }

    private fun updateAutoSendButton() {
        autoSendButton.text = if (isPeriodicSendingEnabled) {
            "3s backend send: ON"
        } else {
            "3s backend send: OFF"
        }
        autoSendButton.setBackgroundColor(
            if (isPeriodicSendingEnabled) Color.rgb(251, 146, 60) else Color.rgb(148, 163, 184),
        )
    }

    private fun appendResult(message: String) {
        val now = nowKstTimeText()
        resultText.text = "[$now] $message\n\n${resultText.text}"
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 36) {
            permissions += PERMISSION_READ_HEART_RATE
            permissions += PERMISSION_READ_OXYGEN_SATURATION
        } else {
            permissions += Manifest.permission.BODY_SENSORS
        }
        return permissions.toTypedArray()
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false,
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = true
            if (bold) {
                setTypeface(typeface, Typeface.BOLD)
            }
        }
    }

    private fun label(value: String): TextView {
        return text(value, 11f, Color.rgb(148, 163, 184)).apply {
            gravity = Gravity.START
        }
    }

    private fun input(value: String): EditText {
        return EditText(this).apply {
            setText(value)
            textSize = 11f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.rgb(15, 23, 42))
            setHintTextColor(Color.rgb(100, 116, 139))
            setBackgroundColor(Color.rgb(241, 245, 249))
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            layoutParams = fullWidthParams()
        }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.rgb(15, 23, 42))
            setBackgroundColor(color)
            setOnClickListener { onClick() }
            layoutParams = fullWidthParams()
        }
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun LinearLayout.addSpace(heightDp: Int) {
        addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, heightDp.dp()))
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == HEALTH_SERVICES_PERMISSION_REQUEST) {
            if (hasRequiredPermissions()) {
                val action = pendingPermissionAction
                pendingPermissionAction = PendingPermissionAction.NONE
                appendResult("Health sensor permissions granted.")
                when (action) {
                    PendingPermissionAction.START_EXERCISE -> startExercise()
                    PendingPermissionAction.MEASURE_SPO2 -> requestSpo2Measurement()
                    PendingPermissionAction.NONE -> Unit
                }
            } else {
                pendingPermissionAction = PendingPermissionAction.NONE
                appendResult("Health sensor permissions were not granted.")
            }
        }
    }

    companion object {
        private const val HEALTH_SERVICES_PERMISSION_REQUEST = 30
        private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val PERMISSION_READ_OXYGEN_SATURATION =
            "android.permission.health.READ_OXYGEN_SATURATION"
    }
}

private enum class PendingPermissionAction {
    NONE,
    START_EXERCISE,
    MEASURE_SPO2,
}

private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

private fun nowKstTimeText(): String {
    return OffsetDateTime.now(KST_ZONE)
        .toLocalTime()
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()
}

@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.sanhaengii.wearhealthsender

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.sanhaengii.wearhealthsender.ui.AlertScreen
import com.sanhaengii.wearhealthsender.ui.BackendTestEntryScreen
import com.sanhaengii.wearhealthsender.ui.MainDashboard
import com.sanhaengii.wearhealthsender.ui.PageIndicator
import com.sanhaengii.wearhealthsender.ui.SosScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.Locale

class ComposeMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationProvider by lazy { OneShotLocationProvider(this) }
    private lateinit var mainViewModel: MainViewModel
    private var trackingService: HealthTrackingService? = null
    private var isTrackingServiceBound = false
    private var trackingStateJob: Job? = null
    private var trackingEventJob: Job? = null
    private var currentPayload = HealthServicesPayload.empty()
    private val credentialStore by lazy { SecureCredentialStore(this) }
    private val repository by lazy {
        HealthDataRepository(
            apiBaseUrl = BuildConfig.HEALTH_API_BASE_URL,
            relayBaseUrl = BuildConfig.TRAIL_API_BASE_URL,
            allowCleartext = BuildConfig.DEBUG,
        )
    }
    @Volatile private var currentCredentials: WatchCredentials? = null
    private val remoteHealthSampleGate = HealthSampleGate(
        maxAgeMillis = REMOTE_HEALTH_SAMPLE_MAX_AGE_MS,
        futureToleranceMillis = REMOTE_HEALTH_SAMPLE_FUTURE_TOLERANCE_MS,
    )
    // 동일 종류의 이상 상태는 정상값이 다시 관측되기 전까지 하나의 에피소드로 취급한다.
    private var localAnomalyEpisodeType: AnomalyType? = null
    private var remoteAnomalyEpisodeType: AnomalyType? = null
    private var backendAnomalyEpisodeType: AnomalyType? = null
    private var mobileAnomalyEpisodeRecordId: Long? = null
    // 모바일이 소유한 산행 레코드 id (상태 PUT 대상). 서버 폴링으로 채워짐
    @Volatile private var activeHikingRecordId: Long? = null
    // 현재 워치가 따라가고 있는(동기화 중인) 산행 레코드 id
    @Volatile private var syncedHikingRecordId: Long? = null
    private var hikingStartedAtMs = 0L
    private var totalHikingMinutes = 0
    private var totalHikingDistanceKm = 0.0

    private val trackingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? HealthTrackingService.LocalBinder)?.service ?: return
            trackingService = service
            isTrackingServiceBound = true
            trackingStateJob?.cancel()
            trackingEventJob?.cancel()
            trackingStateJob = scope.launch {
                service.state.collect { tracking ->
                    val wasActive = mainViewModel.isHikingActive
                    currentPayload = tracking.payload
                    mainViewModel.updateHikingActive(tracking.isActive)
                    mainViewModel.updatePaused(tracking.isPaused)
                    tracking.payload.heartRate?.let(mainViewModel::updateHeartRate)
                    runLocalAnomalyCheck()
                    if (!wasActive && tracking.isActive) fetchHikingStatusAndStartCountdown()
                }
            }
            trackingEventJob = scope.launch {
                service.events.collect { event ->
                    when (event) {
                        is HealthTrackingEvent.HealthResponse ->
                            handleHealthDataResponse(event.responseBody, event.payload)
                        is HealthTrackingEvent.Error -> Log.w(SENSOR_LOG_TAG, event.message)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isTrackingServiceBound = false
            trackingService = null
            trackingStateJob?.cancel()
            trackingEventJob?.cancel()
        }
    }

    private val hikingStatusRunnable = object : Runnable {
        override fun run() {
            if (mainViewModel.isHikingActive) {
                // 1) 마지막 동기화 기준점으로 분 단위 카운트다운 (polling 사이에도 줄어듦)
                refreshEtaFromElapsed()
                // 2) 모바일 ETA 알고리즘 최신값으로 재동기화
                fetchRelayStatus()
                mainHandler.postDelayed(this, HIKING_STATUS_INTERVAL_MS)
            }
        }
    }
    // 모바일 로그인 토큰을 주기적으로 Flask relay에서 받아옴 (산행 여부와 무관하게 상시 동작)
    private val credentialSyncRunnable = object : Runnable {
        override fun run() {
            fetchWatchCredentials()
            mainHandler.postDelayed(this, CREDENTIAL_SYNC_INTERVAL_MS)
        }
    }
    // 모바일 산행 상태(active/paused/completed)를 상시 폴링해 워치에 반영 (시작/정지/중단 동기화)
    private val mobileHikingSyncRunnable = object : Runnable {
        override fun run() {
            syncHikingStateFromServer()
            mainHandler.postDelayed(this, MOBILE_SYNC_INTERVAL_MS)
        }
    }
    // DB 최신 건강 데이터를 상시 폴링해 이상징후 감지 → 워치에 AlertScreen 표시
    // 산행 여부와 무관하게 항상 동작 (모바일 WatchHealthContext 역할을 워치에서 직접 수행)
    private val healthAnomalyPollingRunnable = object : Runnable {
        override fun run() {
            pollHealthDataForAnomaly()
            mainHandler.postDelayed(this, HEALTH_ANOMALY_POLL_INTERVAL_MS)
        }
    }
    private val anomalyCountdownRunnable = object : Runnable {
        override fun run() {
            val finished = mainViewModel.tickCountdown()
            if (finished) {
                autoSendEmergencyFromAnomaly()
            } else if (mainViewModel.emergencyState.phase == EmergencyPhase.COUNTDOWN) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentCredentials = credentialStore.load()
        
        setContent {
            mainViewModel = viewModel()
            
            // 초기값 설정 및 Wearable 데이터 로드
            LaunchedEffect(Unit) {
                // ... (existing logic)
            }

            MaterialTheme {
                val pagerState = rememberPagerState(pageCount = { 3 })

                if (mainViewModel.isEmergencyVisible) {
                    val phase = mainViewModel.emergencyState.phase
                    val isSending = phase == EmergencyPhase.SENDING
                    val canAct = phase == EmergencyPhase.COUNTDOWN || phase == EmergencyPhase.FAILED
                    AlertScreen(
                        message = mainViewModel.anomalyMessage,
                        isWarning = true,
                        countdown = if (isSending) null else mainViewModel.anomalyCountdown,
                        cancelLabel = if (mainViewModel.isMobileAnomalySource) "괜찮아요" else "취소",
                        emergencySendState = mainViewModel.emergencySendState,
                        // 전송 중엔 버튼 비활성 (중복 탭 방지)
                        onConfirm = if (canAct) {{ confirmEmergency() }} else null,
                        onCancel = if (canAct) {{ cancelEmergency() }} else null,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            when (page) {
                                0 -> MainDashboard(
                                    bpm = mainViewModel.bpm,
                                    eta = mainViewModel.eta,
                                    distance = mainViewModel.distance
                                )
                                1 -> SosScreen(
                                    isHikingActive = mainViewModel.isHikingActive,
                                    isPaused = mainViewModel.isPaused,
                                    onHikingToggle = { toggleHikingFromWatch() },
                                    onAbort = { abortHikingFromWatch() },
                                    onSosClick = {
                                        if (sendEmergencyManual()) notifySosTriggered()
                                    }
                                )
                                2 -> BackendTestEntryScreen(
                                    onOpenBackendTest = {
                                        startActivity(
                                            Intent(this@ComposeMainActivity, MainActivity::class.java)
                                        )
                                    }
                                )
                            }
                        }
                        PageIndicator(
                            currentPage = pagerState.currentPage,
                            pageCount = 3,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }

        Wearable.getDataClient(this).addListener(this)
        bindService(
            Intent(this, HealthTrackingService::class.java),
            trackingServiceConnection,
            Context.BIND_AUTO_CREATE,
        )

        // 암호화 저장소를 기준으로 모바일 로그인 자격증명을 상시 동기화한다.
        mainHandler.post(credentialSyncRunnable)
        // 모바일 산행 상태 상시 동기화 시작 (모바일 시작/정지/중단 → 워치 반영)
        mainHandler.post(mobileHikingSyncRunnable)
        // DB 건강 데이터 상시 폴링 → 이상징후 워치 알림 (산행 여부 무관)
        mainHandler.postDelayed(healthAnomalyPollingRunnable, 5_000L)
    }

    private fun notifySosTriggered() {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@ComposeMainActivity).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@ComposeMainActivity)
                        .sendMessage(node.id, "/sos_triggered", "SOS".toByteArray())
                        .await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // SOS 화면 5초 롱프레스 → 수동 긴급 신고
    private fun sendEmergencyManual(): Boolean {
        val alert = EmergencyAlert(
            anomalyType = AnomalyType.MANUAL_SOS,
            message = "사용자가 긴급 구조를 요청했습니다",
            source = EmergencySource.MANUAL_SOS,
        )
        if (!mainViewModel.startEmergencyAlert(alert, countdownSeconds = 0)) return false
        val activeAlert = mainViewModel.beginEmergencySend(EmergencyTrigger.MANUAL_LONG_PRESS) ?: return false
        sendEmergencyRequest(activeAlert, EmergencyTrigger.MANUAL_LONG_PRESS)
        return true
    }

    // Suspend helper: request a single location update with timeout
    private suspend fun getLocationWithTimeout(timeoutMs: Long): android.location.Location? {
        return locationProvider.getLocation(timeoutMs)
    }

    /**
     * 백엔드 /api/emergency 필수 필드를 항상 포함하는 JSON body 생성.
     *  - userId : 필수. 비어있으면 null이 아니라 예외를 던져 호출부에서 "failed" 처리.
     *  - eventType, location, timestamp : 항상 포함.
     *  - 추가 필드(triggeredBy, reason)는 옵션.
     */
    private fun buildEmergencyBody(
        eventType: String,
        lat: Double,
        lng: Double,
        triggeredBy: String? = null,
        reason: String? = null,
    ): String {
        val userIdVal = currentUserId().trim()
        check(userIdVal.isNotBlank()) {
            "userId가 비어있습니다. npm run sync-watch 실행 후 워치 앱을 재빌드하세요."
        }
        val timestamp = java.time.Instant.now().toString()
        return buildString {
            append("{")
            append("\"userId\":$userIdVal,")
            append("\"eventType\":\"$eventType\",")
            if (triggeredBy != null) append("\"triggered_by\":\"$triggeredBy\",")
            if (reason != null) append("\"reason\":${escapeJson(reason)},")
            append("\"location\":{\"lat\":$lat,\"lng\":$lng},")
            append("\"timestamp\":\"$timestamp\"")
            append("}")
        }
    }

    /** JSON 문자열 값의 제어문자·특수문자를 이스케이프합니다. */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun postEmergency(token: String, body: String): Boolean {
        val response = repository.postEmergency(body, token)
        Log.d(SENSOR_LOG_TAG, "Emergency response=${response.code}")
        return response.isSuccessful
    }

    override fun onDataChanged(dataEvents: com.google.android.gms.wearable.DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                if (path == "/hiking_info") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val eta = dataMap.getString("eta", "-")
                    val distance = dataMap.getString("distance", "-")
                    val bpm = dataMap.getInt("bpm", 0)
                    mainViewModel.updateEta(eta)
                    mainViewModel.updateDistance(distance)
                    if (bpm > 0) mainViewModel.updateHeartRate(bpm)
                } else if (path == "/sos_status") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val status = dataMap.getString("status")
                    if (status == "finished") {
                        mainViewModel.resetEmergency()
                    }
                }
            }
        }
    }

    // 시작 / 정지(일시정지) / 재개 토글
    private fun toggleHikingFromWatch() {
        if (!mainViewModel.isHikingActive) {
            startHikingFromWatch()
            // 모바일 레코드가 이미 있으면 active로 동기화(없으면 id 없어 no-op)
            putHikingStatus("active")
        } else if (!mainViewModel.isPaused) {
            pauseHikingFromWatch()
        } else {
            resumeHikingFromWatch()
        }
    }

    // 정지 = 일시정지 (세션 유지, 양쪽 동기화)
    private fun pauseHikingFromWatch() {
        applyPauseLocal()
        putHikingStatus("paused")
    }

    private fun resumeHikingFromWatch() {
        applyResumeLocal()
        putHikingStatus("active")
    }

    // 일시정지 로컬 적용(전송 중단·ETA 카운트다운 정지). 서버 PUT은 호출측에서 결정
    private fun applyPauseLocal() {
        mainViewModel.updatePaused(true)
        sendTrackingAction(HealthTrackingService.ACTION_PAUSE)
        mainHandler.removeCallbacks(hikingStatusRunnable)
    }

    private fun applyResumeLocal() {
        mainViewModel.updatePaused(false)
        sendTrackingAction(HealthTrackingService.ACTION_RESUME)
        mainHandler.removeCallbacks(hikingStatusRunnable)
        mainHandler.postDelayed(hikingStatusRunnable, HIKING_STATUS_INTERVAL_MS)
    }

    // 중단하기 = 완전 종료(기록 저장 status=completed). 양쪽 종료
    private fun abortHikingFromWatch() {
        putHikingStatus("completed")
        syncedHikingRecordId = null
        stopHikingFromWatch()
    }

    // 산행 레코드 status를 백엔드에 PUT (active/paused/completed). recordId 없으면 무시
    private fun putHikingStatus(status: String) {
        val recordId = activeHikingRecordId ?: return
        val token = currentToken()
        scope.launch(Dispatchers.IO) {
            runCatching { repository.updateHikingStatus(recordId, status, token) }
        }
    }

    // 모바일 산행 상태를 폴링해 워치에 반영 (시작/정지/중단 동기화)
    /**
     * DB의 최신 건강 데이터(/health/data/latest)를 10초마다 폴링해 이상징후를 감지합니다.
     * 산행 여부와 무관하게 항상 동작. 모바일 WatchHealthContext와 동일한 임계값 사용.
     * 감지 시 → 진동 + AlertScreen 표시 + 30초 카운트다운 → /api/emergency 자동 신고.
     */
    private fun pollHealthDataForAnomaly() {
        val token = currentToken()
        if (token.isBlank()) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val sample = repository.fetchLatestHealthData(token) ?: return@runCatching
                val decision = remoteHealthSampleGate.evaluate(
                    sample = sample.identity,
                    expectedUserId = currentUserId().toLongOrNull(),
                    nowEpochMs = System.currentTimeMillis(),
                )
                if (decision is HealthSampleDecision.Rejected) {
                    if (decision.reason != HealthSampleRejection.DUPLICATE) {
                        Log.w(SENSOR_LOG_TAG, "Latest health sample rejected: ${decision.reason}")
                    }
                    return@runCatching
                }

                val payload = sample.payload
                val anomaly = detectAnomaly(payload)
                mainHandler.post {
                    if (anomaly == null) {
                        remoteAnomalyEpisodeType = null
                    } else if (isKnownAnomalyEpisode(anomaly.type)) {
                        remoteAnomalyEpisodeType = anomaly.type
                    } else {
                        val started = handleAnomalyDetected(
                            anomaly = anomaly,
                            sosRequestId = null,
                            source = EmergencySource.REMOTE_HEALTH_DATA,
                        )
                        if (started) {
                            remoteAnomalyEpisodeType = anomaly.type
                            Log.w(SENSOR_LOG_TAG, "Fresh remote anomaly accepted: ${anomaly.message}")
                        } else {
                            // 다른 신고 처리 중이면 이 최신 샘플을 소모하지 않고 다음 폴링에서 재시도한다.
                            remoteHealthSampleGate.release(sample.identity.fingerprint)
                        }
                    }
                }
            }.onFailure {
                // 네트워크 실패는 조용히 무시 (폴러가 계속 실행되도록)
            }
        }
    }

    private fun syncHikingStateFromServer() {
        val userId = currentUserId().toLongOrNull() ?: return
        val token = currentToken()

        scope.launch(Dispatchers.IO) {
            runCatching {
                val records = repository.filterHikingRecords(userId, token)
                // 진행 중(active/paused/anomaly) 레코드 중 가장 최근 1건
                val ongoing = records
                    .filter {
                        val s = it.status
                        s == "active" || s == "paused" || s == "anomaly"
                    }
                    .maxByOrNull { it.startedAt }

                // ETA/거리는 센서 시작 여부와 무관하게 항상 갱신 (모바일이 PUT한 값 반영)
                val etaMin = ongoing?.durationMinutes ?: Double.NaN
                val remainKm = ongoing?.distanceKm ?: Double.NaN

                mainHandler.post {
                    if (ongoing != null) {
                        val recordId = ongoing.id
                        activeHikingRecordId = recordId
                        syncedHikingRecordId = recordId
                        // 모바일이 보낸 잔여 ETA/거리를 대시보드에 반영 (권한/센서 없이도 표시)
                        if (!etaMin.isNaN() && etaMin >= 0) {
                            totalHikingMinutes = etaMin.toInt()
                            hikingStartedAtMs = System.currentTimeMillis()
                            mainViewModel.updateEta("${etaMin.toLong()}분")
                        }
                        if (!remainKm.isNaN() && remainKm >= 0) {
                            totalHikingDistanceKm = remainKm
                            mainViewModel.updateDistance(String.format(Locale.ROOT, "%.2f", remainKm) + "km")
                        }
                        println("[Relay] ETA/거리 동기화: eta=${etaMin}분, dist=${remainKm}km (record=$recordId)")
                        when (ongoing.status) {
                            "active" -> {
                                mobileAnomalyEpisodeRecordId = null
                                if (!mainViewModel.isHikingActive) {
                                    // 모바일에서 산행 시작 → 워치 자동 반영
                                    startHikingFromWatch()
                                } else if (mainViewModel.isPaused) {
                                    // 모바일에서 재개됨
                                    applyResumeLocal()
                                }
                            }
                            "paused" -> {
                                mobileAnomalyEpisodeRecordId = null
                                if (mainViewModel.isHikingActive && !mainViewModel.isPaused) {
                                    // 모바일에서 일시정지됨
                                    applyPauseLocal()
                                }
                            }
                            "anomaly" -> {
                                // 같은 산행 레코드의 anomaly 상태는 한 번만 알린다.
                                if (mobileAnomalyEpisodeRecordId != recordId) {
                                    val started = handleAnomalyDetected(
                                        anomaly = DetectedAnomaly(
                                            AnomalyType.MOBILE_REPORTED,
                                            "이상징후가 감지되었습니다\n괜찮으시면 취소를 눌러주세요",
                                        ),
                                        sosRequestId = null,
                                        source = EmergencySource.MOBILE_SYNC,
                                    )
                                    if (started) mobileAnomalyEpisodeRecordId = recordId
                                }
                            }
                        }
                    } else {
                        // 진행 중 레코드 없음: 따라가던 산행이 종료(중단/완료)된 것으로 보고 워치도 종료
                        if (syncedHikingRecordId != null && mainViewModel.isHikingActive) {
                            stopHikingFromWatch()
                        }
                        syncedHikingRecordId = null
                        activeHikingRecordId = null
                        mobileAnomalyEpisodeRecordId = null
                    }
                }
            }.onFailure { /* 네트워크 실패 시 무시 */ }
        }
    }

    private fun startHikingFromWatch() {
        if (!hasHealthPermissions()) {
            requestPermissions(healthPermissions(), HEALTH_PERMISSION_REQUEST)
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, HealthTrackingService::class.java).setAction(HealthTrackingService.ACTION_START),
        )
        requestNotificationPermissionIfNeeded()
        requestLocationPermissionIfNeeded()
    }

    private fun stopHikingFromWatch() {
        mainViewModel.updateHikingActive(false)
        sendTrackingAction(HealthTrackingService.ACTION_STOP)
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainHandler.removeCallbacks(hikingStatusRunnable)
        localAnomalyEpisodeType = null
        remoteAnomalyEpisodeType = null
        backendAnomalyEpisodeType = null
        mobileAnomalyEpisodeRecordId = null
        hikingStartedAtMs = 0L
        totalHikingMinutes = 0
        totalHikingDistanceKm = 0.0
        mainViewModel.updateEta("-")
        mainViewModel.updateDistance("-")
    }

    private fun sendTrackingAction(action: String) {
        startService(Intent(this, HealthTrackingService::class.java).setAction(action))
    }

    // 백엔드 /health/data 응답 파싱 → 이상 감지 시 구조 프로토콜 트리거
    private fun handleHealthDataResponse(responseBody: String, payload: HealthServicesPayload) {
        val (isAnomaly, sosRequestId) = parseHealthDataResponse(responseBody)
        if (!isAnomaly) {
            backendAnomalyEpisodeType = null
            return
        }

        val anomaly = detectAnomaly(payload)
            ?: DetectedAnomaly(AnomalyType.BACKEND_REPORTED, "생체 이상 징후 감지")
        if (isKnownAnomalyEpisode(anomaly.type)) {
            backendAnomalyEpisodeType = anomaly.type
            return
        }
        if (handleAnomalyDetected(anomaly, sosRequestId, EmergencySource.BACKEND_RESPONSE)) {
            backendAnomalyEpisodeType = anomaly.type
        }
    }

    // 전송 게이트와 무관하게 현재 수집된 생체값으로 이상징후를 점검한다.
    // 감지되면 자동으로 구조 프로토콜(30초 카운트다운 → /api/emergency)을 트리거한다.
    private fun runLocalAnomalyCheck() {
        val anomaly = detectAnomaly(currentPayload)
        if (anomaly == null) {
            localAnomalyEpisodeType = null
            return
        }
        if (isKnownAnomalyEpisode(anomaly.type)) {
            localAnomalyEpisodeType = anomaly.type
            return
        }
        if (handleAnomalyDetected(anomaly, null, EmergencySource.LOCAL_SENSOR)) {
            localAnomalyEpisodeType = anomaly.type
        }
    }

    private fun isKnownAnomalyEpisode(type: AnomalyType): Boolean {
        return type == localAnomalyEpisodeType ||
            type == remoteAnomalyEpisodeType ||
            type == backendAnomalyEpisodeType
    }

    private fun handleAnomalyDetected(
        anomaly: DetectedAnomaly,
        sosRequestId: Int?,
        source: EmergencySource,
    ): Boolean {
        val started = mainViewModel.startEmergencyAlert(
            EmergencyAlert(
                anomalyType = anomaly.type,
                message = anomaly.message,
                source = source,
                sosRequestId = sosRequestId,
            )
        )
        if (!started) return false
        startAnomalyCountdown()
        vibrateAlert()
        return true
    }

    private fun startAnomalyCountdown() {
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainHandler.postDelayed(anomalyCountdownRunnable, 1_000L)
    }

    /**
     * 이상징후 알림 진동 패턴:
     *   짧게×3회(사용자 주의 환기) → 1초 대기 → 다시 짧게×3회
     * 총 약 2초간 진동.
     */
    @Suppress("DEPRECATION")
    private fun vibrateAlert() {
        val pattern = longArrayOf(0, 300, 150, 300, 150, 300, 800, 300, 150, 300, 150, 300)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (e: Exception) {
            // 진동 기능 없는 기기에서 예외 무시
        }
    }

    private fun confirmEmergency() {
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        val source = mainViewModel.emergencyState.alert?.source ?: return
        val trigger = if (source == EmergencySource.MANUAL_SOS) {
            EmergencyTrigger.MANUAL_LONG_PRESS
        } else {
            EmergencyTrigger.USER_CONFIRM
        }
        val alert = mainViewModel.beginEmergencySend(trigger) ?: return
        sendEmergencyRequest(alert, trigger)
    }

    private fun cancelEmergency() {
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        val alert = mainViewModel.cancelEmergency() ?: return
        if (alert.source == EmergencySource.MOBILE_SYNC) {
            putHikingStatus("active")
        }
    }

    private fun autoSendEmergencyFromAnomaly() {
        val alert = mainViewModel.beginEmergencySend(EmergencyTrigger.AUTO_TIMEOUT) ?: return
        sendEmergencyRequest(alert, EmergencyTrigger.AUTO_TIMEOUT)
    }

    private fun sendEmergencyRequest(alert: EmergencyAlert, trigger: EmergencyTrigger) {
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val token = currentToken()
        if (baseUrl.isBlank()) {
            mainViewModel.markEmergencyFailed("Backend URL is empty")
            return
        }

        scope.launch {
            var lat = DEFAULT_EMERGENCY_LAT
            var lng = DEFAULT_EMERGENCY_LNG
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val loc = getLocationWithTimeout(5000L)
                    if (loc != null) { lat = loc.latitude; lng = loc.longitude }
                }
            } catch (_: Exception) {}

            val body = runCatching {
                val eventType = if (alert.source == EmergencySource.MANUAL_SOS) {
                    "수동_긴급_호출"
                } else {
                    "이상_징후"
                }
                buildEmergencyBody(
                    eventType = eventType,
                    lat = lat,
                    lng = lng,
                    triggeredBy = trigger.apiValue,
                    reason = alert.message,
                )
            }.getOrElse {
                mainViewModel.markEmergencyFailed(it.message)
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                runCatching { postEmergency(token, body) }.getOrDefault(false)
            }
            if (success) {
                if (mainViewModel.markEmergencySucceeded() && alert.source == EmergencySource.MOBILE_SYNC) {
                    // 워치 신고가 성공한 뒤에만 모바일의 별도 자동 신고를 해제한다.
                    putHikingStatus("active")
                }
                mainHandler.postDelayed({
                    val state = mainViewModel.emergencyState
                    if (state.phase == EmergencyPhase.SUCCESS && state.alert == alert) {
                        mainViewModel.resetEmergency()
                    }
                }, EMERGENCY_SUCCESS_DISPLAY_MS)
            } else {
                mainViewModel.markEmergencyFailed("Emergency request failed")
            }
        }
    }

    private fun refreshEtaFromElapsed() {
        if (totalHikingMinutes > 0 && hikingStartedAtMs > 0) {
            val elapsedMinutes = (System.currentTimeMillis() - hikingStartedAtMs) / 60_000L
            val remaining = (totalHikingMinutes - elapsedMinutes).coerceAtLeast(0L)
            mainViewModel.updateEta("${remaining}분")
        }
    }

    private fun currentToken(): String = currentCredentials?.token.orEmpty()

    private fun currentUserId(): String = currentCredentials?.userId?.toString().orEmpty()

    // Flask relay에서 모바일이 push한 최신 user_id+JWT를 받아 런타임/영구 저장에 반영
    private fun fetchWatchCredentials() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val credentials = repository.fetchWatchCredentials() ?: return@runCatching
                if (credentials != currentCredentials) {
                    credentialStore.save(credentials)
                    currentCredentials = credentials
                    Log.i(SENSOR_LOG_TAG, "Watch credentials refreshed for user ${credentials.userId}")
                }
            }.onFailure { /* Flask 미실행 시 무시, 기존 자격증명 유지 */ }
        }
    }

    // 배포된 Railway에서 모바일이 갱신한 active hiking_record의 '잔여 ETA/거리'를 읽어 워치에 반영.
    // (모바일 LiveMapScreen이 updateHikingProgress로 duration_minutes=잔여분, distance_km=잔여km를 10초마다 갱신)
    private fun fetchRelayStatus() {
        val token = currentToken()
        val userId = currentUserId().toLongOrNull() ?: return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val records = repository.filterHikingRecords(userId, token)
                // active 중 가장 최근 started_at 1건 선택
                val active = records.filter { it.status == "active" }
                    .maxByOrNull { it.startedAt } ?: return@runCatching

                // 상태 PUT 대상 레코드 id 캡처
                activeHikingRecordId = active.id

                val etaMin = active.durationMinutes ?: Double.NaN
                val remainKm = active.distanceKm ?: Double.NaN

                println("[Relay] ETA/거리 수신: eta=${etaMin}분, dist=${remainKm}km (record=${activeHikingRecordId})")
                mainHandler.post {
                    if (!etaMin.isNaN() && etaMin >= 0) {
                        // 모바일 알고리즘의 '남은 시간'을 새 카운트다운 기준점으로 재설정
                        totalHikingMinutes = etaMin.toInt()
                        hikingStartedAtMs = System.currentTimeMillis()
                        mainViewModel.updateEta("${etaMin.toLong()}분")
                    }
                    if (!remainKm.isNaN() && remainKm >= 0) {
                        totalHikingDistanceKm = remainKm
                        mainViewModel.updateDistance(String.format(Locale.ROOT, "%.2f", remainKm) + "km")
                    }
                }
            }.onFailure { /* 네트워크 실패 시 무시, 로컬 카운트다운 유지 */ }
        }
    }

    private fun fetchHikingStatusAndStartCountdown() {
        val token = currentToken()
        val userId = currentUserId().toLongOrNull() ?: return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val records = repository.filterHikingRecords(userId, token)
                val active = records.filter { it.status == "active" }
                    .maxByOrNull { it.startedAt }
                    ?: return@runCatching

                val distanceKm = active.distanceKm ?: 0.0
                val durationMin = active.durationMinutes?.toInt() ?: 0
                val startedAt = active.startedAt

                mainHandler.post {
                    if (distanceKm > 0) {
                        totalHikingDistanceKm = distanceKm
                        mainViewModel.updateDistance(String.format(Locale.ROOT, "%.1f", distanceKm) + "km")
                    }
                    if (durationMin > 0) totalHikingMinutes = durationMin
                    if (startedAt.isNotEmpty()) {
                        hikingStartedAtMs = try {
                            java.time.Instant.parse(startedAt).toEpochMilli()
                        } catch (_: Exception) { System.currentTimeMillis() }
                    }
                    refreshEtaFromElapsed()
                    fetchRelayStatus() // 모바일 실시간 값으로 즉시 덮어씌우기 시도
                    mainHandler.removeCallbacks(hikingStatusRunnable)
                    mainHandler.postDelayed(hikingStatusRunnable, HIKING_STATUS_INTERVAL_MS)
                }
            }.onFailure { it.printStackTrace() }
        }
    }

    private fun hasHealthPermissions(): Boolean {
        return healthPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun healthPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 36) {
            permissions += PERMISSION_READ_HEART_RATE
            permissions += PERMISSION_READ_OXYGEN_SATURATION
        } else {
            permissions += Manifest.permission.BODY_SENSORS
        }
        return permissions.toTypedArray()
    }

    private fun requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == HEALTH_PERMISSION_REQUEST && hasHealthPermissions()) {
            startHikingFromWatch()
        }
    }

    override fun onDestroy() {
        Wearable.getDataClient(this).removeListener(this)
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainHandler.removeCallbacks(hikingStatusRunnable)
        mainHandler.removeCallbacks(credentialSyncRunnable)
        mainHandler.removeCallbacks(mobileHikingSyncRunnable)
        mainHandler.removeCallbacks(healthAnomalyPollingRunnable)
        trackingStateJob?.cancel()
        trackingEventJob?.cancel()
        if (isTrackingServiceBound) {
            unbindService(trackingServiceConnection)
            isTrackingServiceBound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val HEALTH_PERMISSION_REQUEST = 41
        private const val LOCATION_PERMISSION_REQUEST = 42
        private const val NOTIFICATION_PERMISSION_REQUEST = 43
        private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val PERMISSION_READ_OXYGEN_SATURATION =
            "android.permission.health.READ_OXYGEN_SATURATION"
        private const val HIKING_STATUS_INTERVAL_MS = 10_000L
        private const val CREDENTIAL_SYNC_INTERVAL_MS = 15_000L
        private const val MOBILE_SYNC_INTERVAL_MS = 7_000L
        private const val HEALTH_ANOMALY_POLL_INTERVAL_MS = 10_000L
        private const val REMOTE_HEALTH_SAMPLE_MAX_AGE_MS = 90_000L
        private const val REMOTE_HEALTH_SAMPLE_FUTURE_TOLERANCE_MS = 30_000L
        private const val EMERGENCY_SUCCESS_DISPLAY_MS = 3_000L
        // 백엔드 /api/emergency는 location 필수. GPS 실패 시 폴백 좌표(모바일 앱과 동일)
        private const val DEFAULT_EMERGENCY_LAT = 37.557999
        private const val DEFAULT_EMERGENCY_LNG = 127.007993
        private const val SENSOR_LOG_TAG = "WearHealthSender"
    }
}

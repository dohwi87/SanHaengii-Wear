package com.sanhaengii.wearhealthsender.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Background = Color(0xFF050806)
private val Surface = Color(0xFF121713)
private val SurfaceElevated = Color(0xFF1A211C)
private val Outline = Color(0xFF2A332C)
private val Primary = Color(0xFF5EEA8A)
private val PrimaryMuted = Color(0xFF183824)
private val TextPrimary = Color(0xFFF4F7F4)
private val TextSecondary = Color(0xFFA8B2AA)
private val Warning = Color(0xFFFFC857)
private val Danger = Color(0xFFFF5A61)
private val DangerMuted = Color(0xFF3A171A)

@Composable
fun MainDashboard(bpm: Int, eta: String, distance: String) {
    val hasHeartRate = bpm > 0
    val heartColor = when {
        !hasHeartRate -> TextSecondary
        bpm >= 150 || bpm < 40 -> Danger
        bpm >= 125 -> Warning
        else -> Primary
    }
    val heartTransition = rememberInfiniteTransition(label = "heartPulse")
    val heartAlpha by heartTransition.animateFloat(
        initialValue = if (hasHeartRate) 0.65f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heartAlpha",
    )

    val etaDisplay = eta.withUnit("분")
    val distanceDisplay = distance.withUnit("km")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPill(
            label = if (hasHeartRate) "실시간 건강 데이터" else "센서 연결 대기",
            active = hasHeartRate,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(heartColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "심박수",
                    tint = heartColor,
                    modifier = Modifier
                        .size(25.dp)
                        .alpha(heartAlpha),
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (hasHeartRate) bpm.toString() else "--",
                        color = TextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "BPM",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                Text(
                    text = heartRateLabel(bpm),
                    color = heartColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MetricCard(
                label = "남은 시간",
                value = etaDisplay,
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "잔여 거리",
                value = distanceDisplay,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) Primary else TextSecondary),
        )
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, color = TextSecondary, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun BackendTestEntryScreen(onOpenBackendTest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(PrimaryMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "백엔드 전송",
                tint = Primary,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "전송 도구",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "센서 payload와 서버 응답을 확인합니다",
            color = TextSecondary,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onOpenBackendTest,
            colors = ButtonDefaults.buttonColors(backgroundColor = Primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
        ) {
            Text(
                text = "테스트 열기",
                color = Color(0xFF061008),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun AlertScreen(
    message: String,
    isWarning: Boolean,
    countdown: Int? = null,
    cancelLabel: String = "취소",
    emergencySendState: String = "idle",
    onConfirm: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val sendingTransition = rememberInfiniteTransition(label = "sendingPulse")
    val sendingAlpha by sendingTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "sendingAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isWarning) Color(0xFF0D0607) else Background)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (emergencySendState) {
            "sending" -> AlertStatusPanel(
                icon = Icons.AutoMirrored.Filled.Send,
                iconColor = Warning.copy(alpha = sendingAlpha),
                title = "신고 전송 중",
                detail = "위치와 건강 정보를 전달하고 있어요",
            )

            "success" -> AlertStatusPanel(
                icon = Icons.Default.CheckCircle,
                iconColor = Primary,
                title = "신고 접수 완료",
                detail = "안전한 곳에서 구조 안내를 기다려주세요",
            )

            "failed" -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StatusIcon(Icons.Default.ErrorOutline, Danger, DangerMuted)
                Spacer(modifier = Modifier.height(7.dp))
                Text("전송하지 못했어요", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("119에 직접 전화하거나 다시 시도하세요", color = Warning, fontSize = 9.sp)
                if (onConfirm != null || onCancel != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (onCancel != null) {
                            Button(
                                onClick = onCancel,
                                colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceElevated),
                                modifier = Modifier.weight(1f).height(40.dp),
                            ) {
                                Text("닫기", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onConfirm != null) {
                            Button(
                                onClick = onConfirm,
                                colors = ButtonDefaults.buttonColors(backgroundColor = Danger),
                                modifier = Modifier.weight(1f).height(40.dp),
                            ) {
                                Text("다시 시도", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            else -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StatusIcon(Icons.Default.Warning, Danger, DangerMuted, size = 38)
                    if (countdown != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("자동 신고까지", color = TextSecondary, fontSize = 8.sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = countdown.toString(),
                                    color = Warning,
                                    fontSize = 25.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "초",
                                    color = Warning,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "건강 이상 감지",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = message,
                    color = TextSecondary,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                if (countdown != null) {
                    Spacer(modifier = Modifier.height(9.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (onCancel != null) {
                            Button(
                                onClick = onCancel,
                                colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceElevated),
                                modifier = Modifier.weight(1f).height(40.dp),
                            ) {
                                Text(cancelLabel, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onConfirm != null) {
                            Button(
                                onClick = onConfirm,
                                colors = ButtonDefaults.buttonColors(backgroundColor = Danger),
                                modifier = Modifier.weight(1f).height(40.dp),
                            ) {
                                Text("지금 신고", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertStatusPanel(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    detail: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusIcon(icon, iconColor, iconColor.copy(alpha = 0.14f), size = 52)
        Spacer(modifier = Modifier.height(10.dp))
        Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(3.dp))
        Text(detail, color = TextSecondary, fontSize = 9.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    tint: Color,
    background: Color,
    size: Int = 46,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size * 0.52f).dp),
        )
    }
}

@Composable
fun SosScreen(
    isHikingActive: Boolean,
    isPaused: Boolean,
    onHikingToggle: () -> Unit,
    onAbort: () -> Unit,
    onSosClick: () -> Unit,
) {
    val isRunning = isHikingActive && !isPaused

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPill(
            label = when {
                isRunning -> "산행 기록 중"
                isPaused -> "산행 일시정지"
                else -> "산행 시작 전"
            },
            active = isRunning,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ControlButton(
                label = "종료",
                icon = Icons.Default.Stop,
                enabled = isHikingActive,
                background = SurfaceElevated,
                onClick = onAbort,
                modifier = Modifier.weight(1f),
            )
            ControlButton(
                label = when {
                    isRunning -> "일시정지"
                    isPaused -> "재개"
                    else -> "시작"
                },
                icon = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                enabled = true,
                background = if (isRunning) SurfaceElevated else Primary,
                contentColor = if (isRunning) TextPrimary else Color(0xFF061008),
                onClick = onHikingToggle,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        HoldForSosButton(onSosClick = onSosClick)
    }
}

@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = TextPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = background,
            disabledBackgroundColor = Surface,
        ),
        modifier = modifier.fillMaxHeight(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) contentColor else TextSecondary.copy(alpha = 0.45f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                color = if (enabled) contentColor else TextSecondary.copy(alpha = 0.45f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HoldForSosButton(onSosClick: () -> Unit) {
    var isPressing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(59.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(DangerMuted)
            .border(1.dp, Danger.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .pointerInput(onSosClick) {
                detectTapGestures(
                    onPress = {
                        isPressing = true
                        progress.snapTo(0f)
                        coroutineScope {
                            val animation = launch {
                                progress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(5_000, easing = LinearEasing),
                                )
                            }
                            val released = withTimeoutOrNull(5_000L) { tryAwaitRelease() }
                            if (released == null) {
                                onSosClick()
                            }
                            animation.cancel()
                        }
                        progress.snapTo(0f)
                        isPressing = false
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(Danger.copy(alpha = 0.38f)),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(31.dp)
                    .clip(CircleShape)
                    .background(Danger),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "긴급 구조 신고",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = if (isPressing) "계속 누르세요" else "긴급 구조 요청",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (isPressing) {
                        "신고까지 ${(5 - progress.value * 5).toInt().coerceAtLeast(1)}초"
                    } else {
                        "5초간 길게 누르기"
                    },
                    color = if (isPressing) Warning else TextSecondary,
                    fontSize = 8.sp,
                )
            }
        }
    }
}

@Composable
fun PageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { page ->
            val selected = currentPage == page
            Box(
                modifier = Modifier
                    .width(if (selected) 12.dp else 4.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (selected) Primary else TextSecondary.copy(alpha = 0.35f)),
            )
        }
    }
}

private fun String.withUnit(unit: String): String {
    val clean = trim()
    return when {
        clean.isBlank() || clean == "-" -> "--"
        clean.contains(unit, ignoreCase = true) -> clean
        else -> "$clean$unit"
    }
}

private fun heartRateLabel(bpm: Int): String {
    return when {
        bpm <= 0 -> "측정값 없음"
        bpm >= 150 -> "높은 심박수"
        bpm < 40 -> "낮은 심박수"
        bpm >= 125 -> "활동 심박수"
        else -> "정상 범위"
    }
}

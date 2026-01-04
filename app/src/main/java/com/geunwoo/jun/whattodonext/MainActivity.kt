package com.geunwoo.jun.whattodonext

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geunwoo.jun.whattodonext.ui.theme.WhatToDoNextTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// 앱 상태 관리
object AppState {
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Input)
    val currentScreen = _currentScreen.asStateFlow()

    private val _currentTask = MutableStateFlow<TaskInfo?>(null)
    val currentTask = _currentTask.asStateFlow()

    private val _remainingTimeMillis = MutableStateFlow(0L)
    val remainingTimeMillis = _remainingTimeMillis.asStateFlow()

    private val _isOvertime = MutableStateFlow(false)
    val isOvertime = _isOvertime.asStateFlow()

    private val _overtimeMillis = MutableStateFlow(0L)
    val overtimeMillis = _overtimeMillis.asStateFlow()

    fun startTask(taskName: String, taskType: TaskType, durationMillis: Long) {
        _currentTask.value = TaskInfo(taskName, taskType, durationMillis)
        _remainingTimeMillis.value = durationMillis
        _isOvertime.value = false
        _overtimeMillis.value = 0
        _currentScreen.value = Screen.Timer
    }

    fun updateTimerState(remainingMillis: Long, isOvertime: Boolean, overtimeMillis: Long) {
        _remainingTimeMillis.value = remainingMillis
        _isOvertime.value = isOvertime
        _overtimeMillis.value = overtimeMillis
    }

    fun completeTask() {
        _currentTask.value = null
        _remainingTimeMillis.value = 0
        _isOvertime.value = false
        _overtimeMillis.value = 0
        _currentScreen.value = Screen.Input
    }

    fun goToTimer() {
        if (_currentTask.value != null) {
            _currentScreen.value = Screen.Timer
        }
    }
}

data class TaskInfo(
    val name: String,
    val type: TaskType,
    val durationMillis: Long
)

sealed class Screen {
    object Input : Screen()
    object Timer : Screen()
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> }

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TaskTimerService.ACTION_TICK -> {
                    val remaining = intent.getLongExtra(TaskTimerService.EXTRA_REMAINING_TIME, 0)
                    val isOvertime = intent.getBooleanExtra(TaskTimerService.EXTRA_IS_OVERTIME, false)
                    val overtimeMillis = intent.getLongExtra(TaskTimerService.EXTRA_OVERTIME_MILLIS, 0)
                    AppState.updateTimerState(remaining, isOvertime, overtimeMillis)
                }
                TaskTimerService.ACTION_COMPLETE -> {
                    AppState.completeTask()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleIntent(intent)

        // 진행 중인 태스크가 없으면 idle 모드 시작
        if (AppState.currentTask.value == null) {
            TaskTimerService.startIdleMode(this)
        }

        setContent {
            WhatToDoNextTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            TaskTimerService.ACTION_SHOW_TIMER -> {
                AppState.goToTimer()
            }
            TaskTimerService.ACTION_COMPLETE -> {
                AppState.completeTask()
                // 태스크 완료 후 idle 모드로 전환
                TaskTimerService.startIdleMode(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(TaskTimerService.ACTION_TICK)
            addAction(TaskTimerService.ACTION_COMPLETE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(timerReceiver)
    }
}

@Composable
fun MainScreen() {
    val currentScreen by AppState.currentScreen.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentScreen) {
            is Screen.Input -> TaskInputScreen(modifier = Modifier.padding(innerPadding))
            is Screen.Timer -> TimerScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}

enum class TaskType(
    val label: String,
    val title: String,
    val inputLabel: String,
    val inputPlaceholder: String,
    val timeLabel: String,
    val buttonText: String,
    val primaryColor: Color,
    val containerColor: Color
) {
    CHALLENGE(
        label = "도전",
        title = "무엇을 해낼까요?",
        inputLabel = "완료할 일",
        inputPlaceholder = "예: 보고서 1페이지 작성",
        timeLabel = "완료 목표",
        buttonText = "시작하기",
        primaryColor = Color(0xFFFF6B35),
        containerColor = Color(0xFF2D1810)
    ),
    RECHARGE(
        label = "충전",
        title = "어떻게 쉴까요?",
        inputLabel = "충전 방법",
        inputPlaceholder = "예: 산책, 낮잠, 명상",
        timeLabel = "충전 시간",
        buttonText = "충전 시작",
        primaryColor = Color(0xFF4ECDC4),
        containerColor = Color(0xFF102D2A)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskInputScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var taskName by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("0") }
    var minutes by remember { mutableStateOf("30") }
    var taskType by remember { mutableStateOf(TaskType.CHALLENGE) }

    val backgroundColor by animateColorAsState(
        targetValue = taskType.containerColor,
        label = "backgroundColor"
    )
    val primaryColor by animateColorAsState(
        targetValue = taskType.primaryColor,
        label = "primaryColor"
    )

    val totalMinutes = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
    val showSplitSuggestion = taskType == TaskType.CHALLENGE && totalMinutes > 60

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = taskType.title,
            style = MaterialTheme.typography.headlineMedium,
            color = primaryColor,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            TaskType.entries.forEach { type ->
                FilterChip(
                    selected = taskType == type,
                    onClick = { taskType = type },
                    label = { Text(type.label) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = type.primaryColor,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        OutlinedTextField(
            value = taskName,
            onValueChange = { taskName = it },
            label = { Text(taskType.inputLabel) },
            placeholder = { Text(taskType.inputPlaceholder) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primaryColor,
                focusedLabelColor = primaryColor,
                cursorColor = primaryColor
            )
        )

        Text(
            text = taskType.timeLabel,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = hours,
                onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) hours = it },
                label = { Text("시간") },
                modifier = Modifier.width(80.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryColor,
                    focusedLabelColor = primaryColor,
                    cursorColor = primaryColor
                )
            )

            Text(
                text = ":",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            OutlinedTextField(
                value = minutes,
                onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) minutes = it },
                label = { Text("분") },
                modifier = Modifier.width(80.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryColor,
                    focusedLabelColor = primaryColor,
                    cursorColor = primaryColor
                )
            )
        }

        if (showSplitSuggestion) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = primaryColor.copy(alpha = 0.2f)
                )
            ) {
                Text(
                    text = "💡 1시간 넘는 일은 더 작게 쪼개보는 건 어때요?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = primaryColor,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                val durationMillis = totalMinutes * 60 * 1000L
                AppState.startTask(taskName, taskType, durationMillis)
                TaskTimerService.startTimer(
                    context = context,
                    taskName = taskName,
                    taskType = taskType.name,
                    durationMillis = durationMillis
                )
                // 입력 필드 초기화
                taskName = ""
                hours = "0"
                minutes = "30"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = taskName.isNotBlank() && totalMinutes > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryColor,
                contentColor = Color.Black
            )
        ) {
            Text(
                text = taskType.buttonText,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun TimerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val currentTask by AppState.currentTask.collectAsStateWithLifecycle()
    val remainingTimeMillis by AppState.remainingTimeMillis.collectAsStateWithLifecycle()
    val isOvertime by AppState.isOvertime.collectAsStateWithLifecycle()
    val overtimeMillis by AppState.overtimeMillis.collectAsStateWithLifecycle()

    val task = currentTask ?: return

    val backgroundColor by animateColorAsState(
        targetValue = task.type.containerColor,
        label = "backgroundColor"
    )
    val primaryColor by animateColorAsState(
        targetValue = task.type.primaryColor,
        label = "primaryColor"
    )

    val typeEmoji = if (task.type == TaskType.CHALLENGE) "🔥" else "🌿"

    val timeText = if (isOvertime) {
        "+${formatTime(overtimeMillis)}"
    } else {
        formatTime(remainingTimeMillis)
    }

    val timeColor = if (isOvertime) {
        Color(0xFFFF4444)
    } else {
        primaryColor
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = typeEmoji,
            fontSize = 64.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = task.name,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        if (isOvertime) {
            Text(
                text = "목표 시간 초과!",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF4444),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.displayLarge,
            color = timeColor,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        Button(
            onClick = {
                AppState.completeTask()
                // 태스크 완료 후 idle 모드로 전환
                TaskTimerService.startIdleMode(context)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryColor,
                contentColor = Color.Black
            )
        ) {
            Text(
                text = "완료",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

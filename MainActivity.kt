package jp.focusroom.nativeapp

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import kotlin.math.max

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("focus_room", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val noise = NoisePlayer()

    private var mode = "focus"
    private var running = false
    private var deadline = 0L
    private var remainingSeconds = 25L * 60L
    private var pausedWithRemaining = false
    private var focusMinutes = 25
    private var breakMinutes = 5
    private var focusGoal = ""
    private var distractions = 0
    private var todayMinutes = 0
    private var todaySessions = 0
    private var pinningFallback = false
    private var isResumedNow = false
    private var taskItems = mutableListOf<Pair<String, Boolean>>()
    private var soundKind = "OFF"
    private var soundVolume = 0.16f

    private lateinit var statusText: TextView
    private lateinit var timerModeText: TextView
    private lateinit var timerText: TextView
    private lateinit var timerCaption: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var focusGoalInput: EditText
    private lateinit var focusMinutesInput: EditText
    private lateinit var breakMinutesInput: EditText
    private lateinit var taskInput: EditText
    private lateinit var taskListBox: LinearLayout
    private lateinit var statsText: TextView
    private lateinit var distractionText: TextView
    private lateinit var soundSpinner: Spinner
    private lateinit var volumeBar: SeekBar

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val remaining = max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L)
            remainingSeconds = remaining
            updateTimerDisplay(remaining)
            if (remaining <= 0L) {
                if (mode == "focus") completeFocus() else completeBreak()
            } else handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(8, 13, 26)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadState()
        buildUi()
        createNotificationChannel()
        requestRuntimePermissions()
        checkTimerOnResume()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        loadState()
        checkTimerOnResume()
        updateUi()
        if (mode == "focus" && running && DevicePolicyHelper.isOwner(this)) {
            enterStrongKiosk()
        }
    }

    override fun onPause() {
        isResumedNow = false
        saveState()
        noise.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        noise.stop()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isStrongLocked()) {
            Toast.makeText(this, "集中モード中です。ロゴを長押しして緊急解除できます。", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 16, 32))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        val brand = text("FOCUS ROOM", 22f, CYAN, true).apply {
            letterSpacing = .18f
            setOnLongClickListener { showEmergencyUnlock(); true }
        }
        root.addView(brand)
        root.addView(text("NATIVE DEEP WORK SYSTEM", 10f, MUTED, true).apply {
            letterSpacing = .18f
            setPadding(0, dp(4), 0, dp(18))
        })

        val lockCard = card()
        lockCard.addView(text("ANDROID DEVICE LOCK", 11f, CYAN, true))
        statusText = text("管理状態を確認中…", 13f, WHITE, true).apply { setPadding(0, dp(9), 0, dp(4)) }
        lockCard.addView(statusText)
        lockCard.addView(text("本格ロックには、このアプリをDevice Ownerとして登録する必要があります。", 11f, MUTED, false))
        val lockInfo = button("ロック機能・設定条件を確認", false) { showLockHelp() }
        lockCard.addView(lockInfo, matchWrap())
        root.addView(lockCard, matchWrap(dp(12)))

        val goalCard = card()
        goalCard.addView(text("今回の集中目標", 14f, WHITE, true))
        focusGoalInput = edit("例：数学の問題を5問解く", false).apply {
            setText(focusGoal)
            addTextChangedListener(simpleWatcher { focusGoal = it; saveState() })
        }
        goalCard.addView(focusGoalInput, matchWrap(dp(10)))
        goalCard.addView(text("目標を小さく具体的にすると、開始時に迷いにくくなります。", 10f, MUTED, false))
        root.addView(goalCard, matchWrap(dp(12)))

        val timerCard = card()
        timerModeText = text("FOCUS SESSION", 12f, CYAN, true).apply { gravity = Gravity.CENTER; letterSpacing = .2f }
        timerCard.addView(timerModeText)
        timerText = text("25:00", 62f, WHITE, false).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, dp(7), 0, dp(4))
        }
        timerCard.addView(timerText)
        timerCaption = text("一度に一つ。急がず進めよう。", 12f, MUTED, false).apply { gravity = Gravity.CENTER }
        timerCard.addView(timerCaption)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(38, 50, 74))
        }
        timerCard.addView(progress, matchWrap(dp(16)))
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        focusMinutesInput = numericInput(focusMinutes.toString(), 3)
        breakMinutesInput = numericInput(breakMinutes.toString(), 3)
        timeRow.addView(text("集中", 11f, MUTED, true))
        timeRow.addView(focusMinutesInput, wrapWidth(dp(58), dp(44)).apply { setMargins(dp(7), 0, dp(17), 0) })
        timeRow.addView(text("分   休憩", 11f, MUTED, true))
        timeRow.addView(breakMinutesInput, wrapWidth(dp(58), dp(44)).apply { setMargins(dp(7), 0, dp(7), 0) })
        timeRow.addView(text("分", 11f, MUTED, true))
        timerCard.addView(timeRow, matchWrap(dp(14)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        startButton = button("集中を開始", true) { onStartPressed() }
        controls.addView(startButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(7) })
        controls.addView(button("リセット", false) { resetTimer() }, LinearLayout.LayoutParams(0, dp(52), .75f).apply { marginStart = dp(7) })
        timerCard.addView(controls)
        timerCard.addView(text("集中終了 → 自動休憩 → 休憩終了 → 次の集中", 10f, MUTED, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        root.addView(timerCard, matchWrap(dp(12)))

        val taskCard = card()
        taskCard.addView(text("今日のタスク", 14f, WHITE, true))
        val taskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        taskInput = edit("やることを追加", false)
        taskRow.addView(taskInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        taskRow.addView(button("追加", false) { addTask() }, wrapWidth(dp(74), dp(48)))
        taskCard.addView(taskRow, matchWrap(dp(10)))
        taskListBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        taskCard.addView(taskListBox, matchWrap(dp(4)))
        root.addView(taskCard, matchWrap(dp(12)))

        val returnCard = card()
        returnCard.addView(text("気が散ったとき", 14f, WHITE, true))
        distractionText = text("集中に戻った回数：${distractions}", 11f, MUTED, false).apply { setPadding(0, dp(5), 0, dp(8)) }
        returnCard.addView(distractionText)
        returnCard.addView(button("気が散った — ひと呼吸して戻る", false) {
            distractions++
            saveState()
            distractionText.text = "集中に戻った回数：$distractions"
            Toast.makeText(this, "気が散るのは自然なこと。次の小さな一歩へ。", Toast.LENGTH_LONG).show()
        }, matchWrap())
        root.addView(returnCard, matchWrap(dp(12)))

        val soundCard = card()
        soundCard.addView(text("環境音", 14f, WHITE, true))
        soundSpinner = Spinner(this)
        val sounds = listOf("OFF" to "無音", "RAIN" to "雨音ノイズ", "OCEAN" to "波のような低音", "WHITE" to "ホワイトノイズ", "PINK" to "ピンクノイズ", "BROWN" to "ブラウンノイズ")
        val soundAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, sounds.map { it.second })
        soundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        soundSpinner.adapter = soundAdapter
        soundSpinner.setSelection(sounds.indexOfFirst { it.first == soundKind }.coerceAtLeast(0))
        soundSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                soundKind = sounds[position].first
                noise.volume = soundVolume
                if (soundKind == "OFF") noise.stop() else noise.start(soundKind)
                saveState()
            }
        }
        soundCard.addView(soundSpinner, matchWrap(dp(8)))
        soundCard.addView(text("音量（小さめから試す）", 10f, MUTED, false).apply { setPadding(0, dp(10), 0, 0) })
        volumeBar = SeekBar(this).apply {
            max = 60
            progress = (soundVolume * 100).toInt().coerceIn(0, 60)
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            thumbTintList = android.content.res.ColorStateList.valueOf(CYAN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    soundVolume = value / 100f
                    noise.volume = soundVolume
                    saveState()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        soundCard.addView(volumeBar, matchWrap(dp(5)))
        soundCard.addView(text("読解・暗記・難しい計算は、まず無音で試すのがおすすめ。音の効果には個人差があります。", 10f, MUTED, false).apply { setPadding(0, dp(5), 0, 0) })
        root.addView(soundCard, matchWrap(dp(12)))

        val statsCard = card()
        statsCard.addView(text("今日の記録", 14f, WHITE, true))
        statsText = text("集中 0分  ·  完了 0回", 20f, CYAN, true).apply { setPadding(0, dp(10), 0, dp(4)) }
        statsCard.addView(statsText)
        statsCard.addView(text("端末内に保存。ネット接続は不要です。", 10f, MUTED, false))
        root.addView(statsCard, matchWrap(dp(12)))

        val footer = text("DEEP WORK · REST · REPEAT", 10f, MUTED, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .18f
            setPadding(0, dp(16), 0, dp(6))
        }
        root.addView(footer, matchWrap())

        renderTasks()
        updateUi()
    }

    private fun onStartPressed() {
        if (running) {
            remainingSeconds = max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L)
            pausedWithRemaining = true
            running = false
            deadline = 0L
            handler.removeCallbacks(tick)
            TimerAlarmReceiver.cancel(this)
            saveState(); updateUi()
            return
        }
        if (mode == "focus" && DevicePolicyHelper.isOwner(this)) {
            ensureExitPinThen { if (it) startPhaseTimer(true) }
            return
        }
        if (mode == "focus") {
            AlertDialog.Builder(this)
                .setTitle("集中ロックが未登録です")
                .setMessage("このGalaxyはまだDevice Ownerではありません。強いロックは有効になりません。画面固定（自分で解除可能）で試すか、ロックなしでタイマーを開始できます。")
                .setPositiveButton("画面固定で試す") { _, _ ->
                    pinningFallback = true
                    try { startLockTask() } catch (_: Exception) { }
                    startPhaseTimer(false)
                }
                .setNeutralButton("タイマーのみ") { _, _ -> startPhaseTimer(false) }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            startPhaseTimer(false)
        }
    }

    private fun startPhaseTimer(strong: Boolean) {
        readDurationInputs()
        if (mode == "focus") {
            focusGoal = focusGoalInput.text?.toString()?.trim().orEmpty()
            if (focusGoal.isBlank()) focusGoal = "今取り組むことに集中"
            distractions = 0
            if (strong && !enterStrongKiosk()) {
                Toast.makeText(this, "強ロックを開始できませんでした。Device Owner状態を確認してください。", Toast.LENGTH_LONG).show()
                return
            }
            playStartFeedback()
        }
        if (!pausedWithRemaining || remainingSeconds <= 0L) remainingSeconds = durationSeconds()
        pausedWithRemaining = false
        running = true
        deadline = System.currentTimeMillis() + remainingSeconds * 1000L
        saveState()
        scheduleTimerAlarm()
        handler.removeCallbacks(tick)
        handler.post(tick)
        updateUi()
    }

    private fun completeFocus() {
        if (mode != "focus" || !running) return
        running = false
        handler.removeCallbacks(tick)
        TimerAlarmReceiver.cancel(this)
        stopKiosk()
        updateTodayStats()
        mode = "break"
        pausedWithRemaining = false
        remainingSeconds = breakMinutes * 60L
        running = true
        deadline = System.currentTimeMillis() + remainingSeconds * 1000L
        saveState()
        scheduleTimerAlarm()
        alertFinished()
        Toast.makeText(this, "集中完了！休憩モードを自動開始しました。", Toast.LENGTH_LONG).show()
        updateUi()
        handler.post(tick)
    }

    private fun completeBreak() {
        if (mode != "break" || !running) return
        running = false
        handler.removeCallbacks(tick)
        TimerAlarmReceiver.cancel(this)
        mode = "focus"
        pausedWithRemaining = false
        remainingSeconds = focusMinutes * 60L
        distractions = 0
        if (DevicePolicyHelper.isOwner(this)) {
            if (!ensureStoredPin()) {
                running = false
                deadline = 0L
                pausedWithRemaining = false
                saveState(); updateUi()
                Toast.makeText(this, "集中ロック用PINを設定してから再開してください。", Toast.LENGTH_LONG).show()
                return
            }
            if (isResumedNow && !enterStrongKiosk()) {
                running = false
                deadline = 0L
                pausedWithRemaining = false
                saveState(); updateUi()
                return
            }
        }
        running = true
        deadline = System.currentTimeMillis() + remainingSeconds * 1000L
        saveState()
        scheduleTimerAlarm()
        alertFinished()
        Toast.makeText(this, "休憩終了！集中モードへ戻りました。", Toast.LENGTH_LONG).show()
        updateUi()
        handler.post(tick)
    }

    private fun checkTimerOnResume() {
        if (!running || deadline <= 0L) {
            updateUi()
            return
        }
        if (System.currentTimeMillis() >= deadline) {
            if (mode == "focus") completeFocus() else completeBreak()
        } else {
            scheduleTimerAlarm()
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun enterStrongKiosk(): Boolean {
        return try {
            DevicePolicyHelper.configure(this)
            if (Build.VERSION.SDK_INT >= 23) {
                val am = getSystemService(android.app.ActivityManager::class.java)
                if (am.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
            } else startLockTask()
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            statusText.text = "● DEVICE OWNER · 強ロック有効"
            true
        } catch (e: Exception) {
            statusText.text = "強ロックを開始できません：${e.javaClass.simpleName}"
            false
        }
    }

    private fun stopKiosk() {
        try { stopLockTask() } catch (_: Exception) { }
        pinningFallback = false
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun isStrongLocked(): Boolean {
        if (!DevicePolicyHelper.isOwner(this)) return false
        return try {
            getSystemService(android.app.ActivityManager::class.java).lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        } catch (_: Exception) { false }
    }

    private fun showEmergencyUnlock() {
        if (!isStrongLocked()) {
            showLockHelp()
            return
        }
        val input = EditText(this).apply {
            hint = "解除PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("緊急解除")
            .setMessage("設定した解除PINを入力してください。通常の集中終了はタイマーで自動解除されます。")
            .setView(input)
            .setPositiveButton("ロック解除") { _, _ ->
                if (pinDigest(input.text.toString()) == prefs.getString("exit_pin_hash", "")) {
                    running = false
                    deadline = 0L
                    pausedWithRemaining = false
                    remainingSeconds = focusMinutes * 60L
                    mode = "focus"
                    handler.removeCallbacks(tick)
                    TimerAlarmReceiver.cancel(this)
                    stopKiosk()
                    saveState(); updateUi()
                    Toast.makeText(this, "緊急解除しました。", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "PINが違います。", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("戻る", null)
            .show()
    }

    private fun ensureExitPinThen(done: (Boolean) -> Unit) {
        val existing = prefs.getString("exit_pin", null)
        if (!existing.isNullOrBlank()) { done(true); return }
        val input = EditText(this).apply {
            hint = "4～8桁の数字"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("緊急解除PINを設定")
            .setMessage("集中ロック中に中断するためのPINです。忘れると端末の初期化が必要になる場合があります。必ず控えてください。")
            .setView(input)
            .setPositiveButton("保存して開始") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8 && pin.all { it.isDigit() }) {
                    prefs.edit().putString("exit_pin_hash", pinDigest(pin)).apply()
                    done(true)
                } else {
                    Toast.makeText(this, "4～8桁の数字を設定してください。", Toast.LENGTH_LONG).show()
                    done(false)
                }
            }
            .setNegativeButton("キャンセル") { _, _ -> done(false) }
            .show()
    }

    private fun ensureStoredPin(): Boolean = !prefs.getString("exit_pin_hash", null).isNullOrBlank()

    private fun pinDigest(pin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun showLockHelp() {
        val owner = DevicePolicyHelper.isOwner(this)
        val message = if (owner) {
            "このアプリはDevice Ownerとして登録されています。集中タイマー中はAndroid Lock Task Modeを使用し、ホーム・履歴・通知パネル等を制限します。\n\n解除は集中終了時の自動切替、またはFOCUS ROOMロゴ長押し＋解除PINです。"
        } else {
            "現在：通常インストール状態（Device Owner未登録）\n\n画面固定はAndroid標準の画面固定に留まり、ユーザーが解除できます。本格的なLock Task ModeにはDevice Owner登録が必要です。既に設定済みのGalaxyに後からDevice Ownerを追加するには、通常は端末初期化後の初期設定中に登録します。初期化すると端末内データが消えるため、バックアップ前に進めないでください。\n\nAndroid 7以降は初期設定画面で同じ場所を6回タップしてQR登録へ進める方式がありますが、登録用に署名済みAPKと適切なプロビジョニング設定が必要です。"
        }
        AlertDialog.Builder(this)
            .setTitle(if (owner) "強ロック準備完了" else "強ロックの設定条件")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("正確なアラーム設定") { _, _ -> openExactAlarmSettings() }
            .show()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { Toast.makeText(this, "この端末では設定画面を開けません。", Toast.LENGTH_SHORT).show() }
        } else Toast.makeText(this, "このAndroidバージョンでは追加設定は不要です。", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleTimerAlarm() {
        if (running && deadline > System.currentTimeMillis()) TimerAlarmReceiver.schedule(this, deadline)
    }

    private fun durationSeconds(): Long = (if (mode == "focus") focusMinutes else breakMinutes).coerceIn(1, 240) * 60L

    private fun updateTimerDisplay(remainingSeconds: Long) {
        val m = remainingSeconds / 60L
        val s = remainingSeconds % 60L
        timerText.text = String.format(Locale.JAPAN, "%02d:%02d", m, s)
        val total = (if (mode == "focus") focusMinutes else breakMinutes).coerceAtLeast(1) * 60L
        progress.progress = (((total - remainingSeconds).coerceAtLeast(0) * 1000L) / total).toInt()
    }

    private fun updateUi() {
        val owner = DevicePolicyHelper.isOwner(this)
        statusText.text = if (owner) "● DEVICE OWNER · 強ロック利用可能" else "○ 通常モード · 強ロック未登録"
        statusText.setTextColor(if (owner) GREEN else ORANGE)
        timerModeText.text = if (mode == "focus") "DEEP FOCUS" else "REST & RESET"
        timerModeText.setTextColor(if (mode == "focus") CYAN else PURPLE)
        timerCaption.text = when {
            mode == "focus" && isStrongLocked() -> "集中画面を固定中。ロゴ長押しで緊急解除。"
            mode == "focus" -> "一度に一つ。急がず進めよう。"
            else -> "画面から目を離して、目と体を休ませよう。"
        }
        startButton.text = if (running) "一時停止" else if (mode == "focus") "集中を開始" else "休憩を開始"
        if (running) updateTimerDisplay(max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L))
        else updateTimerDisplay(remainingSeconds.coerceAtLeast(0L))
        statsText.text = "集中 ${todayMinutes}分  ·  完了 ${todaySessions}回"
        distractionText.text = "集中に戻った回数：$distractions"
        if (::taskListBox.isInitialized) renderTasks()
    }

    private fun resetTimer() {
        if (mode == "focus" && isStrongLocked()) {
            Toast.makeText(this, "集中ロック中はリセットできません。ロゴ長押しで緊急解除してください。", Toast.LENGTH_LONG).show()
            return
        }
        running = false
        deadline = 0L
        remainingSeconds = durationSeconds()
        pausedWithRemaining = false
        handler.removeCallbacks(tick)
        TimerAlarmReceiver.cancel(this)
        saveState(); updateUi()
    }

    private fun readDurationInputs() {
        focusMinutes = (focusMinutesInput.text?.toString()?.toIntOrNull() ?: focusMinutes).coerceIn(1, 180)
        breakMinutes = (breakMinutesInput.text?.toString()?.toIntOrNull() ?: breakMinutes).coerceIn(1, 60)
        focusMinutesInput.setText(focusMinutes.toString())
        breakMinutesInput.setText(breakMinutes.toString())
        if (!running && !pausedWithRemaining) remainingSeconds = durationSeconds()
        saveState()
    }

    private fun updateTodayStats() {
        val day = dayKey()
        if (prefs.getString("stats_day", "") != day) {
            todayMinutes = 0; todaySessions = 0
        }
        todayMinutes += focusMinutes
        todaySessions += 1
        prefs.edit().putString("stats_day", day).putInt("today_minutes", todayMinutes).putInt("today_sessions", todaySessions).apply()
    }

    private fun loadState() {
        mode = prefs.getString("mode", "focus") ?: "focus"
        running = prefs.getBoolean("running", false)
        deadline = prefs.getLong("deadline", 0L)
        remainingSeconds = prefs.getLong("remaining_seconds", (prefs.getInt("focus_minutes", 25) * 60).toLong())
        pausedWithRemaining = prefs.getBoolean("paused_with_remaining", false)
        focusMinutes = prefs.getInt("focus_minutes", 25).coerceIn(1, 180)
        breakMinutes = prefs.getInt("break_minutes", 5).coerceIn(1, 60)
        focusGoal = prefs.getString("focus_goal", "") ?: ""
        distractions = prefs.getInt("distractions", 0)
        soundKind = prefs.getString("sound_kind", "OFF") ?: "OFF"
        soundVolume = prefs.getFloat("sound_volume", .16f).coerceIn(0f, .6f)
        val day = dayKey()
        if (prefs.getString("stats_day", "") != day) {
            todayMinutes = 0; todaySessions = 0
            prefs.edit().putString("stats_day", day).putInt("today_minutes", 0).putInt("today_sessions", 0).apply()
        } else {
            todayMinutes = prefs.getInt("today_minutes", 0)
            todaySessions = prefs.getInt("today_sessions", 0)
        }
        taskItems = try {
            val array = JSONArray(prefs.getString("tasks", "[]"))
            MutableList(array.length()) { i ->
                val obj = array.getJSONObject(i)
                obj.optString("text") to obj.optBoolean("done", false)
            }
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveState() {
        val arr = JSONArray()
        taskItems.forEach { (name, done) -> arr.put(JSONObject().put("text", name).put("done", done)) }
        prefs.edit()
            .putString("mode", mode)
            .putBoolean("running", running)
            .putLong("deadline", deadline)
            .putLong("remaining_seconds", remainingSeconds)
            .putBoolean("paused_with_remaining", pausedWithRemaining)
            .putInt("focus_minutes", focusMinutes)
            .putInt("break_minutes", breakMinutes)
            .putString("focus_goal", focusGoal)
            .putInt("distractions", distractions)
            .putString("tasks", arr.toString())
            .putString("sound_kind", soundKind)
            .putFloat("sound_volume", soundVolume)
            .apply()
    }

    private fun addTask() {
        val value = taskInput.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) return
        taskItems.add(value to false)
        taskInput.setText("")
        saveState(); renderTasks()
    }

    private fun renderTasks() {
        if (!::taskListBox.isInitialized) return
        taskListBox.removeAllViews()
        if (taskItems.isEmpty()) {
            taskListBox.addView(text("タスクはまだありません。小さな目標を追加しよう。", 11f, MUTED, false).apply { setPadding(0, dp(12), 0, dp(5)) })
            return
        }
        taskItems.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val check = CheckBox(this).apply {
                isChecked = item.second
                buttonTintList = android.content.res.ColorStateList.valueOf(CYAN)
                setOnCheckedChangeListener { _, checked ->
                    if (index < taskItems.size) {
                        taskItems[index] = taskItems[index].first to checked
                        saveState(); renderTasks()
                    }
                }
            }
            row.addView(check, wrapWidth(dp(42), dp(42)))
            val name = text(item.first, 12f, if (item.second) MUTED else WHITE, false).apply {
                if (item.second) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("×", false) {
                if (index < taskItems.size) taskItems.removeAt(index)
                saveState(); renderTasks()
            }, wrapWidth(dp(42), dp(40)))
            taskListBox.addView(row)
        }
    }

    @Suppress("DEPRECATION")
    private fun alertFinished() {
        try {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 300)
            handler.postDelayed({ try { tone.release() } catch (_: Exception) { } }, 450)
        } catch (_: Exception) { }
        if (prefs.getBoolean("vibrate", true)) {
            try {
                val vibrator = getSystemService(android.os.Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 70, 60, 100), -1))
                else vibrator.vibrate(160)
            } catch (_: Exception) { }
        }
    }

    private fun playStartFeedback() {
        // A quiet native acknowledgement marks the start without adding music.
        try {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 35)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 90)
            handler.postDelayed({ try { tone.release() } catch (_: Exception) { } }, 150)
        } catch (_: Exception) { }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 221)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(TimerAlarmReceiver.CHANNEL, "FOCUS ROOM タイマー", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun edit(hintText: String, numeric: Boolean): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(MUTED)
        setTextColor(WHITE)
        textSize = 13f
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        setPadding(dp(13), dp(5), dp(13), dp(5))
        background = rounded(Color.rgb(13, 23, 41), 13, Color.rgb(48, 64, 91))
    }

    private fun numericInput(value: String, widthDp: Int): EditText = edit("", true).apply {
        setText(value)
        gravity = Gravity.CENTER
        textSize = 14f
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(if (primary) Color.rgb(8, 18, 36) else WHITE)
        background = if (primary) roundedGradient(CYAN, BLUE, 14) else rounded(Color.rgb(31, 44, 69), 14, Color.rgb(52, 68, 95))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { action() }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = roundedGradient(Color.rgb(23, 34, 57), Color.rgb(15, 23, 41), 22)
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun roundedGradient(top: Int, bottom: Int, radiusDp: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
    ).apply { cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(1), Color.rgb(47, 62, 89)) }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        if (topMargin > 0) this.topMargin = this@MainActivity.dp(topMargin)
    }

    private fun wrapWidth(width: Int, height: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(Date())

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    companion object {
        private const val WHITE = Color.WHITE
        private const val MUTED = 0xFF91A0BD.toInt()
        private const val CYAN = 0xFF67E8F9.toInt()
        private const val BLUE = 0xFF7197FF.toInt()
        private const val PURPLE = 0xFFA78BFA.toInt()
        private const val GREEN = 0xFF63E6B0.toInt()
        private const val ORANGE = 0xFFFFBD6A.toInt()
    }
}

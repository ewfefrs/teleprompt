package com.example.teleprompter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Screen { MAIN, TEXTS, EDITOR, SETTINGS, FAQ }

class MainActivity : ComponentActivity() {

    private val viewModel: TeleprompterViewModel by viewModels()

    private val serviceState = mutableStateOf<BleService?>(null)
    private val connState = mutableStateOf(ConnState("", false))

    private var stateJob: Job? = null
    private var bindRequested = false
    private var pendingDisconnect = false   // отложенное «Отключить» из уведомления (ждём привязки сервиса)

    private lateinit var security: SecurityManager
    private lateinit var repository: TextRepository

    private var themeMode by mutableStateOf(ThemeManager.SYSTEM)
    private var loggedIn by mutableStateOf(false)   // есть действующая сессия (админ/гость)
    private var isAdmin by mutableStateOf(false)    // текущая сессия — админская
    private var appMode by mutableStateOf(AppModeManager.PUBLIC)  // публичный / аренда
    private var isPremium by mutableStateOf(false)  // куплена премиум-версия

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? BleService.LocalBinder)?.getService() ?: return
            serviceState.value = svc
            maybeHandlePendingDisconnect()   // отключение по кнопке уведомления при холодном старте
            stateJob?.cancel()
            stateJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { svc.connState.collect { connState.value = it } }
                    launch {
                        svc.glassesEvents.collect { event ->
                            when {
                                event == "started" || event == "resumed" -> {
                                    // Показ запущен с очков — синхронизируем текст на телефоне.
                                    svc.selectedScript?.let { viewModel.script = it }
                                    viewModel.start()
                                }
                                event == "paused" -> viewModel.pause()
                                event == "stopped" -> viewModel.stop()
                                // Одиночный тап по очкам переключил Широкий↔Узкий — синхронизируем
                                // кнопку и рамку превью на телефоне.
                                event == "narrow" -> viewModel.narrowScreen = true
                                event == "wide" -> viewModel.narrowScreen = false
                                // Свайп по тачбару выбрал другой скрипт — показываем его в превью.
                                event.startsWith("picked:") -> svc.selectedScript?.let { viewModel.script = it }
                            }
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        IntegrityGuard.check(this)   // анти-реверс: помечает подмену → отключает премиум
        if (intent?.action == BleService.ACTION_DISCONNECT) pendingDisconnect = true
        security = SecurityManager(this)
        repository = TextRepository(this)
        themeMode = ThemeManager.mode(this)
        loggedIn = security.hasSession()
        isAdmin = security.isAdminSession()
        appMode = AppModeManager.mode(this)
        isPremium = PremiumManager.isPremium(this)
        // Ширина колонки: как у официалки (44 = 43-символьные строки из ofc.log).
        // Устаревшие узкие значения (<40) из прошлых версий поднимаем до 44, чтобы
        // строки были такими же широкими, как в официальном приложении.
        viewModel.colWidth = Prefs.getInt(this, Prefs.KEY_COL_WIDTH, 44).let { if (it < 40) 44 else it }.toFloat()
        viewModel.wpm = Prefs.getInt(this, Prefs.KEY_SPEED, 130).let { if (it < 40) 130 else it }.toFloat()
        viewModel.brightness = Prefs.getInt(this, Prefs.KEY_BRIGHTNESS, 60).toFloat()

        setContent {
            AppTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val crash = remember { readAndConsumeCrash() }
                    if (crash != null) {
                        CrashScreen(crash) { recreate() }
                    } else if (appMode == AppModeManager.RENTAL && !loggedIn) {
                        // Гейт авторизации ТОЛЬКО в режиме аренды: без действующей сессии
                        // показываем экран входа (Admin Passcode / Guest Code). В публичном
                        // режиме входа нет — сразу основной интерфейс.
                        LoginScreen(security) {
                            loggedIn = security.hasSession()
                            isAdmin = security.isAdminSession()
                        }
                    } else {
                        PermissionGate { AppRoot() }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        var screen by remember { mutableStateOf(Screen.MAIN) }
        var editing by remember { mutableStateOf<SavedText?>(null) }
        var importDraft by remember { mutableStateOf<FileImport.Imported?>(null) }
        var texts by remember { mutableStateOf(repository.list()) }
        var showLimitDialog by remember { mutableStateOf(false) }
        val language = remember { LocaleManager.language(this) }

        // Создать НОВЫЙ текст: на бесплатном тарифе не больше [PremiumManager.MAX_TEXTS_FREE].
        val openNewEditor = {
            if (PremiumManager.canAddText(this@MainActivity, repository.list().size)) {
                editing = null; importDraft = null; screen = Screen.EDITOR
            } else showLimitDialog = true
        }

        // Импорт скрипта из файла (.txt / .md / .docx) — офлайн (FileImport). Лимит числа
        // текстов — как у «Нового текста»; при успехе открываем редактор с содержимым файла,
        // где действует счётчик/лимит слов бесплатного тарифа.
        val onImportFile: (android.net.Uri) -> Unit = { uri ->
            if (!PremiumManager.canAddText(this@MainActivity, repository.list().size)) {
                showLimitDialog = true
            } else when (val r = FileImport.read(this@MainActivity, uri)) {
                is FileImport.Result.Ok -> {
                    editing = null
                    importDraft = r.value
                    screen = Screen.EDITOR
                }
                is FileImport.Result.Error ->
                    android.widget.Toast.makeText(
                        this@MainActivity, getString(r.messageRes), android.widget.Toast.LENGTH_LONG
                    ).show()
            }
        }

        // Список сохранённых скриптов уходит в сервис для выбора С ОЧКОВ: свайп по
        // тачбару листает их, тап — запускает выбранный (без телефона).
        val svc = serviceState.value
        val ready = connState.value.isReady
        LaunchedEffect(texts, ready, svc) {
            if (ready) svc?.prepareScripts(
                texts.map { it.title to it.body },
                viewModel.scrollMode, viewModel.wpm, viewModel.bigText
            )
        }

        when (screen) {
            Screen.MAIN -> TeleprompterScreen(
                viewModel = viewModel,
                conn = connState.value,
                service = serviceState.value,
                onConnect = ::connectGlasses,
                onDisconnect = ::disconnectGlasses,
                onOpenTexts = { texts = repository.list(); screen = Screen.TEXTS },
                onNewText = openNewEditor,
                onOpenSettings = { screen = Screen.SETTINGS }
            )
            Screen.TEXTS -> TextsScreen(
                items = texts,
                onNew = openNewEditor,
                onImportFile = onImportFile,
                // ВЫБОР текста — только загружаем его в главный экран; запуск делает «Старт».
                onRun = { loadOnly(it.body, it.id, it.title); screen = Screen.MAIN },
                onEdit = { editing = it; importDraft = null; screen = Screen.EDITOR },
                onDelete = { repository.delete(it.id); texts = repository.list() },
                onBack = { screen = Screen.MAIN }
            )
            Screen.EDITOR -> TextEditorScreen(
                // Приоритет: правка существующего → черновик импорта → пустой (новый).
                initialTitle = editing?.title ?: importDraft?.title ?: "",
                initialBody = editing?.body ?: importDraft?.body ?: "",
                wordLimit = PremiumManager.wordLimit(this),
                onSave = { title, body ->
                    val saved = repository.save(editing?.id, title, body)
                    importDraft = null
                    loadOnly(saved.body, saved.id, saved.title)
                    texts = repository.list()
                    screen = Screen.MAIN
                },
                // «Выбрать» в редакторе — сохранить и загрузить (без авто-старта).
                onRun = { title, body ->
                    val saved = repository.save(editing?.id, title, body)
                    importDraft = null
                    loadOnly(saved.body, saved.id, saved.title)
                    texts = repository.list()
                    screen = Screen.MAIN
                },
                onBack = { importDraft = null; screen = Screen.MAIN }
            )
            Screen.SETTINGS -> SettingsScreen(
                themeMode = themeMode,
                language = language,
                appMode = appMode,
                isAdmin = isAdmin,
                isPremium = isPremium,
                sessionHoursLeft = security.sessionHoursLeft(),
                onThemeChange = { ThemeManager.setMode(this, it); themeMode = it },
                onLanguageChange = {
                    if (it != language) { LocaleManager.setLanguage(this, it); recreate() }
                },
                onEnableRental = {
                    // Включаем аренду: дальше на очках/в приложении работает гейт доступа.
                    AppModeManager.enableRental(this)
                    appMode = AppModeManager.RENTAL
                    loggedIn = security.hasSession()
                    isAdmin = security.isAdminSession()
                    screen = Screen.MAIN
                },
                onDisableRental = { code ->
                    val ok = AppModeManager.disableRental(this, security, code)
                    if (ok) { appMode = AppModeManager.PUBLIC; security.logout(); loggedIn = false; isAdmin = false }
                    ok
                },
                onActivateKey = { key ->
                    val ok = LicenseManager.activate(this, key)
                    if (ok) isPremium = true
                    ok
                },
                onOpenFaq = { screen = Screen.FAQ },
                onLogout = {
                    security.logout()
                    loggedIn = false
                    isAdmin = false
                    screen = Screen.MAIN
                },
                onBack = { screen = Screen.MAIN },
                security = security
            )
            Screen.FAQ -> FaqScreen(onBack = { screen = Screen.SETTINGS })
        }

        // Апселл: достигнут лимит бесплатного тарифа на число текстов.
        if (showLimitDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showLimitDialog = false },
                title = { Text(stringResource(R.string.premium_title)) },
                text = { Text(stringResource(R.string.limit_texts_reached, PremiumManager.MAX_TEXTS_FREE)) },
                confirmButton = {
                    Button(onClick = { showLimitDialog = false; screen = Screen.SETTINGS }) {
                        Text(stringResource(R.string.buy_premium))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLimitDialog = false }) { Text(stringResource(R.string.back)) }
                }
            )
        }
    }

    /** ВЫБРАТЬ текст: загрузить в главный экран (без запуска показа — его делает «Старт»). */
    private fun loadOnly(body: String, id: Long?, title: String) {
        viewModel.script = body
        viewModel.currentTextId = id
        viewModel.currentTitle = title.ifBlank { null }
        // Память на текст: применяем сохранённые для него настройки (если есть).
        if (id != null) TextSettings.load(this, id)?.let { cfg ->
            viewModel.wpm = cfg.wpm
            viewModel.colWidth = cfg.col.toFloat()
            viewModel.narrowScreen = cfg.narrow
            viewModel.brightness = cfg.brightness.toFloat()
            viewModel.scrollMode = cfg.mode
            viewModel.bigText = cfg.big
            serviceState.value?.let { svc ->
                svc.setScrollSpeed(cfg.wpm)
                svc.setColumnWidth(cfg.col)
                svc.setScreenNarrow(cfg.narrow)
                svc.setBrightness(cfg.brightness)
            }
        }
    }

    private fun installCrashCatcher() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putString("last", sw.toString()).commit()
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun readAndConsumeCrash(): String? {
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val s = prefs.getString("last", null)
        if (s != null) prefs.edit().remove("last").commit()
        return s
    }

    override fun onStart() {
        super.onStart()
        // Перепроверяем сессию при каждом возврате: админ-сессия живёт 48ч, гостевая 24ч.
        // По истечении loggedIn станет false и снова покажется экран входа.
        loggedIn = security.hasSession()
        isAdmin = security.isAdminSession()
        appMode = AppModeManager.mode(this)
        isPremium = PremiumManager.isPremium(this)
        bindRequested = bindService(Intent(this, BleService::class.java), connection, 0)
    }

    /** Кнопка «Отключить» из уведомления открывает нас с этим action — отключаемся так же,
     *  как по кнопке в приложении. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == BleService.ACTION_DISCONNECT) {
            pendingDisconnect = true
            maybeHandlePendingDisconnect()
        }
    }

    /** Выполнить отложенное отключение, как только сервис привязан. */
    private fun maybeHandlePendingDisconnect() {
        if (pendingDisconnect && serviceState.value != null) {
            pendingDisconnect = false
            disconnectGlasses()
        }
    }

    override fun onStop() {
        super.onStop()
        stateJob?.cancel()
        if (bindRequested) {
            try {
                unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
            bindRequested = false
        }
    }

    private fun connectGlasses() {
        val intent = Intent(this, BleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (serviceState.value == null) {
            bindRequested = bindService(intent, connection, Context.BIND_AUTO_CREATE) || bindRequested
        }
    }

    private fun disconnectGlasses() {
        val svc = serviceState.value
        // Сначала — ЧИСТЫЙ ВЫХОД на очках (закрыть показ → очки вернутся домой), потом,
        // дав кадрам уйти, рвём BLE. Иначе очки висят с «соединение потеряно».
        if (svc != null && connState.value.isReady) {
            viewModel.stop()
            svc.exitGlasses()
            connState.value = ConnState("", false)
            Handler(Looper.getMainLooper()).postDelayed({ finishDisconnect() }, svc.exitFlushMs)
        } else {
            finishDisconnect()
        }
    }

    private fun finishDisconnect() {
        if (bindRequested) {
            try {
                unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
            bindRequested = false
        }
        stopService(Intent(this, BleService::class.java))
        stateJob?.cancel()
        serviceState.value = null
        connState.value = ConnState("", false)
    }
}

// =================================================================================================
// Permissions
// =================================================================================================

private fun requiredPermissions(): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list.toTypedArray()
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    val permissions = remember { requiredPermissions() }

    var granted by remember { mutableStateOf(permissions.all { isGranted(ctx, it) }) }
    var askedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        askedOnce = true
        granted = permissions.all { isGranted(ctx, it) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = permissions.all { isGranted(ctx, it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
    } else {
        PermissionRequestScreen(
            onGrant = { launcher.launch(permissions) },
            onOpenSettings = { openAppSettings(ctx) },
            showSettingsHint = askedOnce
        )
    }
}

@Composable
private fun PermissionRequestScreen(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    showSettingsHint: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.permissions_needed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.perm_intro))
        Spacer(Modifier.height(8.dp))
        Text("• ${stringResource(R.string.perm_bluetooth)}")
        Text("• ${stringResource(R.string.perm_notifications)}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Text("• ${stringResource(R.string.perm_location)}")
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.grant_permissions))
        }
        if (showSettingsHint) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.perm_settings_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_app_settings))
            }
        }
    }
}

// =================================================================================================
// Main teleprompter screen
// =================================================================================================

@Composable
private fun TeleprompterScreen(
    viewModel: TeleprompterViewModel,
    conn: ConnState,
    service: BleService?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenTexts: () -> Unit,
    onNewText: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val disconnectedText = stringResource(R.string.st_disconnected)

    // Каскадный вход: один оркестрованный момент — контент проявляется и слегка
    // поднимается. Уважаем «reduced motion» (системный масштаб анимаций = 0).
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
    var appeared by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { appeared = true }
    // «Fade-in on mount»: opacity 0→1, y 20→0, 300мс ease-out-quint.
    val reveal by animateFloatAsState(if (appeared) 1f else 0f, tween(300, easing = EaseOutQuint), label = "reveal")

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 20f
                }
                .animateContentSize(),   // плавная смена высоты при появлении/скрытии блоков
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) { Wordmark() }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            val effective = remember(viewModel.script, PremiumManager.isPremium(context)) {
                ContentPolicy.apply(context, viewModel.script)
            }

            // Память настроек НА ТЕКСТ: сохраняем текущие настройки для выбранного текста.
            val saveTextCfg = {
                viewModel.currentTextId?.let {
                    TextSettings.save(
                        context, it, viewModel.wpm, viewModel.colWidth.toInt(), viewModel.narrowScreen,
                        viewModel.brightness.toInt(), viewModel.scrollMode, viewModel.bigText
                    )
                }
                Unit
            }

            // ── 1. ТЕКСТ (сначала выбираем, что показывать) ──
            SectionLabel(stringResource(R.string.sec_text))
            CurrentTextCard(viewModel.currentTitle, viewModel.script)
            OutlinedButton(onClick = onOpenTexts, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.my_texts))
            }

            // ── 2. ПОДКЛЮЧЕНИЕ ──
            SectionLabel(stringResource(R.string.sec_connection))
            StatusRow(conn, disconnectedText)
            val connecting = !conn.isReady && conn.statusText.isNotEmpty() &&
                conn.statusText != disconnectedText
            Button(
                onClick = { if (conn.isReady || connecting) onDisconnect() else onConnect() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    when {
                        conn.isReady -> stringResource(R.string.disconnect)
                        connecting -> stringResource(R.string.cancel_search)
                        else -> stringResource(R.string.connect)
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }

            LaunchedEffect(
                service, conn.isReady, effective.text,
                viewModel.scrollMode, viewModel.wpm
            ) {
                if (conn.isReady) {
                    // Сервис сам применит политику тарифа (лимит + промо); шлём сырой текст.
                    service?.prepare(viewModel.script, viewModel.scrollMode, viewModel.wpm, viewModel.bigText)
                }
            }

            // ── 3. ПРОКРУТКА ──
            SectionLabel(stringResource(R.string.scroll_mode_title))
            Panel {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(stringResource(R.string.mode_auto), viewModel.scrollMode == ScrollMode.APP_AUTO, Modifier.weight(1f)) {
                        viewModel.scrollMode = ScrollMode.APP_AUTO
                        saveTextCfg()
                    }
                    ModeButton(stringResource(R.string.mode_manual), viewModel.scrollMode == ScrollMode.MANUAL, Modifier.weight(1f)) {
                        viewModel.scrollMode = ScrollMode.MANUAL
                        saveTextCfg()
                    }
                }
                Crossfade(targetState = viewModel.scrollMode, label = "modeHint") { m ->
                    Text(
                        text = stringResource(if (m == ScrollMode.MANUAL) R.string.mode_hint_manual else R.string.mode_hint_auto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(
                    visible = viewModel.scrollMode == ScrollMode.APP_AUTO,
                    // Вход 250мс ease-out, выход 180мс ease-in (≈75% — «exits faster than enters»).
                    enter = fadeIn(tween(250, easing = EaseOutQuint)) + expandVertically(tween(250, easing = EaseOutQuint)),
                    exit = fadeOut(tween(180, easing = EaseInOutCubic)) + shrinkVertically(tween(180, easing = EaseInOutCubic))
                ) {
                    Column {
                        Text(stringResource(R.string.speed_label, viewModel.wpm.toInt()), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = viewModel.wpm,
                            onValueChange = {
                                // Шаг 5 WPM — точнее прежних 10 делений.
                                val v = (Math.round(it / 5f) * 5).toFloat()
                                viewModel.wpm = v
                                Prefs.putInt(context, Prefs.KEY_SPEED, v.toInt())
                                service?.setScrollSpeed(v)
                            },
                            onValueChangeFinished = { saveTextCfg() },
                            valueRange = 60f..300f,
                            colors = monoSliderColors()
                        )
                        // Предполагаемое время прочтения ВСЕГО текста при текущей скорости.
                        // Мемоизируем: перенос текста (O(n)) не пересчитывается на каждой
                        // перерисовке (напр. при поллере прогресса 120мс), только при смене входов.
                        val narrowRows = if (viewModel.narrowScreen) EvenG2Protocol.EH_NARROW_ROWS else EvenG2Protocol.EH_WIDE_ROWS
                        val secs = remember(effective.text, viewModel.wpm, viewModel.colWidth.toInt(), narrowRows) {
                            ReadingTime.estimateSeconds(effective.text, viewModel.wpm, viewModel.colWidth.toInt(), narrowRows)
                        }
                        if (secs > 0) {
                            val timeStr = if (secs >= 60)
                                stringResource(R.string.time_min_sec, secs / 60, secs % 60)
                            else stringResource(R.string.time_sec, secs)
                            Text(
                                stringResource(R.string.est_time_label, timeStr),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(stringResource(R.string.column_width_label, viewModel.colWidth.toInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = viewModel.colWidth,
                    onValueChange = { viewModel.colWidth = it },
                    onValueChangeFinished = {
                        Prefs.putInt(context, Prefs.KEY_COL_WIDTH, viewModel.colWidth.toInt())
                        service?.setColumnWidth(viewModel.colWidth.toInt())
                        saveTextCfg()
                    },
                    valueRange = 12f..44f,
                    steps = 31,
                    colors = monoSliderColors()
                )
            }

            // ── 4. ЭКРАН ──
            SectionLabel(stringResource(R.string.sec_screen))
            Panel {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(stringResource(R.string.screen_wide), !viewModel.narrowScreen, Modifier.weight(1f)) {
                        viewModel.narrowScreen = false
                        service?.setScreenNarrow(false)
                        saveTextCfg()
                    }
                    ModeButton(stringResource(R.string.screen_narrow), viewModel.narrowScreen, Modifier.weight(1f)) {
                        viewModel.narrowScreen = true
                        service?.setScreenNarrow(true)
                        saveTextCfg()
                    }
                }
                Text(stringResource(R.string.brightness_label, viewModel.brightness.toInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = viewModel.brightness,
                    onValueChange = { viewModel.brightness = it },
                    onValueChangeFinished = {
                        Prefs.putInt(context, Prefs.KEY_BRIGHTNESS, viewModel.brightness.toInt())
                        service?.setBrightness(viewModel.brightness.toInt())
                        saveTextCfg()
                    },
                    valueRange = 0f..100f,
                    colors = monoSliderColors()
                )
            }

            // ── 5. ПРЕВЬЮ ──
            SectionLabel(stringResource(R.string.sec_preview))
            Text(
                stringResource(R.string.preview_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Прогресс/остаток: процент прочитанного + оставшееся время (читаемо, на телефоне).
            run {
                val pct = (viewModel.progress * 100).toInt().coerceIn(0, 100)
                val totalSec = remember(effective.text, viewModel.wpm, viewModel.colWidth.toInt(), viewModel.narrowScreen) {
                    ReadingTime.estimateSeconds(
                        effective.text, viewModel.wpm, viewModel.colWidth.toInt(),
                        if (viewModel.narrowScreen) EvenG2Protocol.EH_NARROW_ROWS else EvenG2Protocol.EH_WIDE_ROWS
                    )
                }
                val remainSec = (totalSec * (1f - viewModel.progress)).toInt().coerceAtLeast(0)
                Text(
                    "$pct%%   ·   %d:%02d".format(remainSec / 60, remainSec % 60),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Строки РОВНО как на очках: та же разбивка (center=false — центрирует
            // геометрия контейнера, и на превью — та же геометрия).
            val wrappedLines = remember(effective.text, viewModel.colWidth.toInt()) {
                EvenG2Protocol.wrapLines(effective.text, viewModel.colWidth.toInt(), center = false)
            }
            // Превью следует за РЕАЛЬНОЙ позицией показа на очках (опрос ~3 раза/сек).
            // Работает и НА ПАУЗЕ (не только PLAYING): на паузе очки всё ещё мотаются
            // пальцем/тачбаром — рамка и превью должны следовать, а не замирать.
            LaunchedEffect(viewModel.playState) {
                // 120мс: превью гладко следует за суб-строчным ходом очков (hubProgress теперь
                // непрерывный). Сдвиг мгновенный (scrollTo), эхо-петли нет (isScrollInProgress=false).
                while (viewModel.playState != PlayState.STOPPED) {
                    service?.let { viewModel.syncProgress(it.showProgress) }
                    kotlinx.coroutines.delay(120)
                }
            }
            // Тап по превью — разворачивается на весь экран.
            var previewFull by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth().clickable { previewFull = true }) {
                TeleprompterPreview(
                    lines = wrappedLines,
                    progress = viewModel.progress,
                    colChars = viewModel.colWidth.toInt(),
                    narrow = viewModel.narrowScreen,
                    brightness = viewModel.brightness,
                    // В авто превью ползёт само за позицией очков; в остальных ведёт палец.
                    driveFromProgress = viewModel.scrollMode == ScrollMode.APP_AUTO,
                    // Палец мотает очки в ЛЮБОМ режиме НЕПРЕРЫВНО (суб-строчная точность);
                    // в авто протяжка отодвигает тик, дальше ход продолжается с новой позиции.
                    onUserScrollFraction = { frac -> service?.hubScrollToFraction(frac) }
                )
            }
            if (previewFull) {
                Dialog(
                    onDismissRequest = { previewFull = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    // «Modal Entry»: scale 0.95→1 + opacity, 200мс ease-out.
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    val m by animateFloatAsState(if (shown) 1f else 0f, tween(200, easing = EaseOutQuint), label = "modal")
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = m; scaleX = 0.95f + 0.05f * m; scaleY = 0.95f + 0.05f * m }
                            .background(Color(0xFF080C0A))
                    ) {
                        TeleprompterPreview(
                            lines = wrappedLines,
                            progress = viewModel.progress,
                            colChars = viewModel.colWidth.toInt(),
                            narrow = viewModel.narrowScreen,
                            fullscreen = true,
                            brightness = viewModel.brightness,
                            initialFraction = viewModel.progress,
                            driveFromProgress = viewModel.scrollMode == ScrollMode.APP_AUTO,
                            onUserScrollFraction = { frac -> service?.hubScrollToFraction(frac) }
                        )
                        // Свернуть — крестик (или системная «назад»).
                        Text(
                            text = "✕",
                            color = Color(0xFF54E67A),
                            fontSize = 26.sp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 14.dp, end = 18.dp)
                                .clickable { previewFull = false }
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { viewModel.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )

            // ── 6. ВОСПРОИЗВЕДЕНИЕ (внизу — в зоне большого пальца) ──
            PlaybackControls(
                playState = viewModel.playState,
                enabled = conn.isReady && viewModel.script.isNotBlank(),
                onPrimary = {
                    when (viewModel.playState) {
                        PlayState.PLAYING -> {
                            viewModel.pause()
                            service?.pausePresentation()
                        }
                        PlayState.PAUSED -> {
                            service?.resumePresentation()
                            viewModel.start()
                        }
                        PlayState.STOPPED -> {
                            service?.setScreenNarrow(viewModel.narrowScreen)
                            service?.startPresentation(viewModel.script, viewModel.scrollMode, viewModel.wpm, viewModel.bigText)
                            viewModel.start()
                        }
                    }
                },
                onStop = {
                    viewModel.stop()
                    service?.stopPresentation()
                }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusRow(conn: ConnState, disconnectedText: String) {
    val label = conn.statusText.ifEmpty { disconnectedText }
    val target = when {
        conn.isReady -> Color(0xFF1A1A1A)          // подключено — чёрный (монохром)
        label == disconnectedText -> Color(0xFFBDBDBD)
        else -> Color(0xFF757575)                   // поиск — серый
    }
    val color by animateColorAsState(target, label = "statusDot")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(12.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlaybackControls(
    playState: PlayState,
    enabled: Boolean,
    onPrimary: () -> Unit,
    onStop: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onPrimary,
            enabled = enabled,
            modifier = Modifier.weight(2f).height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                when (playState) {
                    PlayState.STOPPED -> stringResource(R.string.start)
                    PlayState.PLAYING -> stringResource(R.string.pause)
                    PlayState.PAUSED -> stringResource(R.string.resume)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
        OutlinedButton(
            onClick = onStop,
            enabled = enabled && playState != PlayState.STOPPED,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.stop))
        }
    }
}

// ── Анимации (animate-skill: 200-300мс, выход быстрее входа, transform+opacity, springs) ──
private val EaseOutQuint = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val EaseInOutCubic = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)

/** Тактильное нажатие: масштаб 0.97 при press (100мс ease-out) — паттерн «Button Press». */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(100, easing = EaseOutQuint), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

// ── Дизайн-примитивы (frontend-design: типографика с характером, структура несёт смысл) ──

/** Вордмарк-хедер: открываем предметом. Название + технический сабтайтл разрядкой. */
@Composable
private fun Wordmark() {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        Text(
            stringResource(R.string.wordmark),
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            stringResource(R.string.wordmark_sub),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Карточка текущего текста: заголовок выбранного текста + короткий фрагмент (или
 *  приглашение выбрать, если ничего не выбрано). */
@Composable
private fun CurrentTextCard(title: String?, script: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (script.isBlank()) {
                Text(
                    stringResource(R.string.no_text_selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.current_text).uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.body_hint),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    script.trim().replace("\\s+".toRegex(), " ").take(90) +
                        if (script.trim().length > 90) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

/** Надзаголовок-eyebrow: маленький, uppercase, с разрядкой — как метка на пульте. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
    )
}

/** Панель-группа: тихая светлая поверхность со скруглением, объединяет связанные контролы. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** Монохромные цвета ползунка: чёрная активная часть, светло-серая неактивная. */
@Composable
private fun monoSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    activeTickColor = MaterialTheme.colorScheme.onPrimary,
    inactiveTrackColor = Color(0xFFE2E2E2),
    inactiveTickColor = Color(0xFFBFBFBF),
)

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Сегмент-переключатель в духе официалки: плавный переход цвета фона/текста.
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        label = "modeBg"
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "modeFg"
    )
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.height(48.dp).pressScale(interaction),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        contentColor = fg,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun CrashScreen(trace: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.crash_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB00020)
        )
        Text(stringResource(R.string.crash_hint))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        SelectionContainer {
            Text(
                text = trace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF101418))
                    .padding(12.dp),
                color = Color(0xFFFFCDD2)
            )
        }
    }
}

@Composable
private fun TeleprompterPreview(
    lines: List<String>,
    progress: Float,
    colChars: Int = 44,
    narrow: Boolean = false,
    fullscreen: Boolean = false,
    brightness: Float = 100f,
    initialFraction: Float = -1f,
    driveFromProgress: Boolean = true,
    onUserScrollFraction: ((Float) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    // Перетаскивание зелёной рамки (окна чтения): -1 = не тянут; иначе доля позиции 0..1.
    var frameDragFrac by remember { mutableStateOf(-1f) }
    val progressState = rememberUpdatedState(progress)
    // При развороте на весь экран подхватываем текущую позицию показа (один раз).
    if (initialFraction >= 0f) {
        var applied by remember { mutableStateOf(false) }
        LaunchedEffect(scroll.maxValue) {
            if (!applied && scroll.maxValue > 0) {
                applied = true
                scroll.scrollTo((initialFraction * scroll.maxValue).toInt())
            }
        }
    }
    // Авто-режим: прокрутка превью следует за прогрессом показа (пока палец не тянет).
    if (driveFromProgress) {
        LaunchedEffect(progress) {
            if (scroll.maxValue > 0 && !scroll.isScrollInProgress && frameDragFrac < 0f)
                scroll.scrollTo((progress * scroll.maxValue).toInt())
        }
    }
    // Палец мотает превью → очки листаются синхронно и НЕПРЕРЫВНО (суб-строчно).
    // Троттлинг ~120мс во время протяжки + один точный кадр по остановке.
    // ВАЖНО: когда превью ведёт ПРОГРЕСС (авто), программные сдвиги не считаются пальцем
    // (isScrollInProgress=false) — иначе эхо-петля откатывала авто-ход назад.
    if (onUserScrollFraction != null) {
        LaunchedEffect(scroll, lines, driveFromProgress) {
            var lastMs = 0L
            var lastFrac = 0f
            snapshotFlow { Triple(scroll.value, scroll.maxValue, scroll.isScrollInProgress) }
                .collect { (v, max, moving) ->
                    if (max <= 0) return@collect
                    val frac = (v.toFloat() / max).coerceIn(0f, 1f)
                    val now = System.currentTimeMillis()
                    val far = kotlin.math.abs(frac - lastFrac) > 0.001f
                    if (!moving && driveFromProgress) { lastFrac = frac; return@collect }
                    if ((moving && now - lastMs > 120L && far) || (!moving && far)) {
                        lastMs = now; lastFrac = frac; onUserScrollFraction(frac)
                    }
                }
        }
    }
    val projected = remember(lines) { lines.joinToString("\n") { it.ifEmpty { " " } } }
    // ФОРМАТ ОЧКОВ: пропорции экрана 576×288 (2:1), зелёный на чёрном. Однородный фон —
    // без вертикальных «полос». Узость показывает сама РАМКА окна (4 строки vs 9), а не
    // затемнённые зоны сверху/снизу. Строки 1:1 с очками (softWrap=false).
    val screen = Color(0xFF080C0A)
    val glow = Color(0xFF54E67A)
    BoxWithConstraints(
        modifier = (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(2f))
            .clip(RoundedCornerShape(if (fullscreen) 0.dp else 14.dp))
            .background(screen)
            .then(if (fullscreen) Modifier else Modifier.border(1.dp, Color(0xFF203029), RoundedCornerShape(14.dp)))
    ) {
        val barW = maxWidth * (26f / 576f)
        val bodyArea = maxWidth - barW
        val colW = bodyArea * ((colChars * 13f + 8f) / 552f).coerceAtMost(0.98f)
        // Символ шрифта очков ≈ 13px при строке 27px → ширина символа ≈ 0.62 кегля.
        val fontSp = (colW.value / colChars / 0.62f).sp
        val vPad = if (fullscreen) 16.dp else 10.dp
        // Яркость очков управляет яркостью зелёного в превью (тускнеет/ярче).
        val textAlpha = (0.35f + 0.65f * (brightness / 100f)).coerceIn(0.3f, 1f)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(end = barW)
                .fillMaxWidth()
                .verticalScroll(scroll),
            contentAlignment = Alignment.TopCenter
        ) {
            var textLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            val layout = textLayout
            val density = LocalDensity.current
            val windowLines = if (narrow) 4 else 9
            val maxFirst = (lines.size - windowLines).coerceAtLeast(0)
            // Пока тянут рамку — позиция из drag, иначе следует за реальным показом.
            val effProgress = if (frameDragFrac >= 0f) frameDragFrac else progress
            val grabbed = frameDragFrac >= 0f

            // 1) ВИДИМАЯ рамка окна чтения — ПОД текстом (не перекрывает буквы).
            if (lines.isNotEmpty() && layout != null && layout.lineCount > 0) {
                val first = kotlin.math.round(effProgress * maxFirst).toInt().coerceIn(0, layout.lineCount - 1)
                val last = (first + windowLines - 1).coerceIn(first, layout.lineCount - 1)
                val topDp = with(density) { layout.getLineTop(first).toDp() }
                val hDp = with(density) { (layout.getLineBottom(last) - layout.getLineTop(first)).toDp() }
                Box(
                    modifier = Modifier
                        .offset(y = vPad + topDp)
                        .width(colW + 18.dp)
                        .height(hDp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(glow.copy(alpha = if (grabbed) 0.22f else 0.10f))
                        .border(if (grabbed) 1.5.dp else 1.dp, glow.copy(alpha = if (grabbed) 0.8f else 0.5f), RoundedCornerShape(10.dp))
                )
            }
            // 2) ТЕКСТ — ПОВЕРХ рамки.
            Text(
                text = projected,
                color = glow.copy(alpha = textAlpha),
                fontSize = fontSp,
                lineHeight = fontSp * 1.4f,
                softWrap = false,
                onTextLayout = { textLayout = it },
                modifier = Modifier
                    .width(colW)
                    .padding(vertical = vPad)
            )
            // 3) Прозрачный слой ПЕРЕТАСКИВАНИЯ — ПОВЕРХ текста, ровно на месте рамки
            //    (иначе текст перехватывал бы касание). Тянем → двигаем позицию и ведём очки.
            if (onUserScrollFraction != null && lines.isNotEmpty() && layout != null && layout.lineCount > 0) {
                val first = kotlin.math.round(effProgress * maxFirst).toInt().coerceIn(0, layout.lineCount - 1)
                val last = (first + windowLines - 1).coerceIn(first, layout.lineCount - 1)
                val topDp = with(density) { layout.getLineTop(first).toDp() }
                val hDp = with(density) { (layout.getLineBottom(last) - layout.getLineTop(first)).toDp() }
                val travelPx = layout.getLineTop(maxFirst.coerceIn(0, layout.lineCount - 1)) - layout.getLineTop(0)
                Box(
                    modifier = Modifier
                        .offset(y = vPad + topDp)
                        .width(colW + 18.dp)
                        .height(hDp)
                        .pointerInput(travelPx, maxFirst) {
                            detectVerticalDragGestures(
                                onDragStart = { frameDragFrac = progressState.value.coerceIn(0f, 1f) },
                                onDragEnd = {
                                    onUserScrollFraction(frameDragFrac.coerceIn(0f, 1f))
                                    frameDragFrac = -1f
                                },
                                onDragCancel = { frameDragFrac = -1f }
                            ) { change, dy ->
                                change.consume()
                                if (travelPx > 1f) {
                                    val start = if (frameDragFrac >= 0f) frameDragFrac else progressState.value
                                    val nf = (start + dy / travelPx).coerceIn(0f, 1f)
                                    frameDragFrac = nf
                                    onUserScrollFraction(nf)
                                }
                            }
                        }
                )
            }
        }
        // Полоса «сколько осталось» — тонкая, у самого края (10 сегментов, заполнено = прочитано).
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(barW)
                .padding(vertical = 8.dp, horizontal = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val filled = (progress * 10f).toInt().coerceIn(0, 10)
            repeat(10) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < filled) glow else Color(0xFF16241B))
                )
            }
        }
    }
}

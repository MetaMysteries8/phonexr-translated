package com.samrat.cardboardhands

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import zone.ien.hig.CupertinoText
import zone.ien.hig.icons.CupertinoIcons
import zone.ien.hig.icons.filled.Cart
import zone.ien.hig.icons.filled.Gearshape
import zone.ien.hig.icons.filled.House
import zone.ien.hig.icons.filled.Person
import zone.ien.hig.theme.CupertinoTheme
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf(0)
    private var status by mutableStateOf<String?>(null)
    private var games by mutableStateOf<List<GameLibrary.Game>?>(null)
    private var busy by mutableStateOf<String?>(null)
    /** A game waiting for the user's decision: patch it, or install the patched build. */
    private var pendingPatch by mutableStateOf<GameLibrary.Game?>(null)
    private var ready by mutableStateOf<Ready?>(null)
    private var error by mutableStateOf<String?>(null)
    private var startAfterPermission = false
    /** Bumped on every resume, so dialogs re-check what is installed after the system uninstaller. */
    private var resumes by mutableStateOf(0)

    private var shizuku by mutableStateOf(VirtualScreen.Access.NOT_RUNNING)
    private var cinemaScene by mutableStateOf(CinemaActivity.SCENE_ROOM)
    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { shizuku = VirtualScreen.access() }
    }

    private var storeItems by mutableStateOf<List<GameStore.Item>?>(null)
    private var androidApps by mutableStateOf(false)
    private var languagePicker by mutableStateOf(false)

    /** A VR mode from the store whose activation steps are shown. */
    private var guide by mutableStateOf<VrMode?>(null)
    private var webApps by mutableStateOf<List<WebApps.App>>(emptyList())
    private var installedWeb by mutableStateOf<Set<String>>(emptySet())
    private var storeError by mutableStateOf<String?>(null)
    private var storeLoading by mutableStateOf(false)
    /** Download progress per store path: 0..1, or -1 while the size is unknown. */
    private val downloads = mutableStateMapOf<String, Float>()
    private val storeIcons = mutableStateMapOf<String, Bitmap>()
    private val storeDescriptions = mutableStateMapOf<String, String>()

    /**
     * A patched APK ready to install. [replacesPackage] is set when the same package is installed
     * with a different signature, so the old copy must be removed first.
     */
    private data class Ready(val result: ApkPatcher.Result, val label: String?, val replacesPackage: String?)

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterPermission) startTracking()
        else if (!granted) status = tr("Для рук нужен доступ к камере")
        startAfterPermission = false
    }
    private val scanCardboard = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val mm = result.data?.getIntExtra(CardboardQrActivity.EXTRA_IPD_MM, -1) ?: -1
        if (mm !in Settings.MIN_IPD_MM..Settings.MAX_IPD_MM) {
            error = tr("Профиль Cardboard не содержит корректное межзрачковое расстояние")
        } else {
            ipd = mm
            Settings.setIpdMm(this, mm)
            status = "Профиль Cardboard применён: $mm мм"
        }
    }
    /** The VR home needs the camera (passthrough, hands) and the gallery (Spatial Photos). */
    private val enterVr = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startActivity(Intent(this, VrHomeActivity::class.java))
    }
    /** A Minecraft mod from the phone's files. */
    private val chooseMod = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread {
            val problem = runCatching { MinecraftMods.installFromUri(this, uri) }.getOrElse { it.message }
            runOnUiThread { if (problem != null) error = problem }
        }.start()
    }
    private var mods by mutableStateOf<List<GameStore.Item>?>(null)
    private val modProgress = mutableStateMapOf<String, Int>()

    private fun installMod(item: GameStore.Item) {
        modProgress[item.path] = 0
        Thread {
            val file = runCatching {
                GameStore.download(item, MinecraftMods.folder(this)) { value ->
                    runOnUiThread { modProgress[item.path] = (value * 100).toInt().coerceAtLeast(0) }
                }
            }
            runOnUiThread {
                modProgress.remove(item.path)
                file.onSuccess { MinecraftMods.install(this, it)?.let { problem -> error = problem } }
                    .onFailure { error = "«${item.title}» не скачался" }
            }
        }.start()
    }

    private val chooseApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) patch(uri, replaces = null)
    }
    private var pendingPxr: File? = null
    private val savePxr = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = pendingPxr.also { pendingPxr = null }
        if (uri != null && source != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
        }.onFailure { error = tr("Не удалось сохранить .pxr") }
    }
    private val chooseForPxr = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = tr("Сборка .pxr…")
        Thread {
            runCatching {
                val payload = PxrPackage.androidPayload(this, uri)
                val prepared = ApkPatcher.patch(this, payload).apk
                PxrPackage.packAndroid(this, prepared, "PhoneXR-app")
            }.onSuccess { file ->
                runOnUiThread { busy = null; pendingPxr = file; savePxr.launch(file.name) }
            }.onFailure { failure ->
                runOnUiThread { busy = null; error = failure.localizedMessage ?: tr("Не удалось собрать .pxr") }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt(KEY_TAB) ?: 0
        L10n.init(this)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)
        setContent { PhoneXRTheme { Root() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        resumes++
        androidApps = BuildConfig.LITE || AndroidAppsContent.enabled(this)
        if (BuildConfig.LITE) AndroidAppsContent.setEnabled(this, true)
        screenShape = Settings.screenShape(this)
        curvedScreen = Settings.curvedScreen(this)
        refresh = Settings.refresh(this)
        keyboardWindow = Settings.keyboardWindow(this)
        depthInstalled = DepthModel.installed(this)
        ipd = Settings.ipdMm(this)
        lensOffset = Settings.lensOffsetMm(this)
        sixDof = Settings.load(this).sixDof
        trackingSmoothness = Settings.load(this).trackingSmoothness
        panelFollows = Settings.panelFollows(this)
        clipboard = Settings.sharedClipboard(this)
        travel = Settings.travelMode(this)
        guest = Settings.guestMode(this)
        if (clipboard) SharedClipboard.start(this)
        refreshGames()
        checkUpdateOnce()
        shizuku = VirtualScreen.access()
    }

    private fun refreshGames() {
        Thread {
            val found = runCatching { GameLibrary.scan(this) }.getOrDefault(emptyList())
            runOnUiThread { games = found }
        }.start()
    }

    @Composable
    private fun Root() {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                when (tab) {
                    0 -> MenuTab()
                    1 -> StoreTab()
                    else -> SettingsTab()
                }
            }
            HigTabBar(
                tabs = listOf(
                    HigTab(CupertinoIcons.Filled.House, tr("Меню")),
                    HigTab(CupertinoIcons.Filled.Cart, tr("Магазин")),
                    HigTab(CupertinoIcons.Filled.Gearshape, tr("Настройки"))
                ),
                selected = tab,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSelect = { selectTab(it) }
            )
        }

        pendingPatch?.let { PatchDialog(it) }
        guide?.let { GuideDialog(it) }
        if (languagePicker) {
            HigAlert(
                title = tr("Язык"),
                message = "PhoneXR Latitude",
                actions = L10n.Lang.values().map { lang ->
                    HigAction((if (lang == L10n.current) "✓ " else "") + lang.title) {
                        languagePicker = false
                        L10n.set(this@MainActivity, lang)
                        recreate()
                    }
                } + HigAction(tr("Отмена"), HigActionStyle.CANCEL) { languagePicker = false },
                onDismiss = { languagePicker = false }
            )
        }
        update?.let { found ->
            HigAlert(
                title = "Доступно PhoneXR ${found.version}",
                message = Updates.formatSize(found.size),
                actions = listOf(
                    HigAction(tr("Позже"), HigActionStyle.CANCEL) { update = null },
                    HigAction(tr("Подробнее")) { update = null; start(UpdateActivity::class.java) }
                ),
                onDismiss = { update = null }
            )
        }
        ready?.let { ReadyDialog(it) }
        error?.let { message ->
            HigAlert(
                title = tr("Не получилось"),
                message = message,
                actions = listOf(HigAction("OK") { error = null }),
                onDismiss = { error = null }
            )
        }
    }

    private var updateChecked = false
    private var update by mutableStateOf<Updates.Release?>(null)

    /** Automatic updates: once per start, a new version opens the update screen. */
    private fun checkUpdateOnce() {
        if (updateChecked || !Updates.autoUpdate(this)) return
        updateChecked = true
        Thread {
            val found = runCatching { Updates.check(this) }.getOrNull() ?: return@Thread
            runOnUiThread { update = found }
        }.start()
    }

    private fun selectTab(index: Int) {
        tab = index
        if (index == 1 && storeItems == null && !storeLoading) refreshStore()
    }

    private fun scanCardboardProfile() {
        scanCardboard.launch(Intent(this, CardboardQrActivity::class.java))
    }

    // ---------------------------------------------------------------- Menu

    @Composable
    private fun MenuTab() {
        HigPage(
            title = "PhoneXR Latitude",
            subtitle = tr("VR на телефоне: руки в камере, Joy‑Con вместо контроллеров"),
            bottomInset = TAB_BAR_ROOM
        ) {
            if (!sixDof) HigSection(
                title = tr("Сейчас работает 3DoF"),
                footer = tr("Поворот головы и контроллеры работают, но перемещение по комнате, граница, стены, столы и физика комнаты требуют 6DoF.")
            ) {
                HigLink(tr("Включить 6DoF")) {
                    if (BuildConfig.LITE) error = tr("6DoF доступен в PhoneXR Full")
                    else {
                        sixDof = true
                        Settings.save(this@MainActivity, Settings.load(this@MainActivity).copy(sixDof = true))
                    }
                }
            }
            HigSection(footer = tr("VR‑дом в смешанной реальности: щипок — открыть, кулак — перетащить иконки, ладонь к лицу + щипок — меню. Joy‑Con: ZR или A.")) {
                HigLink(tr("Войти в VR")) {
                    enterVr.launch(
                        if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.RECORD_AUDIO)
                        else arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
                    )
                }
            }

            HigSection(
                title = tr("Игры"),
                footer = tr("Нажмите на игру, чтобы включить трекинг и запустить её. ") +
                    tr("Игры Gear VR и сборки для гарнитур сначала нужно пропатчить: PhoneXR откроет им рантайм ") +
                    tr("PhoneXR, впишет новый трекинг и оптимизирует их под телефон.")
            ) {
                val list = games
                when {
                    list == null -> HigRow(tr("Поиск игр…"), trailing = { HigSpinner() })
                    list.isEmpty() -> HigRow(tr("VR-игры не найдены"), tr("Установите игру из магазина или из файла"))
                    else -> list.forEach { game -> GameRow(game) }
                }
            }

            HigSection(
                title = tr("Установка"),
                footer = tr("APK OpenXR-игры, игры Gear VR (64 и 32 бита) или пакет .pxr. ") +
                    tr("PhoneXR впишет новый трекинг, оптимизирует сборку под телефон, подпишет её и откроет установку.")
            ) {
                HigLink(busy ?: tr("Установить игру из файла"), enabled = busy == null) {
                    chooseApk.launch(
                        arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream")
                    )
                }
                HigLink(tr("Магазин игр")) { selectTab(1) }
                HigLink(tr("Собрать .pxr из APK"), enabled = busy == null) {
                    chooseForPxr.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                }
            }

            RuntimeSection()

            HigSection(
                title = "Daydream",
                footer = tr("Игры Daydream ищут Google VR Services. PhoneXR ставит Opendream Services 1.13 — ") +
                    tr("после этого игры Daydream и Cardboard появляются в списке игр и в VR‑доме.")
            ) {
                if (Daydream.servicesInstalled(this@MainActivity)) {
                    HigRow("VR Services", tr("Opendream установлен"))
                } else {
                    HigLink(tr("Установить Opendream Services")) { Daydream.installServices(this@MainActivity) }
                }
            }

            HigSection(title = tr("Трекинг"), footer = status) {
                HigLink(tr("Остановить трекинг")) {
                    stopService(Intent(this@MainActivity, HandTrackingService::class.java))
                    status = tr("Трекинг остановлен")
                }
            }
        }
    }

    /** PhoneXR Runtime for OpenXR games: install or update it, then pick it in the OpenXR broker. */
    @Composable
    private fun RuntimeSection() {
        val state = if (resumes >= 0) PhoneXrRuntime.state(this) else PhoneXrRuntime.State.MISSING
        HigSection(
            title = "OpenXR",
            footer = "The bundled runtime is based on Monado. Select it in OpenXR Runtime Broker after installing. " +
                "Quest titles still need compatible Android APIs and game assets."
        ) {
            when (state) {
                PhoneXrRuntime.State.READY -> HigRow("PhoneXR Runtime", tr("Установлен"))
                PhoneXrRuntime.State.OUTDATED -> HigLink(tr("Обновить PhoneXR Runtime")) { PhoneXrRuntime.install(this@MainActivity) }
                PhoneXrRuntime.State.MISSING -> if (PhoneXrRuntime.bundled(this@MainActivity)) {
                    HigLink(tr("Установить PhoneXR Runtime")) { PhoneXrRuntime.install(this@MainActivity) }
                } else {
                    HigRow("PhoneXR Runtime", tr("Не входит в эту сборку"))
                }
            }
            HigLink(
                "OpenXR Runtime Broker",
                value = if (PhoneXrRuntime.brokerInstalled(this@MainActivity)) tr("Открыть") else "Google Play"
            ) { PhoneXrRuntime.openBroker(this@MainActivity) }
        }
    }

    private fun openCinema(target: String, scene: String = cinemaScene) {
        startActivity(
            Intent(this, CinemaActivity::class.java)
                .putExtra(CinemaActivity.EXTRA_PACKAGE, target)
                .putExtra(CinemaActivity.EXTRA_SCENE, scene)
        )
    }

    @Composable
    private fun HigScope.GameRow(game: GameLibrary.Game) {
        HigItem(
            title = game.label,
            details = listOf(GameLibrary.describe(game) + if (game.checksPurchase) " · проверка покупки Oculus" else ""),
            detailColor = if (game.kind == GameLibrary.Kind.VRAPI_ORIGINAL || game.kind == GameLibrary.Kind.OPENXR_ORIGINAL)
                HigColors.accent else Color.Unspecified,
            icon = { AppIcon(game.packageName) },
            onClick = { open(game) }
        )
    }

    @Composable
    private fun AppIcon(packageName: String) {
        val drawable: Drawable? = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        Canvas(modifier = Modifier.size(30.dp)) {
            drawIntoCanvas { canvas ->
                drawable?.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawable?.draw(canvas.nativeCanvas)
            }
        }
    }

    // ---------------------------------------------------------------- Store

    @Composable
    private fun StoreTab() {
        HigPage(
            title = tr("Магазин"),
            subtitle = tr("OpenXR, Quest и Pico: PhoneXR сам скачает и подготовит APK."),
            bottomInset = TAB_BAR_ROOM
        ) {
            HigSection(
                title = tr("VR‑режимы"),
                footer = tr("Обычные Minecraft и Roblox на большом экране в VR: своя комната, поворот головы, ") +
                    tr("игра двумя руками или Joy‑Con. Нажмите на режим — появится инструкция.")
            ) {
                VR_MODES.forEach { mode -> VrModeRow(mode) }
            }
            HigSection(
                title = tr("Приложения PhoneXR"),
                footer = tr("Android‑приложения: любые приложения телефона окнами в VR (нужен Shizuku). Появляется на главном экране VR.")
            ) {
                val added = resumes >= 0 && androidApps
                HigLink(tr("Android‑приложения"), value = if (BuildConfig.LITE) tr("Включено") else if (added) tr("Удалить") else tr("Получить")) {
                    if (!BuildConfig.LITE) {
                        androidApps = !added
                        AndroidAppsContent.setEnabled(this@MainActivity, !added)
                    }
                }
            }
            HigSection(
                title = tr("Моды Minecraft"),
                footer = tr("Дополнения, наборы ресурсов и миры для Minecraft Bedrock (.mcaddon, .mcpack, .mcworld). " +
                    tr("Minecraft сам импортирует мод, потом включите его в настройках мира."))
            ) {
                HigLink("PhoneXR VR — VR для Minecraft", value = if (resumes >= 0 && MinecraftBridge.modInstalled(this@MainActivity)) "✓" else tr("Установить")) {
                    MinecraftBridge.installMod(this@MainActivity)?.let { error = it }
                }
                HigLink(tr("Установить мод из файла")) { chooseMod.launch(arrayOf("*/*")) }
                mods?.forEach { item ->
                    val progress = modProgress[item.path]
                    HigLink(item.title, value = progress?.let { "$it%" } ?: tr("Установить"), enabled = progress == null) { installMod(item) }
                }
            }
            val items = storeItems
            HigSection(
                title = tr("Игры"),
                footer = when {
                    storeError != null -> storeError
                    items != null && items.isEmpty() -> tr(GameStore.EMPTY_HINT)
                    else -> null
                }
            ) {
                when {
                    storeLoading && items == null -> HigRow(tr("Загрузка…"), trailing = { HigSpinner() })
                    items.isNullOrEmpty() -> HigRow(if (storeError != null) tr("Магазин недоступен") else tr("Пока пусто"))
                    else -> items.forEach { item -> StoreRow(item) }
                }
            }
            if (webApps.isNotEmpty()) {
                HigSection(
                    title = tr("Веб‑приложения"),
                    footer = tr("Открываются в браузере PhoneXR прямо в VR. Добавленные появляются на главном экране VR.")
                ) {
                    webApps.forEach { app ->
                        val added = app.url in installedWeb
                        HigLink(app.name, value = if (added) tr("Открыть") else tr("Добавить")) {
                            if (added) {
                                if (!WebApps.open(this@MainActivity, app.url)) error = tr("Установите браузер PhoneXR")
                            } else {
                                WebApps.add(this@MainActivity, app)
                                installedWeb = installedWeb + app.url
                            }
                        }
                    }
                }
            }
            HigSection(title = "Historical releases", footer = "External archive by Another Axiom. APK compatibility and redistribution rights have not been verified.") {
                HigLink("Gorilla Tag release archive", value = "GitHub") {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GameStore.GORILLA_TAG_ARCHIVE)))
                }
            }
            HigSection {
                HigLink(if (storeLoading) tr("Обновление…") else tr("Обновить"), enabled = !storeLoading) { refreshStore() }
            }
        }
    }

    @Composable
    private fun HigScope.VrModeRow(mode: VrMode) {
        val installed = resumes >= 0 && isInstalled(mode.packageName)
        HigItem(
            title = mode.title,
            details = listOf(mode.subtitle),
            icon = { AppIcon(if (installed) mode.packageName else packageName) },
            trailing = { HigText(if (installed) tr("Играть") else tr("Скачать"), color = HigColors.accent) },
            onClick = { guide = mode }
        )
    }

    /** How to switch a VR mode on: the game from Google Play, Shizuku, then play from PhoneXR. */
    @Composable
    private fun GuideDialog(mode: VrMode) {
        val installed = resumes >= 0 && isInstalled(mode.packageName)
        val ready = shizuku == VirtualScreen.Access.READY
        val steps = listOf(
                        (if (installed) "✓ " else "1. ") + "Установите ${mode.game} из Google Play.",
                        (if (ready) "✓ " else "2. ") + "Установите и запустите Shizuku (через отладку по Wi‑Fi), разрешите доступ PhoneXR.",
                        "3. Нажмите «Играть»: игра откроется на большом экране — ${mode.scene}.",
                        if (mode.packageName == MINECRAFT)
                            "4. Мод PhoneXR VR ставится в Minecraft сам при первом «Играть». В Minecraft: Настройки → Общие → " +
                                "выключите «Требовать зашифрованные веб‑сокеты»; в мире включите читы и набор параметров поведения «PhoneXR VR». " +
                                "В мире покажите ладонь к лицу и сведите пальцы — PhoneXR подключит мод. Дальше: голова — взгляд на 360°, " +
                                "руки видны в мире, кулак — ломать и бить, щипок — поставить блок, «пистолет» из пальцев — идти."
                        else "4. Управление: щипок любой руки — нажатие по экрану, две руки — два пальца. " +
                            "Joy‑Con и геймпад работают как в самой игре. Тап по телефону выравнивает вид.",
        )
        val next = when {
            !installed -> HigAction(tr("Скачать")) {
                guide = null
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${mode.packageName}")))
            }
            !ready -> HigAction("Shizuku") {
                guide = null
                if (shizuku == VirtualScreen.Access.NEEDS_PERMISSION) VirtualScreen.requestPermission()
                else packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                    ?: startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
            }
            mode.packageName == MINECRAFT && !MinecraftBridge.modInstalled(this@MainActivity) -> HigAction(tr("Установить мод")) {
                guide = null
                // First time: Minecraft imports the VR mod, then "Играть" starts VR.
                MinecraftBridge.installMod(this@MainActivity)?.let { error = it }
            }
            else -> HigAction(tr("Играть")) {
                guide = null
                openCinema(mode.packageName, mode.sceneId)
            }
        }
        HigAlert(
            title = "Как включить ${mode.title}",
            message = steps.joinToString("\n"),
            actions = listOf(HigAction(tr("Закрыть"), HigActionStyle.CANCEL) { guide = null }, next),
            onDismiss = { guide = null }
        )
    }

    @Composable
    private fun HigScope.StoreRow(item: GameStore.Item) {
        val progress = downloads[item.path]
        HigItem(
            title = item.title,
            details = listOfNotNull(
                storeDescriptions[item.path],
                item.extension.uppercase() + if (item.size > 0) " · " + formatSize(item.size) else ""
            ),
            enabled = progress == null,
            icon = { StoreIcon(item) },
            trailing = {
                when {
                    progress == null -> HigText(tr("Загрузить"), color = HigColors.accent)
                    progress < 0f -> HigSpinner()
                    progress >= 1f -> HigText("Подготовка…")
                    else -> HigText("${(progress * 100).roundToInt()}%")
                }
            },
            onClick = { if (progress == null && busy == null) downloadAndInstall(item) }
        )
    }

    @Composable
    private fun StoreIcon(item: GameStore.Item) {
        val icon = storeIcons[item.path]
        if (icon != null) {
            Image(
                icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                AppIcon(packageName)
            }
        }
    }

    private fun refreshStore() {
        storeLoading = true
        storeError = null
        Thread {
            try {
                val items = GameStore.list()
                val foundMods = runCatching { GameStore.mods() }.getOrDefault(emptyList())
                runOnUiThread { mods = foundMods }
                val web = WebApps.fromStore()
                runOnUiThread {
                    webApps = web
                    installedWeb = WebApps.installed(this).map { it.url }.toSet()
                    storeItems = items
                    storeLoading = false
                }
                for (item in items) {
                    GameStore.description(item)?.let { text -> runOnUiThread { storeDescriptions[item.path] = text } }
                    GameStore.icon(item)?.let { bitmap -> runOnUiThread { storeIcons[item.path] = bitmap } }
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Store", "Store listing failed", failure)
                runOnUiThread {
                    storeLoading = false
                    storeError = failure.localizedMessage ?: tr("Магазин недоступен")
                }
            }
        }.start()
    }

    private fun downloadAndInstall(item: GameStore.Item) {
        // Internal installs use the same shell service that runs phone apps in PhoneXR windows.
        if (VirtualScreen.access() != VirtualScreen.Access.READY) {
            error = "Для внутренней установки запустите Shizuku и разрешите доступ PhoneXR."
            return
        }
        downloads[item.path] = 0f
        Thread {
            try {
                // Downloads live in files, not in the cache: Android empties the cache when the
                // phone runs low on space, and a game would disappear mid-download.
                val file = GameStore.download(item, File(filesDir, "store")) { value ->
                    runOnUiThread { downloads[item.path] = value }
                }
                // Quest, Pico and generic OpenXR APKs are prepared automatically inside PhoneXR.
                // A .pxr already carries a prepared payload and only needs unpacking.
                val apk = if (item.extension == "pxr") PxrPackage.androidApk(this, Uri.fromFile(file))
                else ApkPatcher.patch(this, Uri.fromFile(file)).apk
                runOnUiThread {
                    downloads.remove(item.path)
                    installApk(apk)
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Store", "Download failed: ${item.path}", failure)
                runOnUiThread {
                    downloads.remove(item.path)
                    error = "«${item.title}» не скачалась: " +
                        (failure.localizedMessage ?: failure.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f МБ".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f КБ".format(bytes / 1024.0)
    }

    // ---------------------------------------------------------------- Settings

    private var screenShape by mutableStateOf(Settings.ScreenShape.NORMAL)
    private var curvedScreen by mutableStateOf(true)
    private var refresh by mutableStateOf(Settings.Refresh.AUTO)
    private var keyboardWindow by mutableStateOf(true)
    private var ipd by mutableStateOf(Settings.DEFAULT_IPD_MM)
    private var lensOffset by mutableStateOf(0)
    private var sixDof by mutableStateOf(true)
    private var trackingSmoothness by mutableStateOf(50)
    private var panelFollows by mutableStateOf(true)
    private var clipboard by mutableStateOf(false)
    private var travel by mutableStateOf(false)
    private var guest by mutableStateOf(false)
    private val spatialAudio: String
        get() = if (android.os.Build.VERSION.SDK_INT >= 32 &&
            getSystemService(android.media.AudioManager::class.java)?.spatializer?.isAvailable == true
        ) "Available for video and cinema" else "Not supported by this phone"
    private var depthInstalled by mutableStateOf(false)
    private var depthProgress by mutableStateOf<Float?>(null)

    /** The depth network is tens of megabytes: it is fetched once, with the progress in plain sight. */
    private fun downloadDepth() {
        if (depthProgress != null) return
        depthProgress = 0f
        Thread {
            val result = runCatching {
                DepthModel.download(this) { value -> runOnUiThread { depthProgress = value } }
            }
            runOnUiThread {
                depthProgress = null
                result.onSuccess { depthInstalled = true }
                    .onFailure { error = "Depth model download failed: ${it.localizedMessage ?: it.javaClass.simpleName}" }
            }
        }.start()
    }

    @Composable
    private fun SettingsTab() {
        val rates = remember { DisplayRate.available(this) }
        HigPage(title = tr("Настройки"), bottomInset = TAB_BAR_ROOM) {
            HigSection(title = tr("Управление")) {
                HigLink(tr("Управление и Joy‑Con")) { start(SettingsActivity::class.java) }
                HigLink(tr("Joy‑Con через камеру")) { start(JoyConCameraActivity::class.java) }
            }
            HigSection(
                title = "Head tracking",
                footer = "3DoF follows head rotation. 6DoF also tracks your position through ARCore."
            ) {
                HigChoice("3DoF", "Head rotation", !sixDof) {
                    sixDof = false
                    Settings.save(this@MainActivity, Settings.load(this@MainActivity).copy(sixDof = false))
                }
                if (BuildConfig.LITE) {
                    HigRow("6DoF", "Available in Latitude Full", detailColor = HigColors.secondary)
                } else {
                    HigChoice("6DoF", "Rotation and position", sixDof) {
                        sixDof = true
                        Settings.save(this@MainActivity, Settings.load(this@MainActivity).copy(sixDof = true))
                    }
                }
            }
            if (BuildConfig.LITE) HigSection(
                title = "Edition",
                footer = "Lite leaves out Persona, depth estimation, and ARCore 6DoF. " +
                    "It uses a smaller camera frame for hand tracking. " +
                    "Games, cinema, controllers, and VR home remain available."
            ) {
                HigRow("Latitude Lite", "Lightweight edition", detailColor = HigColors.accent)
            }
            if (!BuildConfig.LITE) {
                HigSection(
                    title = "Persona",
                    footer = "The outward camera captures a face scan while the phone is in the viewer."
                ) {
                    HigLink(tr("Сканировать камерой"), value = if (resumes >= 0 && Persona.exists(this@MainActivity)) tr("Готово") else null) {
                        start(PersonaCaptureActivity::class.java)
                    }
                }
            }
            HigSection(
                title = "Other devices",
                footer = "Copy text on one phone and paste it on another. " +
                    "Both phones must share a Wi-Fi network."
            ) {
                HigSwitchRow("Shared clipboard", clipboard) {
                    clipboard = it
                    Settings.setSharedClipboard(this@MainActivity, it)
                    if (it) SharedClipboard.start(this@MainActivity) else SharedClipboard.stop()
                }
                HigLink("Send clipboard to another phone") {
                    val sent = SharedClipboard.send(this@MainActivity)
                    status = if (sent == null) "Clipboard is empty" else "Sent: ${sent.take(40)}"
                }
            }
            HigSection(
                title = "System",
                footer = "Travel mode reduces view drift in a moving vehicle. " +
                    "Guest mode keeps a visitor’s calibration and settings separate."
            ) {
                HigSwitchRow("Travel mode", travel) {
                    travel = it
                    Settings.setTravelMode(this@MainActivity, it)
                }
                HigSwitchRow("Guest mode", guest) {
                    guest = it
                    Settings.setGuestMode(this@MainActivity, it)
                }
                HigRow("Spatial audio", spatialAudio, detailColor = HigColors.secondary)
            }
            HigSection(title = tr("Проверка")) {
                HigLink(tr("Проверить гироскоп Joy‑Con")) { start(GyroTestActivity::class.java) }
            }
            HigSection(
                title = "Hand tracking smoothing",
                footer = "0 responds fastest but may jitter. 100 is steadier but responds more slowly."
            ) {
                HigStepper("Smoothing", "$trackingSmoothness%") { step ->
                    trackingSmoothness = (trackingSmoothness + step * 5).coerceIn(0, 100)
                    val current = Settings.load(this@MainActivity)
                    Settings.save(this@MainActivity, current.copy(trackingSmoothness = trackingSmoothness))
                }
            }
            HigSection(
                title = "Second phone",
                footer = "Use a second phone as a cinema pointer. " +
                    "Open this screen on that phone; connect both to the same Wi-Fi network."
            ) {
                HigLink("Use this phone as a controller") { start(ControllerActivity::class.java) }
            }
            HigSection(title = tr("Магазин"), footer = tr("Игры и веб‑приложения берутся из вашего репозитория магазина на GitHub.")) {
                HigRow(tr("Магазин"), GameStore.REPOSITORY)
            }
            HigSection(
                title = "Lenses and display",
                footer = "If the image looks doubled, adjust the interpupillary distance first, " +
                    "then shift each half of the screen under the lenses. " +
                    "Changes take effect the next time you enter VR."
            ) {
                HigStepper("Interpupillary distance", "$ipd mm") { step ->
                    ipd = (ipd + step).coerceIn(Settings.MIN_IPD_MM, Settings.MAX_IPD_MM)
                    Settings.setIpdMm(this@MainActivity, ipd)
                }
                HigLink("Scan viewer QR code", value = "Google Cardboard") { scanCardboardProfile() }
                HigStepper("Lens offset", "$lensOffset mm") { step ->
                    lensOffset = (lensOffset + step).coerceIn(-Settings.MAX_LENS_MM, Settings.MAX_LENS_MM)
                    Settings.setLensOffsetMm(this@MainActivity, lensOffset)
                }
                HigSwitchRow("Panel follows gaze", panelFollows) {
                    panelFollows = it
                    Settings.setPanelFollows(this@MainActivity, it)
                }
            }
            HigSection(
                title = "Cinema screen",
                footer = "A wider virtual screen gives games more room to render. " +
                    "A curved screen keeps its edges at a more even distance. " +
                    "The keyboard window shows your desk while typing on a physical keyboard."
            ) {
                Settings.ScreenShape.entries.forEach { shape ->
                    HigChoice(tr(shape.title), tr(shape.detail), screenShape == shape) {
                        screenShape = shape
                        Settings.setScreenShape(this@MainActivity, shape)
                    }
                }
                HigSwitchRow("Curved screen", curvedScreen) {
                    curvedScreen = it
                    Settings.setCurvedScreen(this@MainActivity, it)
                }
                HigSwitchRow("Keyboard passthrough window", keyboardWindow) {
                    keyboardWindow = it
                    Settings.setKeyboardWindow(this@MainActivity, it)
                }
            }
            if (!BuildConfig.LITE) HigSection(
                title = "3D photos",
                footer = "The depth model estimates near and far areas in a photo. " +
                    "Latitude uses it to create a stereo image. The model downloads separately " +
                    "and stays in app storage. Videos and 360° panoramas do not need it."
            ) {
                when {
                    depthProgress != null -> HigRow(
                        "Depth model",
                        "Downloading: ${((depthProgress ?: 0f) * 100).roundToInt()}%",
                        trailing = { HigSpinner() }
                    )
                    depthInstalled -> HigRow("Depth model", "Ready", detailColor = HigColors.good)
                    else -> HigLink("Download depth model", value = "${DepthModel.MEGABYTES} MB") { downloadDepth() }
                }
                if (depthInstalled) HigLink("Remove depth model") {
                    DepthModel.close()
                    DepthModel.file(this@MainActivity).delete()
                    depthInstalled = false
                }
            }
            HigSection(
                title = "Refresh rate",
                footer = rates.let { list ->
                    if (list.isEmpty()) "This phone does not report its supported refresh rates."
                    else "Supported: " + list.joinToString(", ") { "$it Hz" } +
                        ". Higher rates feel smoother but use more battery."
                }
            ) {
                Settings.Refresh.entries
                    .filter { it == Settings.Refresh.AUTO || rates.isEmpty() || rates.any { hz -> hz >= it.hz } }
                    .forEach { option ->
                        HigChoice(tr(option.title), null, refresh == option) {
                            refresh = option
                            Settings.setRefresh(this@MainActivity, option)
                        }
                    }
            }
            HigSection(
                title = tr("Оформление"),
                footer = "Material You uses system wallpaper colors on Android 12 and later."
            ) {
                UiStyle.entries.forEach { style ->
                    HigChoice(style.title, style.detail, Ui.style == style) { Ui.set(this@MainActivity, style) }
                }
            }
            HigSection {
                HigLink(tr("Язык"), value = L10n.current.title) { languagePicker = true }
                HigLink(tr("Обновление ПО")) { start(UpdateActivity::class.java) }
                HigLink(tr("О приложении")) { start(AboutActivity::class.java) }
            }
        }
    }

    private fun start(screen: Class<*>) = startActivity(Intent(this, screen))

    // ---------------------------------------------------------------- Patching and installing

    @Composable
    private fun PatchDialog(game: GameLibrary.Game) {
        val about = if (game.kind == GameLibrary.Kind.VRAPI_ORIGINAL)
            "Это игра ${game.headset.title} на VrApi: без самой гарнитуры и драйвера Oculus она сразу закрывается. " +
                "PhoneXR заменит в ней libvrapi.so переходником на OpenXR"
        else "Это сборка ${game.headset.title}: на телефоне она не находит рантайм OpenXR и показывает чёрный экран. " +
            "PhoneXR откроет ей доступ к рантайму PhoneXR"
        HigAlert(
            title = "Пропатчить «${game.label}»?",
            message = about + ", впишет новый трекинг и оптимизирует сборку под телефон, " +
                "а потом подпишет своей подписью. " +
                "Оригинал придётся удалить (подпись другая), сохранения игры при этом пропадут.",
            actions = listOf(
                HigAction(tr("Отмена"), HigActionStyle.CANCEL) { pendingPatch = null },
                HigAction("Пропатчить") {
                    pendingPatch = null
                    patch(Uri.fromFile(game.apk), replaces = game)
                }
            ),
            onDismiss = { pendingPatch = null }
        )
    }

    @Composable
    private fun ReadyDialog(value: Ready) {
        // The same game, still installed with the store's signature: it goes first.
        val stale = value.replacesPackage?.takeIf { resumes >= 0 && isInstalled(it) }
        // Without the runtime the game starts into a black screen, whatever the patch did.
        val runtime = if (PhoneXrRuntime.state(this) == PhoneXrRuntime.State.READY) "" else
            "\n\nЧтобы вместо игры не был чёрный экран, установите PhoneXR Runtime в меню и выберите его " +
                "в OpenXR Runtime Broker."
        HigAlert(
            title = value.label?.let { "«$it» готова" }
                ?: if (value.result.vrApi) "Игра ${value.result.headset.title} готова" else "Сборка готова",
            message = value.result.changes.joinToString("\n") { "• $it" } + runtime +
                if (stale != null) "\n\nУстановлена версия с другой подписью: сначала удалите её, " +
                    "затем нажмите «Установить». Сохранения игры пропадут." else "",
            actions = listOf(
                HigAction(tr("Позже"), HigActionStyle.CANCEL) { ready = null },
                if (stale != null) HigAction("Удалить старую", HigActionStyle.DESTRUCTIVE) { uninstall(stale) }
                else HigAction("Установить") {
                    ready = null
                    install(value.result)
                }
            ),
            onDismiss = { ready = null }
        )
    }

    private fun open(game: GameLibrary.Game) {
        when (game.kind) {
            GameLibrary.Kind.VRAPI_ORIGINAL, GameLibrary.Kind.OPENXR_ORIGINAL -> pendingPatch = game
            GameLibrary.Kind.DAYDREAM -> {
                if (!Daydream.servicesInstalled(this) && Daydream.bundled(this)) {
                    status = "Сначала установите Opendream Services, затем снова откройте игру"
                    Daydream.installServices(this)
                } else {
                    requestStart(game)
                }
            }
            GameLibrary.Kind.VRAPI_UNSUPPORTED -> error =
                "«${game.label}» — игра ${game.headset.title}, которую PhoneXR запустить не может: " +
                    "у неё нет сборки для ARM (arm64-v8a или armeabi-v7a)."
            else -> requestStart(game)
        }
    }

    private var gameToStart: GameLibrary.Game? = null

    private fun requestStart(game: GameLibrary.Game) {
        gameToStart = game
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else {
            startAfterPermission = true
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTracking() {
        if (gameToStart?.let { GameLibrary.ownsHandTracking(this, it.packageName) } == true) {
            // The game tracks hands itself: PhoneXR declines and leaves the camera to it.
            stopService(Intent(this, HandTrackingService::class.java))
            status = "Руки отслеживает сама игра"
        } else {
            ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
            status = "Камера рук включена"
        }
        val game = gameToStart ?: return
        gameToStart = null
        val intent = GameLibrary.launchIntent(this, game)
        if (intent == null) {
            error = "У «${game.label}» нет экрана запуска"
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun patch(uri: Uri, replaces: GameLibrary.Game?, label: String? = replaces?.label) {
        if (replaces?.hasSplits == true) {
            error = "«${replaces.label}» установлена из нескольких APK (split). Такую игру установите из исходного файла."
            return
        }
        busy = "Подготовка…"
        Thread {
            try {
                val payload = PxrPackage.androidPayload(this, uri)
                val result = ApkPatcher.patch(this, payload)
                val conflict = signatureConflict(result.apk)
                runOnUiThread {
                    busy = null
                    ready = Ready(result, label, conflict)
                }
            } catch (failure: Throwable) {
                android.util.Log.e("PhoneXR-Patch", "APK preparation failed for $uri", failure)
                runOnUiThread {
                    busy = null
                    error = failure.localizedMessage ?: failure.javaClass.simpleName
                }
            }
        }.start()
    }

    /** Package name of [apk] when that package is installed with other signing keys, else null. */
    private fun signatureConflict(apk: File): String? {
        val archive = packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        val installed = runCatching {
            packageManager.getPackageInfo(archive.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return null
        fun signers(info: android.content.pm.PackageInfo) =
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        return archive.packageName.takeIf { signers(archive) != signers(installed) }
    }

    /** Installs the prepared game inside the PhoneXR flow, without opening the APK installer UI. */
    private fun installApk(file: File) {
        signatureConflict(file)?.let { conflict ->
            error = "Эта игра уже установлена с другой подписью ($conflict). Удалите её и скачайте снова."
            return
        }
        busy = "Установка внутри PhoneXR…"
        InternalInstaller.install(this, file) { problem ->
            busy = null
            if (problem != null) error = problem
            else {
                status = "Игра добавлена в PhoneXR"
                refreshGames()
            }
        }
    }

    private fun install(result: ApkPatcher.Result) {
        installApk(result.apk)
    }

    @Suppress("DEPRECATION")
    private fun uninstall(target: String) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$target")))
    }

    private fun isInstalled(target: String) =
        runCatching { packageManager.getApplicationInfo(target, 0) }.isSuccess

    /** A normal game played on the big VR screen (cinema) with its own scene. */
    private data class VrMode(
        val title: String, val game: String, val packageName: String,
        val subtitle: String, val scene: String, val sceneId: String,
    )

    companion object {
        private const val KEY_TAB = "tab"
        private const val MINECRAFT = "com.mojang.minecraftpe"
        private val VR_MODES = listOf(
            VrMode("Minecraft VR", "Minecraft", MINECRAFT, "Bedrock в гостиной с камином", "гостиная из Minecraft VR", CinemaActivity.SCENE_ROOM),
            VrMode("Roblox VR", "Roblox", "com.roblox.client", "Roblox на экране в VR", "небо вокруг экрана", CinemaActivity.SCENE_ROBLOX),
            VrMode("Brawl Stars VR", "Brawl Stars", "com.supercell.brawlstars", "Brawl Stars на экране в VR", "небо вокруг экрана", CinemaActivity.SCENE_BRAWL),
        )
        /** Height of the floating tab bar plus its margin. */
        private val TAB_BAR_ROOM = 120.dp
    }
}

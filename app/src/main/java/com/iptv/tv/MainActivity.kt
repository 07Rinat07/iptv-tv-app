package com.iptv.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.IptvTheme
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.AppStartDestination
import com.iptv.tv.feature.diagnostics.DiagnosticsScreen
import com.iptv.tv.feature.downloads.DownloadsScreen
import com.iptv.tv.feature.editor.EDITOR_PLAYLIST_ID_ARG
import com.iptv.tv.feature.editor.EditorScreen
import com.iptv.tv.feature.epg.EpgGuideScreen
import com.iptv.tv.feature.favorites.FavoritesScreen
import com.iptv.tv.feature.history.HistoryScreen
import com.iptv.tv.feature.home.HomeScreen
import com.iptv.tv.feature.home.ReadyPlaylistsScreen
import com.iptv.tv.feature.importer.ImportPrefill
import com.iptv.tv.feature.importer.ImportPrefillBus
import com.iptv.tv.feature.importer.ImporterScreen
import com.iptv.tv.feature.player.PLAYER_CHANNEL_ID_ARG
import com.iptv.tv.feature.player.PLAYER_PLAYLIST_ID_ARG
import com.iptv.tv.feature.player.PlayerScreen
import com.iptv.tv.feature.playlists.PlaylistsScreen
import com.iptv.tv.feature.scanner.ScannerScreen
import com.iptv.tv.feature.settings.NetworkTestScreen
import com.iptv.tv.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

const val TAG_ROUTE_LABEL = "top_route_label"
private const val TAG_NAV_PREFIX = "nav_button_"
private const val TAG_SIDEBAR_NAV_PREFIX = "sidebar_nav_button_"
const val TAG_SECTIONS_BUTTON = "sections_button"
const val TAG_SECTIONS_LIST = "sections_list"
const val TAG_APP_SIDEBAR = "app_sidebar"

fun navButtonTag(route: String): String = TAG_NAV_PREFIX + route

private data class AppSection(
    val route: String,
    val label: String,
    val compactLabel: String
)

private val APP_SECTIONS = listOf(
    AppSection(Routes.HOME, "Главная", "Г"),
    AppSection(Routes.SCANNER, "Сканер", "СК"),
    AppSection(Routes.IMPORTER, "Импорт", "И"),
    AppSection(Routes.READY_PLAYLISTS, "Готовые плейлисты", "ГП"),
    AppSection(Routes.PLAYLISTS, "Мои плейлисты", "ПЛ"),
    AppSection(Routes.EDITOR, "Редактор", "Р"),
    AppSection(Routes.FAVORITES, "Избранное", "★"),
    AppSection(Routes.HISTORY, "История", "ИС"),
    AppSection(Routes.EPG, "Телепрограмма", "EPG"),
    AppSection(Routes.PLAYER, "Плеер", "▶"),
    AppSection(Routes.DOWNLOADS, "Загрузки", "З"),
    AppSection(Routes.SETTINGS, "Настройки", "Н"),
    AppSection(Routes.NETWORK_TEST, "Сетевой тест", "СТ"),
    AppSection(Routes.DIAGNOSTICS, "Диагностика", "Д"),
    AppSection(Routes.ABOUT, "О приложении", "i")
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val pendingDeepLinkRoute = mutableStateOf<String?>(null)

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkRoute.value = intent.toAppRoute()
        enableEdgeToEdge()
        setContent {
            IptvTheme {
                AppRoot(pendingDeepLinkRoute, settingsRepository)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkRoute.value = intent.toAppRoute()
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
private fun AppRoot(
    pendingDeepLinkRoute: MutableState<String?>,
    settingsRepository: SettingsRepository
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route ?: Routes.HOME
    val activity = LocalContext.current as? ComponentActivity
    val configuration = LocalConfiguration.current
    val useSidebar = configuration.screenWidthDp >= 760
    val colorScheme = MaterialTheme.colorScheme
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }
    var showSectionsMenu by rememberSaveable { mutableStateOf(false) }
    var playerFullscreen by rememberSaveable { mutableStateOf(false) }
    var sidebarCollapsed by rememberSaveable(configuration.screenWidthDp) {
        mutableStateOf(configuration.screenWidthDp < 1120)
    }
    val configuredStartDestination by settingsRepository
        .observeAppStartDestination()
        .collectAsState(initial = null)
    var startupNavigationApplied by remember {
        mutableStateOf(pendingDeepLinkRoute.value != null)
    }

    fun navigateToSection(route: String) {
        if (isCurrentSection(currentRoute, route)) return
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(Routes.HOME) {
                saveState = true
            }
        }
    }

    BackHandler(enabled = showSectionsMenu) {
        showSectionsMenu = false
    }
    BackHandler(enabled = !showSectionsMenu && !playerFullscreen) {
        if (!navController.navigateUp()) {
            showExitConfirm = true
        }
    }

    LaunchedEffect(pendingDeepLinkRoute.value) {
        val route = pendingDeepLinkRoute.value ?: return@LaunchedEffect
        pendingDeepLinkRoute.value = null
        startupNavigationApplied = true
        navController.navigate(route) { launchSingleTop = true }
    }

    LaunchedEffect(configuredStartDestination) {
        val destination = configuredStartDestination ?: return@LaunchedEffect
        if (startupNavigationApplied) return@LaunchedEffect
        startupNavigationApplied = true
        val route = destination.toAppRoute()
        if (route != Routes.HOME) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    LaunchedEffect(currentRoute) {
        if (!currentRoute.startsWith(Routes.PLAYER)) {
            playerFullscreen = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { playerFullscreen = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colorScheme.background,
                        colorScheme.surface,
                        colorScheme.surfaceVariant
                    )
                )
            )
    ) {
        if (playerFullscreen) {
            AppNavHost(
                navController = navController,
                modifier = Modifier.fillMaxSize(),
                onFullscreenChanged = { playerFullscreen = it }
            )
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                if (useSidebar) {
                    AppSidebar(
                        currentRoute = currentRoute,
                        collapsed = sidebarCollapsed,
                        onToggleCollapsed = { sidebarCollapsed = !sidebarCollapsed },
                        onNavigate = ::navigateToSection,
                        onExit = { showExitConfirm = true }
                    )
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AppHeader(
                        currentRoute = currentRoute,
                        onBack = {
                            if (!navController.navigateUp()) {
                                showExitConfirm = true
                            }
                        },
                        onSections = { showSectionsMenu = true },
                        onExit = { showExitConfirm = true }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (configuration.screenWidthDp < 600) 6.dp else 12.dp)
                            .padding(top = 8.dp, bottom = 10.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 1480.dp),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.58f),
                            tonalElevation = 4.dp,
                            shape = MaterialTheme.shapes.large
                        ) {
                            AppNavHost(
                                navController = navController,
                                modifier = Modifier.fillMaxSize(),
                                onFullscreenChanged = { playerFullscreen = it }
                            )
                        }
                    }
                }
            }
        }

        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("Выход из приложения") },
                text = { Text("Закрыть приложение?") },
                confirmButton = {
                    Button(onClick = { activity?.finish() }) {
                        Text("Да, закрыть")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showExitConfirm = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        if (showSectionsMenu) {
            SectionsMenuDialog(
                currentRoute = currentRoute,
                onDismiss = { showSectionsMenu = false },
                onNavigate = { route ->
                    showSectionsMenu = false
                    navigateToSection(route)
                }
            )
        }
    }
}

@Composable
@kotlin.OptIn(ExperimentalLayoutApi::class)
private fun AppHeader(
    currentRoute: String,
    onBack: () -> Unit,
    onSections: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 760.dp
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppBrand(currentRoute = currentRoute)
                    NavControlButtons(
                        onBack = onBack,
                        onExit = onExit,
                        onSections = onSections
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppBrand(currentRoute = currentRoute, modifier = Modifier.weight(1f))
                    NavControlButtons(
                        onBack = onBack,
                        onExit = onExit,
                        onSections = onSections
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBrand(
    currentRoute: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.rinat_logo),
            contentDescription = "Rinat IPTV",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Rinat IPTV",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Text(
                text = "myscanerIPTV",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = "${routeTitle(currentRoute)} · ${routeKey(currentRoute)}",
                modifier = Modifier.testTag(TAG_ROUTE_LABEL),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
@kotlin.OptIn(ExperimentalLayoutApi::class)
private fun NavControlButtons(
    onBack: () -> Unit,
    onExit: () -> Unit,
    onSections: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.tvFocusOutline()) {
            Text("Назад")
        }
        OutlinedButton(
            onClick = onSections,
            modifier = Modifier.testTag(TAG_SECTIONS_BUTTON).tvFocusOutline()
        ) {
            Text("Разделы")
        }
        Button(onClick = onExit, modifier = Modifier.tvFocusOutline()) {
            Text("Выход")
        }
    }
}

@Composable
private fun AppSidebar(
    currentRoute: String,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onNavigate: (String) -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(if (collapsed) 88.dp else 248.dp)
            .fillMaxHeight()
            .testTag(TAG_APP_SIDEBAR),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!collapsed) {
                    Text("Разделы", style = MaterialTheme.typography.titleSmall)
                }
                OutlinedButton(onClick = onToggleCollapsed, modifier = Modifier.tvFocusOutline()) {
                    Text(if (collapsed) ">" else "<")
                }
            }
            TvScrollableLazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                scrollbarMinScreenWidthDp = 900
            ) {
                items(APP_SECTIONS.size) { index ->
                    val section = APP_SECTIONS[index]
                    val selected = isCurrentSection(currentRoute, section.route)
                    val label = if (collapsed) section.compactLabel else section.label
                    if (selected) {
                        Button(
                            onClick = { onNavigate(section.route) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TAG_SIDEBAR_NAV_PREFIX + section.route)
                                .tvFocusOutline()
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onNavigate(section.route) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TAG_SIDEBAR_NAV_PREFIX + section.route)
                                .tvFocusOutline()
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().tvFocusOutline()
            ) {
                Text(if (collapsed) "×" else "Выход")
            }
        }
    }
}

@Composable
private fun SectionsMenuDialog(
    currentRoute: String,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val dialogListMaxHeight = (configuration.screenHeightDp * 0.66f).dp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разделы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Выберите экран. Список прокручивается пультом, мышью и тачпадом.")
                TvScrollableLazyColumn(
                    modifier = Modifier
                        .heightIn(max = dialogListMaxHeight)
                        .testTag(TAG_SECTIONS_LIST),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(APP_SECTIONS.size) { index ->
                        val section = APP_SECTIONS[index]
                        val selected = isCurrentSection(currentRoute, section.route)
                        Button(
                            onClick = { onNavigate(section.route) },
                            enabled = !selected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(navButtonTag(section.route))
                                .tvFocusOutline()
                        ) {
                            Text(if (selected) "${section.label} (текущий)" else section.label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier,
    onFullscreenChanged: (Boolean) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenScanner = { navController.navigate(Routes.SCANNER) },
                onOpenImporter = { navController.navigate(Routes.IMPORTER) },
                onOpenReadyPlaylists = { navController.navigate(Routes.READY_PLAYLISTS) },
                onOpenPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                onOpenEpg = { navController.navigate(Routes.EPG) },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onPrimaryAction = null
            )
        }
        composable(Routes.SCANNER) {
            ScannerScreen(
                onPrimaryAction = { navController.navigate(Routes.PLAYLISTS) },
                onImportCandidate = { downloadUrl, playlistName ->
                    ImportPrefillBus.push(
                        ImportPrefill(
                            url = downloadUrl,
                            playlistName = playlistName,
                            autoImport = true
                        )
                    )
                    navController.navigate(Routes.IMPORTER)
                },
                primaryLabel = "Мои плейлисты"
            )
        }
        composable(Routes.IMPORTER) {
            ImporterScreen(
                onPrimaryAction = { navController.navigate(Routes.PLAYLISTS) },
                primaryLabel = "Сохранить"
            )
        }
        composable(Routes.READY_PLAYLISTS) {
            ReadyPlaylistsScreen(
                onImportPlaylist = { url, name ->
                    ImportPrefillBus.push(
                        ImportPrefill(
                            url = url,
                            playlistName = name,
                            autoImport = true
                        )
                    )
                    navController.navigate(Routes.IMPORTER)
                }
            )
        }
        composable(Routes.PLAYLISTS) {
            PlaylistsScreen(
                onOpenEditor = { playlistId -> navController.navigate(Routes.editorRoute(playlistId)) },
                onOpenPlayer = { playlistId -> navController.navigate(Routes.playerRoute(playlistId)) }
            )
        }
        composable(Routes.EDITOR) {
            EditorScreen(onPrimaryAction = { navController.navigate(Routes.PLAYLISTS) })
        }
        composable(
            route = Routes.EDITOR_WITH_ARG,
            arguments = listOf(navArgument(EDITOR_PLAYLIST_ID_ARG) { type = NavType.LongType })
        ) {
            EditorScreen(onPrimaryAction = { navController.navigate(Routes.PLAYLISTS) })
        }
        composable(Routes.FAVORITES) {
            FavoritesScreen(
                onOpenPlayer = { playlistId, channelId ->
                    navController.navigate(Routes.playerRoute(playlistId, channelId))
                }
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onOpenPlayer = { playlistId -> navController.navigate(Routes.playerRoute(playlistId)) }
            )
        }
        composable(Routes.EPG) {
            EpgGuideScreen(
                onOpenPlayer = { playlistId, channelId ->
                    navController.navigate(Routes.playerRoute(playlistId, channelId))
                },
                onOpenPlayerSettings = { navController.navigate(Routes.PLAYER) }
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(
                onPrimaryAction = { navController.navigate(Routes.SETTINGS) },
                primaryLabel = "Сменить плеер",
                onFullscreenChanged = onFullscreenChanged
            )
        }
        composable(
            route = Routes.PLAYER_WITH_ARG,
            arguments = listOf(navArgument(PLAYER_PLAYLIST_ID_ARG) { type = NavType.LongType })
        ) {
            PlayerScreen(
                onPrimaryAction = { navController.navigate(Routes.SETTINGS) },
                primaryLabel = "Сменить плеер",
                onFullscreenChanged = onFullscreenChanged
            )
        }
        composable(
            route = Routes.PLAYER_WITH_CHANNEL_ARG,
            arguments = listOf(
                navArgument(PLAYER_PLAYLIST_ID_ARG) { type = NavType.LongType },
                navArgument(PLAYER_CHANNEL_ID_ARG) { type = NavType.LongType }
            )
        ) {
            PlayerScreen(
                onPrimaryAction = { navController.navigate(Routes.SETTINGS) },
                primaryLabel = "Сменить плеер",
                onFullscreenChanged = onFullscreenChanged
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen()
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenNetworkTest = { navController.navigate(Routes.NETWORK_TEST) },
                onPrimaryAction = { navController.navigate(Routes.DIAGNOSTICS) },
                primaryLabel = "Диагностика"
            )
        }
        composable(Routes.NETWORK_TEST) {
            NetworkTestScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onPrimaryAction = { navController.navigate(Routes.SCANNER) },
                primaryLabel = "Открыть сканер"
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                onPrimaryAction = { navController.navigate(Routes.HOME) },
                primaryLabel = "На главную"
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
    }
}

private fun isCurrentSection(currentRoute: String, sectionRoute: String): Boolean = when (sectionRoute) {
    Routes.EDITOR -> currentRoute.startsWith(Routes.EDITOR)
    Routes.PLAYER -> currentRoute.startsWith(Routes.PLAYER)
    else -> currentRoute == sectionRoute
}

private fun routeKey(route: String): String = when {
    route.startsWith(Routes.EDITOR) -> Routes.EDITOR
    route.startsWith(Routes.PLAYER) -> Routes.PLAYER
    else -> route.substringBefore("/")
}

private fun routeTitle(route: String): String = when {
    route.startsWith(Routes.EDITOR) -> "Редактор"
    route.startsWith(Routes.PLAYER) -> "Плеер"
    else -> APP_SECTIONS.firstOrNull { it.route == route }?.label ?: route
}

private fun AppStartDestination.toAppRoute(): String = when (this) {
    AppStartDestination.HOME -> Routes.HOME
    AppStartDestination.PLAYER -> Routes.PLAYER
    AppStartDestination.SCANNER -> Routes.SCANNER
    AppStartDestination.FAVORITES -> Routes.FAVORITES
    AppStartDestination.PLAYLISTS -> Routes.PLAYLISTS
}

object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val IMPORTER = "importer"
    const val READY_PLAYLISTS = "ready_playlists"
    const val PLAYLISTS = "playlists"
    const val EDITOR = "editor"
    const val EDITOR_WITH_ARG = "editor/{playlistId}"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val EPG = "epg"
    const val PLAYER = "player"
    const val PLAYER_WITH_ARG = "player/{playlistId}"
    const val PLAYER_WITH_CHANNEL_ARG = "player/{playlistId}/{channelId}"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val NETWORK_TEST = "network_test"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"

    fun editorRoute(playlistId: Long): String = "editor/$playlistId"
    fun playerRoute(playlistId: Long): String = "player/$playlistId"
    fun playerRoute(playlistId: Long, channelId: Long): String = "player/$playlistId/$channelId"
}

private fun Intent?.toAppRoute(): String? {
    val uri = this?.data ?: return null
    if (uri.scheme != "myscaneriptv") return null
    return when (uri.host) {
        "player" -> {
            val playlistId = uri.pathSegments.getOrNull(0)?.toLongOrNull()
            val channelId = uri.pathSegments.getOrNull(1)?.toLongOrNull()
            when {
                playlistId != null && channelId != null -> Routes.playerRoute(playlistId, channelId)
                playlistId != null -> Routes.playerRoute(playlistId)
                else -> Routes.PLAYER
            }
        }
        "downloads" -> Routes.DOWNLOADS
        "epg" -> Routes.EPG
        "favorites" -> Routes.FAVORITES
        "history" -> Routes.HISTORY
        "about" -> Routes.ABOUT
        else -> Routes.HOME
    }
}

package com.iptv.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.media3.common.util.UnstableApi
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.designsystem.theme.IptvTheme
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.AppStartDestination
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.feature.editor.EDITOR_PLAYLIST_ID_ARG
import com.iptv.tv.feature.diagnostics.DiagnosticsScreen
import com.iptv.tv.feature.downloads.DownloadsScreen
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
import com.iptv.tv.feature.player.StablePlayerScreen
import com.iptv.tv.feature.playlists.PlaylistsScreen
import com.iptv.tv.feature.scanner.ScannerScreen
import com.iptv.tv.feature.settings.NetworkTestScreen
import com.iptv.tv.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

const val TAG_ROUTE_LABEL = "top_route_label"
private const val TAG_NAV_PREFIX = "nav_button_"
const val TAG_SECTIONS_BUTTON = "sections_button"
const val TAG_SECTIONS_LIST = "sections_list"

fun navButtonTag(route: String): String = TAG_NAV_PREFIX + route

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
private fun AppRoot(
    pendingDeepLinkRoute: MutableState<String?>,
    settingsRepository: SettingsRepository
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route ?: Routes.HOME
    val activity = LocalContext.current as? ComponentActivity
    val colorScheme = MaterialTheme.colorScheme
    val isPlayerRoute = currentRoute.startsWith(Routes.PLAYER)
    var showExitConfirm by remember { mutableStateOf(false) }
    var showSectionsMenu by remember { mutableStateOf(false) }
    var playerFullscreen by remember { mutableStateOf(false) }
    val configuredStartDestination by settingsRepository
        .observeAppStartDestination()
        .collectAsState(initial = null)
    var startupNavigationApplied by remember {
        mutableStateOf(pendingDeepLinkRoute.value != null)
    }

    BackHandler {
        if (!navController.navigateUp()) {
            showExitConfirm = true
        }
    }

    LaunchedEffect(pendingDeepLinkRoute.value) {
        val route = pendingDeepLinkRoute.value ?: return@LaunchedEffect
        pendingDeepLinkRoute.value = null
        startupNavigationApplied = true
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    LaunchedEffect(configuredStartDestination) {
        val destination = configuredStartDestination ?: return@LaunchedEffect
        if (startupNavigationApplied) return@LaunchedEffect
        startupNavigationApplied = true
        val route = destination.toAppRoute()
        if (route != Routes.HOME) {
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(currentRoute) {
        if (!currentRoute.startsWith(Routes.PLAYER)) {
            playerFullscreen = false
        }
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
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (!playerFullscreen && !isPlayerRoute) {
                    Surface(
                        color = colorScheme.surface.copy(alpha = 0.92f),
                        tonalElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.rinat_logo),
                                contentDescription = "Rinat",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .padding(end = 10.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Rinat IPTV",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Раздел: ${routeTitle(currentRoute)}",
                                    modifier = Modifier.testTag(TAG_ROUTE_LABEL),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            NavControlButtons(
                                onBack = {
                                    if (activity != null) {
                                        activity.onBackPressedDispatcher.onBackPressed()
                                    } else if (!navController.navigateUp()) {
                                        showExitConfirm = true
                                    }
                                },
                                onExit = { showExitConfirm = true },
                                onSections = { showSectionsMenu = true },
                                sectionsMenuVisible = showSectionsMenu
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = if (playerFullscreen || isPlayerRoute) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                },
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = if (playerFullscreen || isPlayerRoute) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxSize()
                            .widthIn(max = 1360.dp)
                    },
                    color = if (playerFullscreen || isPlayerRoute) {
                        Color.Transparent
                    } else {
                        colorScheme.surfaceVariant.copy(alpha = 0.58f)
                    },
                    tonalElevation = if (playerFullscreen || isPlayerRoute) 0.dp else 4.dp
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                onOpenScanner = { navController.navigate(Routes.SCANNER) },
                                onOpenImporter = { navController.navigate(Routes.IMPORTER) },
                                onOpenReadyPlaylists = { navController.navigate(Routes.READY_PLAYLISTS) },
                                onOpenPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                                onOpenPlaylist = { playlistId -> navController.navigate(Routes.playerRoute(playlistId)) },
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
                                            autoImport = true,
                                            catalogOrigin = CatalogOriginKind.SCANNER_IMPORT
                                        )
                                    )
                                    navController.navigate(Routes.IMPORTER)
                                },
                                primaryLabel = "Мои плейлисты"
                            )
                        }
                        composable(Routes.IMPORTER) {
                            ImporterScreen(onPrimaryAction = { navController.navigate(Routes.PLAYLISTS) }, primaryLabel = "Сохранить")
                        }
                        composable(Routes.READY_PLAYLISTS) {
                            ReadyPlaylistsScreen(
                                onOpenPlaylist = { playlistId ->
                                    navController.navigate(Routes.playerRoute(playlistId))
                                }
                            )
                        }
                        composable(Routes.PLAYLISTS) {
                            PlaylistsScreen(
                                onOpenEditor = { playlistId -> navController.navigate(Routes.editorRoute(playlistId)) },
                                onOpenPlayer = { playlistId, channelId ->
                                    if (channelId != null) {
                                        navController.navigate(Routes.playerRoute(playlistId, channelId))
                                    } else {
                                        navController.navigate(Routes.playerRoute(playlistId))
                                    }
                                }
                            )
                        }
                        composable(Routes.EDITOR) {
                            EditorScreen(onPrimaryAction = { _ -> navController.navigate(Routes.PLAYLISTS) })
                        }
                        composable(
                            route = Routes.EDITOR_WITH_ARG,
                            arguments = listOf(
                                navArgument(EDITOR_PLAYLIST_ID_ARG) { type = NavType.LongType }
                            )
                        ) {
                            EditorScreen(onPrimaryAction = { _ -> navController.navigate(Routes.PLAYLISTS) })
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
                                onOpenPlayer = { playlistId ->
                                    navController.navigate(Routes.playerRoute(playlistId))
                                }
                            )
                        }
                        composable(Routes.EPG) {
                            EpgGuideScreen(
                                onOpenPlayer = { playlistId, channelId ->
                                    navController.navigate(Routes.playerRoute(playlistId, channelId))
                                },
                                onOpenPlayerSettings = {
                                    navController.navigate(Routes.PLAYER)
                                }
                            )
                        }
                        composable(Routes.PLAYER) {
                            StablePlayerScreen(
                                onPrimaryAction = { navController.navigate(Routes.SETTINGS) },
                                primaryLabel = "Сменить плеер",
                                onBack = {
                                    if (!navController.navigateUp()) showExitConfirm = true
                                },
                                onFullscreenChanged = { playerFullscreen = it }
                            )
                        }
                        composable(
                            route = Routes.PLAYER_WITH_ARG,
                            arguments = listOf(
                                navArgument(PLAYER_PLAYLIST_ID_ARG) { type = NavType.LongType }
                            )
                        ) {
                            StablePlayerScreen(
                                onPrimaryAction = { navController.navigate(Routes.SETTINGS) },
                                primaryLabel = "Сменить плеер",
                                onBack = {
                                    if (!navController.navigateUp()) showExitConfirm = true
                                },
                                onFullscreenChanged = { playerFullscreen = it }
                            )
                        }
                        composable(
                            route = Routes.PLAYER_WITH_CHANNEL_ARG,
                            arguments = listOf(
                                navArgument(PLAYER_PLAYLIST_ID_ARG) { type = NavType.LongType },
                                navArgument(PLAYER_CHANNEL_ID_ARG) { type = NavType.LongType }
                            )
                        ) {
                            StablePlayerScreen(
                                onPrimaryAction = { navController.navigate(Routes.SETTINGS) },
                                primaryLabel = "Сменить плеер",
                                onBack = {
                                    if (!navController.navigateUp()) showExitConfirm = true
                                },
                                onFullscreenChanged = { playerFullscreen = it }
                            )
                        }
                        composable(Routes.DOWNLOADS) {
                            DownloadsScreen()
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                onOpenNetworkTest = { navController.navigate(Routes.NETWORK_TEST) },
                                onOpenStartDestination = { destination ->
                                    navController.navigate(destination.toAppRoute()) {
                                        launchSingleTop = true
                                    }
                                },
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
                            DiagnosticsScreen(onPrimaryAction = { navController.navigate(Routes.HOME) }, primaryLabel = "На главную")
                        }
                        composable(Routes.ABOUT) {
                            AboutScreen(
                                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                            )
                        }
                    }
                }
            }
        }
            if (showExitConfirm) {
                val exitCancelFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    exitCancelFocusRequester.requestFocus()
                }
                AlertDialog(
                    onDismissRequest = { showExitConfirm = false },
                    title = { Text("Выход из приложения") },
                    text = { Text("Закрыть приложение?") },
                    confirmButton = {
                        Button(
                            onClick = { activity?.finish() },
                            modifier = Modifier.tvFocusOutline()
                        ) {
                            Text("Да, закрыть")
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { showExitConfirm = false },
                            modifier = Modifier
                                .focusRequester(exitCancelFocusRequester)
                                .tvFocusOutline()
                        ) {
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
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }

@Composable
private fun NavControlButtons(
    onBack: () -> Unit,
    onExit: () -> Unit,
    onSections: () -> Unit,
    sectionsMenuVisible: Boolean
) {
    val sectionsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(sectionsMenuVisible) {
        if (!sectionsMenuVisible) {
            sectionsFocusRequester.requestFocus()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onBack) { Text("Назад") }
        OutlinedButton(
            onClick = onSections,
            modifier = Modifier
                .testTag(TAG_SECTIONS_BUTTON)
                .focusRequester(sectionsFocusRequester)
        ) { Text("Разделы") }
        Button(onClick = onExit) { Text("Выход") }
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
    val routes = listOf(
        Routes.HOME to "Главная",
        Routes.SCANNER to "Сканер",
        Routes.IMPORTER to "Импорт",
        Routes.READY_PLAYLISTS to "Готовые плейлисты",
        Routes.PLAYLISTS to "Плейлисты",
        Routes.EDITOR to "Редактор",
        Routes.FAVORITES to "Избранное",
        Routes.HISTORY to "История",
        Routes.EPG to "Телепрограмма",
        Routes.PLAYER to "Плеер",
        Routes.DOWNLOADS to "Загрузки",
        Routes.SETTINGS to "Настройки",
        Routes.NETWORK_TEST to "Сетевой тест",
        Routes.DIAGNOSTICS to "Логи",
        Routes.ABOUT to "О приложении"
    )
    val firstSelectableIndex = routes.indexOfFirst { (route, _) -> route != currentRoute }
    val firstSelectableFocusRequester = remember { FocusRequester() }
    LaunchedEffect(currentRoute) {
        firstSelectableFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разделы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Выберите экран. Список прокручивается вниз/вверх.")
                TvScrollableLazyColumn(
                    modifier = Modifier
                        .heightIn(max = dialogListMaxHeight)
                        .testTag(TAG_SECTIONS_LIST),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(routes.size) { index ->
                        val (route, label) = routes[index]
                        Button(
                            onClick = { onNavigate(route) },
                            enabled = route != currentRoute,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (index == firstSelectableIndex) {
                                        Modifier.focusRequester(firstSelectableFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                                .testTag(navButtonTag(route))
                                .tvFocusOutline()
                        ) {
                            val suffix = if (route == currentRoute) " (текущий)" else ""
                            Text("$label$suffix")
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.tvFocusOutline()) { Text("Закрыть") }
        }
    )
}

private fun routeTitle(route: String): String = when {
    route.startsWith("editor") -> "Редактор"
    route.startsWith("player") -> "Плеер"
    else -> when (route) {
        Routes.HOME -> "Главная"
        Routes.SCANNER -> "Сканер"
        Routes.IMPORTER -> "Импорт"
        Routes.READY_PLAYLISTS -> "Готовые плейлисты"
        Routes.PLAYLISTS -> "Мои плейлисты"
        Routes.FAVORITES -> "Избранное"
        Routes.HISTORY -> "История"
        Routes.EPG -> "Телепрограмма"
        Routes.DOWNLOADS -> "Загрузки"
        Routes.SETTINGS -> "Настройки"
        Routes.NETWORK_TEST -> "Сетевой тест"
        Routes.DIAGNOSTICS -> "Диагностика"
        Routes.ABOUT -> "О приложении"
        else -> route
    }
}

private fun AppStartDestination.toAppRoute(): String {
    return when (this) {
        AppStartDestination.HOME -> Routes.HOME
        AppStartDestination.PLAYER -> Routes.PLAYER
        AppStartDestination.SCANNER -> Routes.SCANNER
        AppStartDestination.FAVORITES -> Routes.FAVORITES
        AppStartDestination.PLAYLISTS -> Routes.PLAYLISTS
    }
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

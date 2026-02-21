package com.iptv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.iptv.tv.core.designsystem.theme.IptvTheme
import com.iptv.tv.feature.editor.EDITOR_PLAYLIST_ID_ARG
import com.iptv.tv.feature.diagnostics.DiagnosticsScreen
import com.iptv.tv.feature.downloads.DownloadsScreen
import com.iptv.tv.feature.editor.EditorScreen
import com.iptv.tv.feature.favorites.FavoritesScreen
import com.iptv.tv.feature.history.HistoryScreen
import com.iptv.tv.feature.home.HomeScreen
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

const val TAG_ROUTE_LABEL = "top_route_label"
private const val TAG_NAV_PREFIX = "nav_button_"
const val TAG_SECTIONS_BUTTON = "sections_button"
const val TAG_SECTIONS_LIST = "sections_list"

fun navButtonTag(route: String): String = TAG_NAV_PREFIX + route

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IptvTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route ?: Routes.HOME
    val activity = LocalContext.current as? ComponentActivity
    val colorScheme = MaterialTheme.colorScheme
    var showExitConfirm by remember { mutableStateOf(false) }
    var showSectionsMenu by remember { mutableStateOf(false) }

    BackHandler {
        if (!navController.navigateUp()) {
            showExitConfirm = true
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
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "myscanerIPTV",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Раздел: ${routeTitle(currentRoute)}",
                                modifier = Modifier.testTag(TAG_ROUTE_LABEL),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = routeControlHint(currentRoute),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        NavControlButtons(
                            onBack = {
                                if (!navController.navigateUp()) {
                                    showExitConfirm = true
                                }
                            },
                            onExit = { showExitConfirm = true },
                            onSections = { showSectionsMenu = true }
                        )
                    }
                }
            }
        ) { paddingValues ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1360.dp),
                    color = colorScheme.surfaceVariant.copy(alpha = 0.58f),
                    tonalElevation = 4.dp
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
                                onOpenPlaylists = { navController.navigate(Routes.PLAYLISTS) },
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
                            ImporterScreen(onPrimaryAction = { navController.navigate(Routes.PLAYLISTS) }, primaryLabel = "Сохранить")
                        }
                        composable(Routes.PLAYLISTS) {
                            PlaylistsScreen(
                                onOpenEditor = { playlistId -> navController.navigate(Routes.editorRoute(playlistId)) },
                                onOpenPlayer = { playlistId -> navController.navigate(Routes.playerRoute(playlistId)) }
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
                        composable(Routes.PLAYER) {
                            PlayerScreen(onPrimaryAction = { navController.navigate(Routes.SETTINGS) }, primaryLabel = "Сменить плеер")
                        }
                        composable(
                            route = Routes.PLAYER_WITH_ARG,
                            arguments = listOf(
                                navArgument(PLAYER_PLAYLIST_ID_ARG) { type = NavType.LongType }
                            )
                        ) {
                            PlayerScreen(onPrimaryAction = { navController.navigate(Routes.SETTINGS) }, primaryLabel = "Сменить плеер")
                        }
                        composable(
                            route = Routes.PLAYER_WITH_CHANNEL_ARG,
                            arguments = listOf(
                                navArgument(PLAYER_PLAYLIST_ID_ARG) { type = NavType.LongType },
                                navArgument(PLAYER_CHANNEL_ID_ARG) { type = NavType.LongType }
                            )
                        ) {
                            PlayerScreen(onPrimaryAction = { navController.navigate(Routes.SETTINGS) }, primaryLabel = "Сменить плеер")
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
                            DiagnosticsScreen(onPrimaryAction = { navController.navigate(Routes.HOME) }, primaryLabel = "На главную")
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
                        Button(onClick = { showExitConfirm = false }) {
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
    onSections: () -> Unit
) {
    val sectionsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        sectionsFocusRequester.requestFocus()
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
        Routes.PLAYLISTS to "Плейлисты",
        Routes.EDITOR to "Редактор",
        Routes.FAVORITES to "Избранное",
        Routes.HISTORY to "История",
        Routes.PLAYER to "Плеер",
        Routes.DOWNLOADS to "Загрузки",
        Routes.SETTINGS to "Настройки",
        Routes.NETWORK_TEST to "Сетевой тест",
        Routes.DIAGNOSTICS to "Логи"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разделы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Выберите экран. Список прокручивается вниз/вверх.")
                LazyColumn(
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
                                .testTag(navButtonTag(route))
                        ) {
                            val suffix = if (route == currentRoute) " (текущий)" else ""
                            Text("$label$suffix")
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Закрыть") }
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
        Routes.PLAYLISTS -> "Мои плейлисты"
        Routes.FAVORITES -> "Избранное"
        Routes.HISTORY -> "История"
        Routes.DOWNLOADS -> "Загрузки"
        Routes.SETTINGS -> "Настройки"
        Routes.NETWORK_TEST -> "Сетевой тест"
        Routes.DIAGNOSTICS -> "Диагностика"
        else -> route
    }
}

private fun routeControlHint(route: String): String = when {
    route.startsWith("player") ->
        "Пульт: стрелки + OK, BACK. Мышь: двойной клик по видео = развернуть/свернуть."
    route.startsWith("scanner") ->
        "Пульт: стрелки + OK для кнопок. Мышь: клик по карточкам и кнопкам."
    else ->
        "Управление: пульт (D-pad + OK + BACK) и мышь."
}

object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val IMPORTER = "importer"
    const val PLAYLISTS = "playlists"
    const val EDITOR = "editor"
    const val EDITOR_WITH_ARG = "editor/{playlistId}"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val PLAYER = "player"
    const val PLAYER_WITH_ARG = "player/{playlistId}"
    const val PLAYER_WITH_CHANNEL_ARG = "player/{playlistId}/{channelId}"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val NETWORK_TEST = "network_test"
    const val DIAGNOSTICS = "diagnostics"

    fun editorRoute(playlistId: Long): String = "editor/$playlistId"
    fun playerRoute(playlistId: Long): String = "player/$playlistId"
    fun playerRoute(playlistId: Long, channelId: Long): String = "player/$playlistId/$channelId"
}

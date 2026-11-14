package com.yomu.reader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yomu.reader.data.AppRelease
import com.yomu.reader.data.AppUpdater
import com.yomu.reader.data.Library
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.splitKey
import kotlinx.coroutines.CancellationException

private val YomuColors = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF2B0E00),
    primaryContainer = Color(0xFF5A2A18),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF2A2A2A),
)

private val AmoledColors = YomuColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainerHigh = Color(0xFF141414),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC2471B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFFB26A00),
)

@Composable
fun YomuTheme(content: @Composable () -> Unit) {
    val theme by Prefs.theme.collectAsState()
    val light = theme == "light"
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? android.app.Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = when (theme) {
            "light" -> LightColors
            "amoled" -> AmoledColors
            else -> YomuColors
        },
        content = content,
    )
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private fun mangaRoute(sourceId: String, mangaId: String) =
    "manga/${Uri.encode(sourceId)}/${Uri.encode(mangaId)}"

private fun readerRoute(sourceId: String, mangaId: String, chapterId: String) =
    "reader/${Uri.encode(sourceId)}/${Uri.encode(mangaId)}/${Uri.encode(chapterId)}"

private fun NavHostController.openManga(key: String) {
    val (s, m) = splitKey(key)
    navigate(mangaRoute(s, m))
}

private fun NavHostController.openReader(key: String, chapterId: String) {
    val (s, m) = splitKey(key)
    navigate(readerRoute(s, m, chapterId))
}

@Composable
fun YomuRoot(startTab: String? = null) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val updates by Library.updates.collectAsState()
    val progressMap by Library.progress.collectAsState()
    val hasNewUpdates = remember(updates, progressMap) {
        updates.any { u -> u.chapterId !in (progressMap[u.key]?.read ?: emptySet()) }
    }
    val tabs = remember {
        listOf(
            Tab("library", "Biblio", Icons.Default.Favorite),
            Tab("updates", "Nouveautés", Icons.Default.Notifications),
            Tab("history", "Historique", YomuIcons.History),
            Tab("sources", "Explorer", Icons.Default.Search),
            Tab("more", "Plus", Icons.Default.MoreVert),
        )
    }

    // Autorisation des notifications (Android 13+).
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Vérification de mise à jour de l'appli (au plus toutes les 6 h).
    var release by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastAppUpdateCheck > 6 * 3600_000L) {
            Prefs.lastAppUpdateCheck = now
            try {
                release = AppUpdater.check()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            }
        }
    }
    release?.let { AppUpdateDialog(it, onDismiss = { release = null }) }

    val start = tabs.firstOrNull { it.route == startTab }?.route ?: "library"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                if (route != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                if (tab.route == "updates" && hasNewUpdates) {
                                    BadgedBox(badge = { Badge() }) { Icon(tab.icon, contentDescription = null) }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(padding),
        ) {
            composable("library") {
                LibraryScreen(
                    onOpen = { m -> nav.navigate(mangaRoute(m.sourceId, m.id)) },
                    onRead = { key, chapterId -> nav.openReader(key, chapterId) },
                    onSearch = { q -> nav.navigate("search/${Uri.encode(q)}") },
                )
            }
            composable("updates") {
                UpdatesScreen(
                    onOpenManga = { key -> nav.openManga(key) },
                    onRead = { key, chapterId -> nav.openReader(key, chapterId) },
                )
            }
            composable("history") {
                HistoryScreen(
                    onOpenManga = { key -> nav.openManga(key) },
                    onRead = { key, chapterId -> nav.openReader(key, chapterId) },
                )
            }
            composable("sources") {
                SourcesScreen(
                    onOpen = { s -> nav.navigate("browse/${Uri.encode(s.id)}") },
                    onGlobalSearch = { q -> nav.navigate("search/${Uri.encode(q)}") },
                    onOpenManga = { m -> nav.navigate(mangaRoute(m.sourceId, m.id)) },
                )
            }
            composable("search/{query}") { entry ->
                GlobalSearchScreen(
                    query = entry.arguments?.getString("query").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpen = { m -> nav.navigate(mangaRoute(m.sourceId, m.id)) },
                )
            }
            composable("more") {
                MoreScreen(onStats = { nav.navigate("stats") })
            }
            composable("stats") {
                StatsScreen(onBack = { nav.popBackStack() })
            }
            composable("browse/{sourceId}") { entry ->
                BrowseScreen(
                    sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpen = { m -> nav.navigate(mangaRoute(m.sourceId, m.id)) },
                )
            }
            composable("manga/{sourceId}/{mangaId}") { entry ->
                val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                val mangaId = entry.arguments?.getString("mangaId").orEmpty()
                DetailsScreen(
                    sourceId = sourceId,
                    mangaId = mangaId,
                    onBack = { nav.popBackStack() },
                    onRead = { chapterId -> nav.navigate(readerRoute(sourceId, mangaId, chapterId)) },
                    onOpenManga = { key ->
                        nav.popBackStack()
                        nav.openManga(key)
                    },
                )
            }
            composable("reader/{sourceId}/{mangaId}/{chapterId}") { entry ->
                ReaderScreen(
                    sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                    mangaId = entry.arguments?.getString("mangaId").orEmpty(),
                    initialChapterId = entry.arguments?.getString("chapterId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

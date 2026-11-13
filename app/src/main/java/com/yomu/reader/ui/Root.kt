package com.yomu.reader.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

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

@Composable
fun YomuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = YomuColors, content = content)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private fun mangaRoute(sourceId: String, mangaId: String) =
    "manga/${Uri.encode(sourceId)}/${Uri.encode(mangaId)}"

@Composable
fun YomuRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = remember {
        listOf(
            Tab("library", "Bibliothèque", Icons.Default.Favorite),
            Tab("sources", "Explorer", Icons.Default.Search),
            Tab("settings", "Réglages", Icons.Default.Settings),
        )
    }

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
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "library",
            modifier = Modifier.padding(padding),
        ) {
            composable("library") {
                LibraryScreen(onOpen = { m -> nav.navigate(mangaRoute(m.sourceId, m.id)) })
            }
            composable("sources") {
                SourcesScreen(onOpen = { s -> nav.navigate("browse/${Uri.encode(s.id)}") })
            }
            composable("settings") {
                SettingsScreen()
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
                    onRead = { chapterId ->
                        nav.navigate(
                            "reader/${Uri.encode(sourceId)}/${Uri.encode(mangaId)}/${Uri.encode(chapterId)}"
                        )
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

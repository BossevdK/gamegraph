package nl.gamegraph.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import nl.gamegraph.app.AppViewModel
import nl.gamegraph.app.ui.screens.DetailScreen
import nl.gamegraph.app.ui.screens.DnaScreen
import nl.gamegraph.app.ui.screens.GraphScreen
import nl.gamegraph.app.ui.screens.HomeScreen
import nl.gamegraph.app.ui.screens.LibraryScreen
import nl.gamegraph.app.ui.screens.NextGameScreen
import nl.gamegraph.app.ui.screens.SettingsScreen

private data class TopDestination(val route: String, val label: String, val icon: ImageVector)

private val topDestinations = listOf(
    TopDestination("home", "Home", Icons.Filled.Home),
    TopDestination("graph", "Graph", Icons.Filled.BubbleChart),
    TopDestination("next", "NextGame", Icons.Filled.AutoAwesome),
    TopDestination("library", "Library", Icons.Filled.VideogameAsset),
    TopDestination("dna", "Profiel", Icons.Filled.Person),
)

@Composable
fun AppNav(viewModel: AppViewModel, startRoute: String, recovered: Boolean) {
    val nav = rememberNavController()
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(Unit) {
        if (recovered) snackbar.showSnackbar("GameGraph is hersteld na een onverwachte fout. Je gegevens zijn behouden.")
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    // Als de app 20 seconden stabiel draait, vergeten we eerdere crashes (anders blijft "Opnieuw proberen" onnodig geblokkeerd).
    LaunchedEffect(Unit) {
        delay(20_000)
        viewModel.recovery.markStable()
    }
    LaunchedEffect(startRoute) {
        if (startRoute.startsWith("detail/")) nav.navigate(startRoute)
    }
    // Onthoud de laatste veilige plek voor herstel na een crash.
    LaunchedEffect(backStack) {
        val entry = backStack ?: return@LaunchedEffect
        val route = entry.destination.route ?: return@LaunchedEffect
        when {
            route == "detail/{id}" -> entry.arguments?.getString("id")?.let { viewModel.recovery.lastSafeRoute = "detail/$it" }
            else -> viewModel.recovery.lastSafeRoute = route
        }
    }

    val showBar = currentRoute in topDestinations.map { it.route }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) NavigationBar {
                topDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        val start = if (startRoute in topDestinations.map { it.route } || startRoute == "settings") startRoute else "home"
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(state, viewModel,
                    onOpenGame = { nav.navigate("detail/$it") },
                    onNavigate = { r -> nav.navigate(r) { launchSingleTop = true } },
                    onSettings = { nav.navigate("settings") })
            }
            composable("graph") { GraphScreen(state, onOpenGame = { nav.navigate("detail/$it") }) }
            composable("next") { NextGameScreen(state, viewModel, onOpenGame = { nav.navigate("detail/$it") }) }
            composable("library") { LibraryScreen(state, viewModel, onOpenGame = { nav.navigate("detail/$it") }) }
            composable("dna") { DnaScreen(state, viewModel, onSettings = { nav.navigate("settings") }) }
            composable("settings") { SettingsScreen(state, viewModel, onBack = { nav.popBackStack() }) }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                DetailScreen(id, state, viewModel, onBack = { nav.popBackStack() }, onOpenGame = { nav.navigate("detail/$it") })
            }
        }
    }
}

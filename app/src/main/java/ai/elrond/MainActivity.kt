package ai.elrond

import ai.elrond.data.SettingsRepository
import ai.elrond.domain.AiColorMode
import ai.elrond.domain.AiLoaderStyle
import ai.elrond.domain.AppAccent
import ai.elrond.ui.LibraryScreen
import ai.elrond.ui.LocalAiColorMode
import ai.elrond.ui.LocalAiLoaderStyle
import ai.elrond.ui.NoteCanvasScreen
import ai.elrond.ui.SettingsScreen
import ai.elrond.ui.theme.ElrondTheme
import androidx.compose.runtime.CompositionLocalProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Drives the user-selectable [AppAccent] (FA-14) that recolours the whole app. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val accent by settingsRepository.appAccent
                .collectAsStateWithLifecycle(initialValue = AppAccent.DEFAULT)
            // FA-17: the AI-mark appearance is provided once here (like the accent) so the AI logo
            // and loader render consistently across screens without per-ViewModel plumbing.
            val aiColorMode by settingsRepository.aiColorMode
                .collectAsStateWithLifecycle(initialValue = AiColorMode.DEFAULT)
            val aiLoaderStyle by settingsRepository.aiLoaderStyle
                .collectAsStateWithLifecycle(initialValue = AiLoaderStyle.DEFAULT)
            ElrondTheme(accent = accent) {
                CompositionLocalProvider(
                    LocalAiColorMode provides aiColorMode,
                    LocalAiLoaderStyle provides aiLoaderStyle,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ElrondNavHost()
                    }
                }
            }
        }
    }
}

@Composable
private fun ElrondNavHost() {
    val navController = rememberNavController()

    // ViewModels are provided by Hilt (hiltViewModel()) inside each screen.
    NavHost(navController = navController, startDestination = ROUTE_NOTES) {
        composable(ROUTE_NOTES) {
            LibraryScreen(
                onOpenNote = { pageId -> navController.navigate("note/$pageId") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(
            route = ROUTE_NOTE,
            arguments = listOf(navArgument("pageId") { type = NavType.StringType }),
        ) { entry ->
            val pageId = entry.arguments?.getString("pageId") ?: return@composable
            NoteCanvasScreen(
                pageId = pageId,
                // The editor close (✕) is a Home button: pop the whole note stack back to the library,
                // not one step — so note→note→note hops still land on the library home.
                onHome = {
                    navController.popBackStack(route = ROUTE_NOTES, inclusive = false)
                },
                onOpenNote = { id -> navController.navigate("note/$id") },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

private const val ROUTE_NOTES = "notes"
private const val ROUTE_NOTE = "note/{pageId}"
private const val ROUTE_SETTINGS = "settings"

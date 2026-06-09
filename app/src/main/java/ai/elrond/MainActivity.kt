package ai.elrond

import ai.elrond.ui.HomeScreen
import ai.elrond.ui.NoteCanvasScreen
import ai.elrond.ui.SettingsScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ElrondNavHost()
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
            HomeScreen(
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
                onBack = { navController.popBackStack() },
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

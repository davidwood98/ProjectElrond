package ai.elrond

import ai.elrond.notes.noteListViewModelFactory
import ai.elrond.ui.NoteCanvasScreen
import ai.elrond.ui.NoteListScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

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
    val app = LocalContext.current.applicationContext as ElrondApplication

    NavHost(navController = navController, startDestination = ROUTE_NOTES) {
        composable(ROUTE_NOTES) {
            NoteListScreen(
                viewModel = viewModel(factory = noteListViewModelFactory(app.noteRepository)),
                onOpenNote = { pageId -> navController.navigate("note/$pageId") },
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
            )
        }
    }
}

private const val ROUTE_NOTES = "notes"
private const val ROUTE_NOTE = "note/{pageId}"

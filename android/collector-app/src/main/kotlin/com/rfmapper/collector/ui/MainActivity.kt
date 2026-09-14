package com.rfmapper.collector.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfmapper.collector.CollectorGraph
import com.rfmapper.collector.collection.CollectionService
import com.rfmapper.collector.ui.theme.RFMapperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = CollectorGraph.from(this)

        setContent {
            RFMapperTheme {
                val model: CollectorViewModel = viewModel(
                    factory = CollectorViewModel.factory(
                        graph = graph,
                        startService = { CollectionService.start(this) },
                        stopService = { CollectionService.stop(this) },
                    ),
                )
                CollectorApp(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions and radio switches can change while the app is away, and a stale capability
        // matrix is worse than none: it would report a sensor as ready when it is off.
        CollectorGraph.from(this).coordinator.refreshCapabilities()
    }
}

private enum class Destination(val label: String, val icon: ImageVector) {
    DASHBOARD("Session", Icons.Filled.Home),
    SURVEY("Survey", Icons.Filled.Place),
    EXPORT("Export", Icons.Filled.Share),
    SETUP("Setup", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectorApp(model: CollectorViewModel) {
    var destination by remember { mutableStateOf(Destination.DASHBOARD) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(model) {
        model.events.collect { message -> snackbar.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("RFMapper Collector") })
        },
        bottomBar = {
            NavigationBar {
                for (entry in Destination.entries) {
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.DASHBOARD -> DashboardScreen(
                    viewModel = model,
                    onOpenOnboarding = { destination = Destination.SETUP },
                )
                Destination.SURVEY -> SurveyScreen(model)
                Destination.EXPORT -> ExportScreen(model)
                Destination.SETUP -> OnboardingScreen(model)
            }
        }
    }
}

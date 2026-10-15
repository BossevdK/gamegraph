package nl.gamegraph.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import nl.gamegraph.app.ui.AppNav
import nl.gamegraph.app.ui.theme.GameGraphTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory((application as GameGraphApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GameGraphApp).container
        // Na een crash: meld herstel en ga terug naar de laatste veilige plek.
        val recovered = container.recovery.consumeRecoveryNotice()
        val requested = intent.getStringExtra(EXTRA_ROUTE)
        val startRoute = requested ?: if (recovered) container.recovery.lastSafeRoute else null

        setContent {
            val state by viewModel.state.collectAsState()
            GameGraphTheme(state.settings.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav(viewModel = viewModel, startRoute = startRoute ?: "home", recovered = recovered)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}

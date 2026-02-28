package com.krypt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

val KryptBlack = Color(0xFF000000)
val KryptDark = Color(0xFF0D0D0D)
val KryptCard = Color(0xFF1A1A1A)
val KryptAccent = Color(0xFF00E5FF)
val KryptText = Color(0xFFFFFFFF)
val KryptSubtext = Color(0xFF888888)

private val KryptDarkColorScheme = darkColorScheme(
    primary = KryptAccent,
    onPrimary = KryptBlack,
    background = KryptBlack,
    onBackground = KryptText,
    surface = KryptDark,
    onSurface = KryptText,
    surfaceVariant = KryptCard,
    onSurfaceVariant = KryptText,
    secondary = KryptAccent,
    onSecondary = KryptBlack
)

class MainActivity : ComponentActivity() {

    private val viewModel: KryptViewModel by viewModels {
        KryptViewModel.Factory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = KryptDarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KryptNavGraph(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkClient.disconnect()
    }
}

@Composable
fun KryptNavGraph(viewModel: KryptViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    // Navigate to call screen when call starts
    LaunchedEffect(uiState.callState.isInCall) {
        if (uiState.callState.isInCall) {
            navController.navigate("call/${uiState.callState.remoteUuid}") {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = "contacts") {

        composable("contacts") {
            ContactsScreen(
                viewModel = viewModel,
                onOpenChat = { uuid ->
                    viewModel.openConversation(uuid)
                    navController.navigate("chat/$uuid")
                },
                onOpenStatus = { navController.navigate("status") }
            )
        }

        composable(
            route = "chat/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType })
        ) { backStack ->
            val uuid = backStack.arguments?.getString("uuid") ?: return@composable
            ChatScreen(
                viewModel = viewModel,
                contactUuid = uuid,
                onStartCall = { viewModel.startCall(uuid) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "call/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType })
        ) { backStack ->
            val uuid = backStack.arguments?.getString("uuid") ?: return@composable
            CallScreen(
                viewModel = viewModel,
                remoteUuid = uuid,
                onEndCall = {
                    viewModel.endCall()
                    navController.popBackStack()
                }
            )
        }

        composable("status") {
            StatusScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

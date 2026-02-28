package com.krypt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
    viewModel: KryptViewModel,
    remoteUuid: String,
    onEndCall: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val callState = uiState.callState
    var isMuted by remember { mutableStateOf(false) }
    var isCameraOff by remember { mutableStateOf(false) }

    // Remote and local SurfaceViewRenderer refs
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Attach renderers once WebRTCManager is available
    LaunchedEffect(viewModel.webRTCManager) {
        viewModel.webRTCManager?.let { mgr ->
            localRenderer?.let { mgr.attachLocalView(it) }
            remoteRenderer?.let { mgr.attachRemoteView(it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KryptBlack)
    ) {
        // Remote video (full screen)
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { renderer ->
                    remoteRenderer = renderer
                    viewModel.webRTCManager?.attachRemoteView(renderer)
                    // Listen for remote track
                    viewModel.webRTCManager?.let { mgr ->
                        // Remote track attachment handled in WebRTCManager via onTrack callback
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Incoming call overlay
        if (callState.isIncoming && callState.pendingOfferSdp.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(KryptBlack.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text("Incoming Call", color = KryptText, fontSize = 28.sp)
                    Text(
                        remoteUuid.take(20) + "…",
                        color = KryptSubtext,
                        fontSize = 14.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        // Decline
                        IconButton(
                            onClick = onEndCall,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        ) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                tint = KryptText,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        // Accept
                        IconButton(
                            onClick = { viewModel.acceptCall() },
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00C853))
                        ) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = "Accept",
                                tint = KryptText,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // Active call UI

            // Local video (PiP top-right)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 100.dp, height = 140.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(KryptDark)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { renderer ->
                            localRenderer = renderer
                            viewModel.webRTCManager?.attachLocalView(renderer)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top info
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 48.dp)
            ) {
                Text("🔒 E2EE Call", color = KryptAccent, fontSize = 12.sp)
                Text(remoteUuid.take(16) + "…", color = KryptText, fontSize = 18.sp)
            }

            // Control buttons at bottom
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute
                CallButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    tint = if (isMuted) Color.Red else KryptText,
                    onClick = { isMuted = !isMuted }
                )

                // End call
                IconButton(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = KryptText,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Camera toggle
                CallButton(
                    icon = if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    tint = if (isCameraOff) Color.Red else KryptText,
                    onClick = { isCameraOff = !isCameraOff }
                )
            }
        }
    }
}

@Composable
fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = KryptText,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(KryptCard)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
    }
}

package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.ConnectionHistoryEntity

// Wii Inspired Colors
val WiiBlue = Color(0xFF00AAFF)
val WiiGrey = Color(0xFFEBEBEB)
val SlateBackground = Color(0xFF16181C)
val LightSlate = Color(0xFF24272E)
val CrimsonRed = Color(0xFFFF3366)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WiiControllerScreen(viewModel: WiiControllerViewModel) {
    val isDsuRunning by viewModel.isDsuRunning.collectAsStateWithLifecycle()
    val isAudioRunning by viewModel.isAudioRunning.collectAsStateWithLifecycle()
    val ipAddr by viewModel.ipAddress.collectAsStateWithLifecycle()
    val clients by viewModel.registeredClients.collectAsStateWithLifecycle()
    val sentCount by viewModel.totalPacketsSent.collectAsStateWithLifecycle()
    val recvCount by viewModel.totalPacketsReceived.collectAsStateWithLifecycle()
    val fpsVal by viewModel.dsuFps.collectAsStateWithLifecycle()
    val audioBytes by viewModel.audioBytesReceived.collectAsStateWithLifecycle()
    val isAudioStreaming by viewModel.isAudioStreaming.collectAsStateWithLifecycle()

    val accel by viewModel.accelState.collectAsStateWithLifecycle()
    val gyro by viewModel.gyroState.collectAsStateWithLifecycle()
    val connections by viewModel.connectionHistory.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(0) } // 0 = Controller, 1 = Server Hub, 2 = Guide

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = LightSlate,
                contentColor = Color.White,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Controller") },
                    label = { Text("Gamepad") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = WiiBlue,
                        selectedTextColor = WiiBlue,
                        indicatorColor = LightSlate.copy(alpha = 0.5f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Server Settings") },
                    label = { Text("Server Hub") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = WiiBlue,
                        selectedTextColor = WiiBlue,
                        indicatorColor = LightSlate.copy(alpha = 0.5f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Manual Guide") },
                    label = { Text("Instructions") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = WiiBlue,
                        selectedTextColor = WiiBlue,
                        indicatorColor = LightSlate.copy(alpha = 0.5f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
            }
        },
        containerColor = SlateBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(SlateBackground)
        ) {
            // Mini Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSlate)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Wii Controller",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = "IP: $ipAddr",
                        fontSize = 12.sp,
                        color = WiiBlue,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Servers status LED
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isDsuRunning) Color.Green else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("DSU", fontSize = 10.sp, color = Color.LightGray)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isAudioRunning) (if (isAudioStreaming) WiiBlue else Color.Yellow) else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Audio", fontSize = 10.sp, color = Color.LightGray)
                    }
                }
            }

            // Tabs Content
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "TabTransition",
                modifier = Modifier.fillMaxSize()
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> ControllerTab(viewModel, accel, gyro, isDsuRunning)
                    1 -> ServerHubTab(viewModel, isDsuRunning, isAudioRunning, sentCount, recvCount, fpsVal, audioBytes, isAudioStreaming, connections, ipAddr)
                    2 -> InstructionTab()
                }
            }
        }
    }
}

@Composable
fun ControllerTab(
    viewModel: WiiControllerViewModel,
    accel: Triple<Float, Float, Float>,
    gyro: Triple<Float, Float, Float>,
    isRunning: Boolean
) {
    if (!isRunning) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "DSU Server is Offline",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Go to the 'Server Hub' tab and start the DSU server first, then map inputs within Dolphin.",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.startDsuServer() },
                colors = ButtonDefaults.buttonColors(containerColor = WiiBlue),
                modifier = Modifier.testTag("activate_server_btn")
            ) {
                Text("Start Server Now", color = Color.White)
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Left Column: Custom Interactive Sensory Gauges
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Device info & visual 3D tilt
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LightSlate)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Motion Tracker",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Gravity Deflection Gauge Bubble
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(SlateBackground)
                            .border(1.dp, Color.DarkGray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2, size.height / 2)
                            val radius = size.width / 2

                            // draw secondary ring representing standard g-factor
                            drawCircle(
                                color = Color.DarkGray.copy(alpha = 0.4f),
                                radius = radius * 0.5f,
                                center = center,
                                style = Stroke(width = 1f)
                            )

                            // Crosshairs lines
                            drawLine(
                                color = Color.DarkGray,
                                start = Offset(0f, center.y),
                                end = Offset(size.width, center.y)
                            )
                            drawLine(
                                color = Color.DarkGray,
                                start = Offset(center.x, 0f),
                                end = Offset(center.x, size.height)
                            )

                            // Maps Accelerometer X & Y to bubble coordinates
                            // Max gravity is approx 9.8.
                            val maxForce = 9.80665f
                            // Clamp and scale deflection to canvas bounds
                            val defX = (-accel.first / maxForce).coerceIn(-1f, 1f) * (radius - 12.dp.toPx())
                            val defY = (accel.second / maxForce).coerceIn(-1f, 1f) * (radius - 12.dp.toPx())

                            drawCircle(
                                color = WiiBlue,
                                radius = 8.dp.toPx(),
                                center = Offset(center.x + defX, center.y + defY)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Roll/Pitch bubble level\nTilt your phone to aim",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }

                // Shaking action feedback module
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LightSlate)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Shake Emulator",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Big interactive shake node button
                    IconButton(
                        onClick = {
                            viewModel.onButtonPressed("SHAKE", true)
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f))
                            .border(1.dp, WiiBlue, CircleShape)
                            .testTag("action_shake_trigger")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Trigger Shake",
                            tint = WiiBlue,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "TAP or SHAKE physical phone physically to spin/attack",
                        fontSize = 9.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }

                // Sensor rate info indicators
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LightSlate)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TelemetryRow("Acc X", String.format("%.2f", accel.first))
                    TelemetryRow("Acc Y", String.format("%.2f", accel.second))
                    TelemetryRow("Acc Z", String.format("%.2f", accel.third))
                    TelemetryRow("Yaw Rate", String.format("%.1f", Math.toDegrees(gyro.third.toDouble())))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right Column: Full WiiMote Controller layout container
            Box(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .border(1.dp, Color.LightGray, RoundedCornerShape(24.dp))
                    .shadow(3.dp, RoundedCornerShape(24.dp))
                    .padding(vertical = 14.dp, horizontal = 10.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Wii logo lookalike branding
                    Text(
                        "Wii",
                        fontSize = 24.sp,
                        color = Color.DarkGray,
                        fontWeight = FontWeight.W700,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-1).sp
                    )

                    // D-PAD
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        // Horizontal crossbar
                        Box(
                            modifier = Modifier
                                .width(110.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2E2E2E))
                        )
                        // Vertical crossbar
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(110.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF2E2E2E))
                        )

                        // Center intersection dot
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1D1D1D))
                        )

                        // Click Areas
                        // UP
                        WiiDpadButton(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .size(32.dp, 39.dp),
                            onClick = { press -> viewModel.onButtonPressed("UP", press) },
                            text = "▲"
                        )
                        // DOWN
                        WiiDpadButton(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .size(32.dp, 39.dp),
                            onClick = { press -> viewModel.onButtonPressed("DOWN", press) },
                            text = "▼"
                        )
                        // LEFT
                        WiiDpadButton(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(39.dp, 32.dp),
                            onClick = { press -> viewModel.onButtonPressed("LEFT", press) },
                            text = "◀"
                        )
                        // RIGHT
                        WiiDpadButton(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(39.dp, 32.dp),
                            onClick = { press -> viewModel.onButtonPressed("RIGHT", press) },
                            text = "▶"
                        )
                    }

                    // A Button (Slightly larger, acrylic tactile button)
                    InteractiveControllerButton(
                        onClick = { press -> viewModel.onButtonPressed("A", press) },
                        modifier = Modifier
                            .size(62.dp)
                            .testTag("controller_a_button")
                    ) { isPressed ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(if (isPressed) WiiBlue else Color(0xFFE0E0E0))
                                .border(2.dp, Color.LightGray, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "A",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPressed) Color.White else Color.DarkGray
                            )
                        }
                    }

                    // Home, Plus, Minus Button cluster
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Minus Button
                        WiiSmallRoundButton("-", testTag = "controller_minus_btn") { press ->
                            viewModel.onButtonPressed("MINUS", press)
                        }

                        // Home Button
                        InteractiveControllerButton(
                            onClick = { press -> viewModel.onButtonPressed("HOME", press) },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("controller_home_btn")
                        ) { isPressed ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(if (isPressed) CrimsonRed else Color(0xFFF7F7F7))
                                    .border(1.dp, Color.LightGray, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Home,
                                    contentDescription = "Home",
                                    tint = if (isPressed) Color.White else CrimsonRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Plus Button
                        WiiSmallRoundButton("+", testTag = "controller_plus_btn") { press ->
                            viewModel.onButtonPressed("PLUS", press)
                        }
                    }

                    // 1 and 2 Buttons
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        WiiSquareButton("1", testTag = "controller_1_btn") { press ->
                            viewModel.onButtonPressed("ONE", press)
                        }
                        WiiSquareButton("2", testTag = "controller_2_btn") { press ->
                            viewModel.onButtonPressed("TWO", press)
                        }
                    }

                    // Trigger B Button on-screen representation (represented at base)
                    InteractiveControllerButton(
                        onClick = { press -> viewModel.onButtonPressed("B", press) },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(42.dp)
                            .testTag("controller_b_trigger")
                    ) { isPressed ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isPressed) WiiBlue else Color(0xFFEEEEEE))
                                .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "B TRIGGER (BACK)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isPressed) Color.White else Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun WiiDpadButton(
    modifier: Modifier,
    onClick: (Boolean) -> Unit,
    text: String
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onClick(true)
                        tryAwaitRelease()
                        isPressed = false
                        onClick(false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isPressed) WiiBlue else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun InteractiveControllerButton(
    onClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (isPressed: Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onClick(true)
                        tryAwaitRelease()
                        isPressed = false
                        onClick(false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content(isPressed)
    }
}

@Composable
fun WiiSmallRoundButton(
    symbol: String,
    testTag: String,
    onClick: (Boolean) -> Unit
) {
    InteractiveControllerButton(
        onClick = onClick,
        modifier = Modifier
            .size(24.dp)
            .testTag(testTag)
    ) { isPressed ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (isPressed) WiiBlue else Color(0xFFF0F0F0))
                .border(1.dp, Color.LightGray, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                symbol,
                fontSize = 14.sp,
                color = if (isPressed) Color.White else Color.DarkGray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun WiiSquareButton(
    text: String,
    testTag: String,
    onClick: (Boolean) -> Unit
) {
    InteractiveControllerButton(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .testTag(testTag)
    ) { isPressed ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(if (isPressed) WiiBlue else Color(0xFFEAEAEA))
                .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPressed) Color.White else Color.DarkGray
            )
        }
    }
}

@Composable
fun ServerHubTab(
    viewModel: WiiControllerViewModel,
    isDsuRunning: Boolean,
    isAudioRunning: Boolean,
    sentCount: Int,
    recvCount: Int,
    fpsVal: Int,
    audioBytes: Long,
    isAudioStreaming: Boolean,
    connections: List<ConnectionHistoryEntity>,
    phoneIp: String
) {
    var customDsuPort by remember { mutableStateOf("26760") }
    var customAudioPort by remember { mutableStateOf("26761") }

    var saveProfileIp by remember { mutableStateOf("") }
    var saveProfileDesc by remember { mutableStateOf("") }

    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Core Server Launchpad
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Low-Latency Server Launchpad",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // DSU Server controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("DSU input Server (Port)", fontSize = 12.sp, color = Color.LightGray)
                            // Port Input
                            TextField(
                                value = customDsuPort,
                                onValueChange = { customDsuPort = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                readOnly = isDsuRunning,
                                textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = SlateBackground,
                                    unfocusedContainerColor = SlateBackground,
                                    focusedIndicatorColor = WiiBlue
                                ),
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(50.dp)
                            )
                        }

                        Button(
                            onClick = {
                                if (isDsuRunning) {
                                    viewModel.stopDsuServer()
                                } else {
                                    val portInt = customDsuPort.toIntOrNull() ?: 26760
                                    viewModel.startDsuServer(portInt)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDsuRunning) CrimsonRed else WiiBlue
                            ),
                            modifier = Modifier.testTag("toggle_dsu_btn")
                        ) {
                            Text(if (isDsuRunning) "Stop DSU" else "Start DSU", color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Audio Stream Server controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Wii Sound Server (Port)", fontSize = 12.sp, color = Color.LightGray)
                            TextField(
                                value = customAudioPort,
                                onValueChange = { customAudioPort = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                readOnly = isAudioRunning,
                                textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = SlateBackground,
                                    unfocusedContainerColor = SlateBackground,
                                    focusedIndicatorColor = WiiBlue
                                ),
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(50.dp)
                            )
                        }

                        Button(
                            onClick = {
                                val portInt = customAudioPort.toIntOrNull() ?: 26761
                                viewModel.toggleAudioServer(portInt)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAudioRunning) CrimsonRed else Color.DarkGray
                            ),
                            modifier = Modifier.testTag("toggle_audio_btn")
                        ) {
                            Text(if (isAudioRunning) "Stop Speaker" else "Start Speaker", color = Color.White)
                        }
                    }
                }
            }
        }

        // Live Network Stats & Telemetry
        if (isDsuRunning || isAudioRunning) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LightSlate),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Live Server Telemetry", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TelemetryBox("DSU Speed", "${fpsVal}Hz", WiiBlue)
                            TelemetryBox("DSU Out / In", "$sentCount / $recvCount", Color.Green)
                            TelemetryBox(
                                "Wii Sound",
                                if (isAudioRunning) (if (isAudioStreaming) "Connected" else "Waiting") else "Offline",
                                if (isAudioStreaming) WiiBlue else Color.Gray
                            )
                        }
                    }
                }
            }
        }

        // Retro synthesized audio board & Rumble Test Box
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Nostalgia Sound & Haptic Test Board",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Synthesize raw chimes directly on device speakers to check sounds and try haptics.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.playSyntheticSound(1) },
                            colors = ButtonDefaults.buttonColors(containerColor = SlateBackground),
                            modifier = Modifier.weight(1f).testTag("play_click_btn")
                        ) {
                            Text("Wii Click", color = Color.White, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                viewModel.playSyntheticSound(2)
                                viewModel.triggerVibration(180, 255) // short synchronized vibration chime
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SlateBackground),
                            modifier = Modifier.weight(1.1f).testTag("play_chime_btn")
                        ) {
                            Text("Wii Beep Chime", color = WiiBlue, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.triggerVibration(400, 255) },
                            colors = ButtonDefaults.buttonColors(containerColor = SlateBackground),
                            modifier = Modifier.weight(1f).testTag("play_rumble_btn")
                        ) {
                            Text("Try Rumble ⌁", color = CrimsonRed, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Add Connection Profile
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Save Connection Profile", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = saveProfileIp,
                        onValueChange = { saveProfileIp = it },
                        label = { Text("PC IP Address", color = Color.Gray) },
                        textStyle = TextStyle(color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WiiBlue,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("profile_ip_field")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = saveProfileDesc,
                        onValueChange = { saveProfileDesc = it },
                        label = { Text("Description (e.g. My PC - Dolphin)", color = Color.Gray) },
                        textStyle = TextStyle(color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WiiBlue,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("profile_desc_field")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (saveProfileIp.isNotBlank()) {
                                viewModel.saveConnectionToHistory(saveProfileIp, customDsuPort.toIntOrNull() ?: 26760, saveProfileDesc)
                                saveProfileIp = ""
                                saveProfileDesc = ""
                                viewModel.triggerVibration(50, 100)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WiiBlue),
                        modifier = Modifier.align(Alignment.End).testTag("save_profile_btn")
                    ) {
                        Text("Save Profile", color = Color.White)
                    }
                }
            }
        }

        // Connection History list (Room database backed)
        if (connections.isNotEmpty()) {
            item {
                Text(
                    "Historical Profiles & Saved Servers",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(connections) { profile ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = LightSlate.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Easily import server connection variables from a saved history item
                            saveProfileIp = profile.ipAddress
                            customDsuPort = profile.port.toString()
                            viewModel.triggerVibration(30, 80)
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                profile.description.ifEmpty { "Wii Dolphin Server" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "${profile.ipAddress}:${profile.port}",
                                fontSize = 11.sp,
                                color = WiiBlue
                            )
                        }

                        IconButton(
                            onClick = { viewModel.deleteProfile(profile.ipAddress, profile.port) }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Profile",
                                tint = CrimsonRed.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = { viewModel.clearHistory() },
                    colors = ButtonDefaults.textButtonColors(contentColor = CrimsonRed),
                    modifier = Modifier.fillMaxWidth().testTag("clear_history_btn")
                ) {
                    Text("Clear All Saved Connections", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun TelemetryBox(label: String, value: String, valueColor: Color) {
    Box(
        modifier = Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SlateBackground)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, color = Color.Gray, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = valueColor, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun InstructionTab() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Plug & Play: Connecting to Dolphin",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = WiiBlue
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    GuideStep("1", "Check Wireless LAN Environment", "Ensure both your Android device and the emulator host computer (PC/Mac/Linux) are on the exact same Wi-Fi subnet.")
                    GuideStep("2", "Start DSU and Sound Servers", "Tap 'Start DSU' on the 'Server Hub' Tab. Note your phone's Wi-Fi IP address shown in the blue banner at the top (e.g., 192.168.1.10).")
                    GuideStep("3", "Configure Dolphin Alternate Inputs", "Open Dolphin. Go to 'Controllers' -> under 'Wii Remotes' choose 'Emulated Wii Remote' -> Click 'Configure' -> Click 'Alternate Input Sources' -> Enable -> Add Server and write your Phone's IP. Press OK.")
                    GuideStep("4", "Map Controls & Calibrate Motion", "In Dolphin Controller mapping, choose 'DSU/Client' as your input device. Map the buttons (A, B, D-pad). Under Motion Input, mapping layout will pick up accelerometer and gyroscope values dynamically from the DSU stream!")
                    GuideStep("5", "Enable Immersive Game sounds", "Deploy the 'dolphin_wiimote_sound_streamer.py' python script on your PC. Run:\n`python dolphin_wiimote_sound_streamer.py --ip YOUR_PHONE_IP` to route retro sound effects smoothly.")
                }
            }
        }
    }
}

@Composable
fun GuideStep(step: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(WiiBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(description, color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

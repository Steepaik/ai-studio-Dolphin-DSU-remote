package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.ConnectionHistoryEntity
import com.example.network.BluetoothControllerManager
import com.example.network.BluetoothRole
import com.example.network.BtConnectionState

// Wii Inspired Colors
val WiiBlue = Color(0xFF00AAFF)
val WiiGrey = Color(0xFFEBEBEB)
val SlateBackground = Color(0xFF13151A)
val LightSlate = Color(0xFF20232A)
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

    // Bluetooth States
    val btRole by viewModel.btManager.role.collectAsStateWithLifecycle()
    val btState by viewModel.btManager.connectionState.collectAsStateWithLifecycle()
    val btDeviceName by viewModel.btManager.connectedDeviceName.collectAsStateWithLifecycle()

    // Customizable Styles
    val selectedTheme by viewModel.themeColor.collectAsStateWithLifecycle()

    val currentAccentColor = when(selectedTheme) {
        "Carbon Grey" -> Color(0xFF5A626F)
        "Nintendo Red" -> Color(0xFFE60012)
        "Teal Fusion" -> Color(0xFF008080)
        else -> WiiBlue
    }

    var activeTab by remember { mutableStateOf(0) } // 0=Gamepad, 1=DSU Server, 2=Bluetooth Sync, 3=Customizer, 4=Guides

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = LightSlate,
                contentColor = Color.White,
                modifier = Modifier.navigationBarsPadding(),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Gamepad") },
                    label = { Text("Gamepad", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = currentAccentColor,
                        selectedTextColor = currentAccentColor,
                        indicatorColor = LightSlate.copy(alpha = 0.5f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Server Settings") },
                    label = { Text("Server Hub", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = currentAccentColor,
                        selectedTextColor = currentAccentColor,
                        indicatorColor = LightSlate.copy(alpha = 0.5f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Share, contentDescription = "Bluetooth Sync") },
                    label = { Text("BT Sync", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = currentAccentColor,
                        selectedTextColor = currentAccentColor,
                        indicatorColor = LightSlate.copy(alpha = 0.5f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    icon = { Icon(Icons.Default.Create, contentDescription = "Layout Customizer") },
                    label = { Text("Customize", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = currentAccentColor,
                        selectedTextColor = currentAccentColor,
                        indicatorColor = LightSlate.copy(alpha = 0.5f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 4,
                    onClick = { activeTab = 4 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Manual Guide") },
                    label = { Text("Guides", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = currentAccentColor,
                        selectedTextColor = currentAccentColor,
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
            // Header Stats bar
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
                        text = "Wii Motion Link",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (btRole == BluetoothRole.SENDER && btState == BtConnectionState.CONNECTED) "BT Transmitter Mode"
                            else if (btRole == BluetoothRole.RECEIVER && btState == BtConnectionState.CONNECTED) "BT Receiver Bridge"
                            else "IP: $ipAddr",
                            fontSize = 11.sp,
                            color = currentAccentColor,
                            fontWeight = FontWeight.Bold
                        )
                        if (btDeviceName != null) {
                            Text(
                                text = " ➔ $btDeviceName",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }

                // Integration state display indicators
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isDsuRunning) Color.Green else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("DSU", fontSize = 9.sp, color = Color.LightGray)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (btState) {
                                        BtConnectionState.CONNECTED -> Color.Green
                                        BtConnectionState.CONNECTING -> Color.Yellow
                                        BtConnectionState.LISTENING -> WiiBlue
                                        else -> Color.Gray
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("BT", fontSize = 9.sp, color = Color.LightGray)
                    }
                }
            }

            // Central tab renderer
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "MainTabs",
                modifier = Modifier.fillMaxSize()
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> ControllerTab(viewModel, accel, gyro, isDsuRunning, btRole, btState, currentAccentColor)
                    1 -> ServerHubTab(viewModel, isDsuRunning, isAudioRunning, sentCount, recvCount, fpsVal, audioBytes, isAudioStreaming, connections, ipAddr, currentAccentColor)
                    2 -> BluetoothSyncTab(viewModel, currentAccentColor)
                    3 -> CustomizerTab(viewModel, currentAccentColor)
                    4 -> InstructionTab(currentAccentColor)
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
    isDsuRunning: Boolean,
    btRole: BluetoothRole,
    btState: BtConnectionState,
    themeColor: Color
) {
    val inputIsActive = isDsuRunning || (btRole == BluetoothRole.SENDER && btState == BtConnectionState.CONNECTED)

    if (!inputIsActive) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Controller Input Offline",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "To play games, turn on your local 'DSU input Server' under Server Hub, or connect to an android receiver via 'Bluetooth Sync'!",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { viewModel.startDsuServer() },
                colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                modifier = Modifier.testTag("activate_dsu_standalone_btn")
            ) {
                Text("Start Standalone DSU", color = Color.White)
            }
        }
    } else {
        val preset by viewModel.layoutPreset.collectAsStateWithLifecycle()
        val scale by viewModel.buttonScale.collectAsStateWithLifecycle()
        val selectedTheme by viewModel.themeColor.collectAsStateWithLifecycle()

        val (shellBg, buttonBg, isDarkTheme) = when (selectedTheme) {
            "Carbon Grey" -> Triple(Color(0xFF2E3138), Color(0xFF1E2024), true)
            "Nintendo Red" -> Triple(Color(0xFFFFEEF0), Color(0xFFE60012).copy(alpha = 0.15f), false)
            "Teal Fusion" -> Triple(Color(0xFFE0F4F4), Color(0xFF008080).copy(alpha = 0.15f), false)
            else -> Triple(Color.White, Color(0xFFF0F0F0), false) // Classic White
        }

        val txtColor = if (isDarkTheme) Color.White else Color.DarkGray
        val labelColor = if (isDarkTheme) Color.LightGray else Color.Gray

        when (preset) {
            "Horizontal Gamepad" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(LightSlate)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text("Tilt Pitch: ${String.format("%.1f", accel.second)}", color = Color.White, fontSize = 11.sp)
                        Text("Tilt Roll: ${String.format("%.1f", accel.first)}", color = Color.White, fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(shellBg)
                            .border(2.dp, themeColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left d-pad section
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("STEER", fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier.size((100 * scale).dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.width((100 * scale).dp).height((30 * scale).dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2E2E2E)))
                                Box(modifier = Modifier.width((30 * scale).dp).height((100 * scale).dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2E2E2E)))
                                
                                WiiDpadButton(modifier = Modifier.align(Alignment.TopCenter).size((30 * scale).dp, (35 * scale).dp), onClick = { b -> viewModel.onButtonPressed("UP", b) }, text = "▲")
                                WiiDpadButton(modifier = Modifier.align(Alignment.BottomCenter).size((30 * scale).dp, (35 * scale).dp), onClick = { b -> viewModel.onButtonPressed("DOWN", b) }, text = "▼")
                                WiiDpadButton(modifier = Modifier.align(Alignment.CenterStart).size((35 * scale).dp, (30 * scale).dp), onClick = { b -> viewModel.onButtonPressed("LEFT", b) }, text = "◀")
                                WiiDpadButton(modifier = Modifier.align(Alignment.CenterEnd).size((35 * scale).dp, (30 * scale).dp), onClick = { b -> viewModel.onButtonPressed("RIGHT", b) }, text = "▶")
                            }
                        }

                        // Center controllers
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                WiiSmallRoundButton("-", scale = scale, onClick = { b -> viewModel.onButtonPressed("MINUS", b) })
                                InteractiveControllerButton(
                                    onClick = { b -> viewModel.onButtonPressed("HOME", b) },
                                    modifier = Modifier.size((24 * scale).dp)
                                ) { isPressed ->
                                    Box(
                                        modifier = Modifier.fillMaxSize().clip(CircleShape).background(if (isPressed) CrimsonRed else Color(0xFFF0F0F0)).border(1.dp, Color.LightGray, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Home, contentDescription = "Home", tint = if (isPressed) Color.White else CrimsonRed, modifier = Modifier.size((12 * scale).dp))
                                    }
                                }
                                WiiSmallRoundButton("+", scale = scale, onClick = { b -> viewModel.onButtonPressed("PLUS", b) })
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { viewModel.onButtonPressed("SHAKE", true) }
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.DarkGray)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = WiiBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SHAKE CONTROLLER", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Right action buttons
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WiiSquareButton("1", scale = scale, onClick = { b -> viewModel.onButtonPressed("ONE", b) })
                                WiiSquareButton("2", scale = scale, onClick = { b -> viewModel.onButtonPressed("TWO", b) })
                            }

                            InteractiveControllerButton(
                                onClick = { b -> viewModel.onButtonPressed("A", b) },
                                modifier = Modifier.size((50 * scale).dp)
                            ) { isPressed ->
                                Box(
                                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(if (isPressed) themeColor else buttonBg).border(2.dp, Color.LightGray, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("A", fontSize = (18 * scale).sp, fontWeight = FontWeight.Bold, color = if (isPressed) Color.White else txtColor)
                                }
                            }

                            InteractiveControllerButton(
                                onClick = { b -> viewModel.onButtonPressed("B", b) },
                                modifier = Modifier.width((80 * scale).dp).height((34 * scale).dp)
                            ) { isPressed ->
                                Box(
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(if (isPressed) themeColor else buttonBg).border(1.dp, Color.LightGray, RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("B", fontSize = (11 * scale).sp, fontWeight = FontWeight.SemiBold, color = if (isPressed) Color.White else txtColor)
                                }
                            }
                        }
                    }
                }
            }
            "Big Buttons" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(shellBg)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val btns = listOf("A", "B", "UP", "DOWN", "LEFT", "RIGHT", "ONE", "TWO", "MINUS", "PLUS", "HOME", "SHAKE")
                        items(btns) { name ->
                            InteractiveControllerButton(
                                onClick = { isPressed -> viewModel.onButtonPressed(name, isPressed) },
                                modifier = Modifier.fillMaxWidth().height((64 * scale).dp)
                            ) { isPressed ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isPressed) themeColor else buttonBg)
                                        .border(2.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (isPressed) Color.White else txtColor)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                // Classic Tall Vertical Wii Remote
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LightSlate)
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Motion Tracker", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.LightGray, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(10.dp))

                            Box(
                                modifier = Modifier.size(110.dp).clip(CircleShape).background(SlateBackground).border(1.dp, Color.DarkGray, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                        val center = Offset(size.width / 2, size.height / 2)
                                        val radius = size.width / 2
                                        drawCircle(color = Color.DarkGray.copy(alpha = 0.4f), radius = radius * 0.5f, center = center, style = Stroke(width = 1f))
                                        drawLine(color = Color.DarkGray, start = Offset(0f, center.y), end = Offset(size.width, center.y))
                                        drawLine(color = Color.DarkGray, start = Offset(center.x, 0f), end = Offset(center.x, size.height))

                                        val maxForce = 9.80665f
                                        val defX = (-accel.first / maxForce).coerceIn(-1f, 1f) * (radius - 12.dp.toPx())
                                        val defY = (accel.second / maxForce).coerceIn(-1f, 1f) * (radius - 12.dp.toPx())

                                        drawCircle(color = themeColor, radius = 8.dp.toPx(), center = Offset(center.x + defX, center.y + defY))
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LightSlate)
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Shake Trigger", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(8.dp))

                            IconButton(
                                onClick = { viewModel.onButtonPressed("SHAKE", true) },
                                modifier = Modifier.size((50 * scale).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).border(1.dp, themeColor, CircleShape)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Trigger Shake", tint = themeColor, modifier = Modifier.size(28.dp))
                            }
                        }

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
                            TelemetryRow("Gyro Z", String.format("%.1f", Math.toDegrees(gyro.third.toDouble())))
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(24.dp))
                            .background(shellBg)
                            .border(2.dp, themeColor.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                            .shadow(4.dp, RoundedCornerShape(24.dp))
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Wii", fontSize = 24.sp, color = txtColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, letterSpacing = (-1).sp)

                            Box(
                                modifier = Modifier.size((100 * scale).dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.width((100 * scale).dp).height((28 * scale).dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2E2E2E)))
                                Box(modifier = Modifier.width((28 * scale).dp).height((100 * scale).dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2E2E2E)))
                                Box(modifier = Modifier.size((28 * scale).dp).clip(CircleShape).background(Color(0xFF1D1D1D)))

                                WiiDpadButton(modifier = Modifier.align(Alignment.TopCenter).size((28 * scale).dp, (35 * scale).dp), onClick = { press -> viewModel.onButtonPressed("UP", press) }, text = "▲")
                                WiiDpadButton(modifier = Modifier.align(Alignment.BottomCenter).size((28 * scale).dp, (35 * scale).dp), onClick = { press -> viewModel.onButtonPressed("DOWN", press) }, text = "▼")
                                WiiDpadButton(modifier = Modifier.align(Alignment.CenterStart).size((35 * scale).dp, (28 * scale).dp), onClick = { press -> viewModel.onButtonPressed("LEFT", press) }, text = "◀")
                                WiiDpadButton(modifier = Modifier.align(Alignment.CenterEnd).size((35 * scale).dp, (28 * scale).dp), onClick = { press -> viewModel.onButtonPressed("RIGHT", press) }, text = "▶")
                            }

                            InteractiveControllerButton(
                                onClick = { press -> viewModel.onButtonPressed("A", press) },
                                modifier = Modifier.size((56 * scale).dp)
                            ) { isPressed ->
                                Box(
                                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(if (isPressed) themeColor else buttonBg).border(2.dp, Color.LightGray, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("A", fontSize = (20 * scale).sp, fontWeight = FontWeight.Bold, color = if (isPressed) Color.White else txtColor)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                WiiSmallRoundButton("-", scale = scale) { press -> viewModel.onButtonPressed("MINUS", press) }
                                    
                                InteractiveControllerButton(
                                    onClick = { press -> viewModel.onButtonPressed("HOME", press) },
                                    modifier = Modifier.size((26 * scale).dp)
                                ) { isPressed ->
                                    Box(
                                        modifier = Modifier.fillMaxSize().clip(CircleShape).background(if (isPressed) CrimsonRed else buttonBg).border(1.dp, Color.LightGray, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Home, contentDescription = "Home", tint = if (isPressed) Color.White else CrimsonRed, modifier = Modifier.size((14 * scale).dp))
                                    }
                                }

                                WiiSmallRoundButton("+", scale = scale) { press -> viewModel.onButtonPressed("PLUS", press) }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                WiiSquareButton("1", scale = scale) { press -> viewModel.onButtonPressed("ONE", press) }
                                WiiSquareButton("2", scale = scale) { press -> viewModel.onButtonPressed("TWO", press) }
                            }

                            InteractiveControllerButton(
                                onClick = { press -> viewModel.onButtonPressed("B", press) },
                                modifier = Modifier.fillMaxWidth(0.9f).height((38 * scale).dp)
                            ) { isPressed ->
                                Box(
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(if (isPressed) themeColor else buttonBg).border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("B TRIGGER (BACK)", fontSize = (11 * scale).sp, fontWeight = FontWeight.ExtraBold, color = if (isPressed) Color.White else txtColor)
                                }
                            }
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
    scale: Float,
    onClick: (Boolean) -> Unit
) {
    InteractiveControllerButton(
        onClick = onClick,
        modifier = Modifier
            .size((24 * scale).dp)
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
                fontSize = (13 * scale).sp,
                color = if (isPressed) Color.White else Color.DarkGray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun WiiSquareButton(
    text: String,
    scale: Float,
    onClick: (Boolean) -> Unit
) {
    InteractiveControllerButton(
        onClick = onClick,
        modifier = Modifier
            .size((34 * scale).dp)
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
                fontSize = (14 * scale).sp,
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
    phoneIp: String,
    themeColor: Color
) {
    var customDsuPort by remember { mutableStateOf("26760") }
    var customAudioPort by remember { mutableStateOf("26761") }

    var saveProfileIp by remember { mutableStateOf("") }
    var saveProfileDesc by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("DSU input Server (Port)", fontSize = 12.sp, color = Color.LightGray)
                            TextField(
                                value = customDsuPort,
                                onValueChange = { customDsuPort = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                readOnly = isDsuRunning,
                                textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = SlateBackground,
                                    unfocusedContainerColor = SlateBackground,
                                    focusedIndicatorColor = themeColor
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
                                containerColor = if (isDsuRunning) CrimsonRed else themeColor
                            ),
                            modifier = Modifier.testTag("toggle_dsu_btn")
                        ) {
                            Text(if (isDsuRunning) "Stop DSU" else "Start DSU", color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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
                                    focusedIndicatorColor = themeColor
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
                            TelemetryBox("DSU Speed", "${fpsVal}Hz", themeColor)
                            TelemetryBox("DSU Out / In", "$sentCount / $recvCount", Color.Green)
                            TelemetryBox(
                                "Wii Sound",
                                if (isAudioRunning) (if (isAudioStreaming) "Connected" else "Waiting") else "Offline",
                                if (isAudioStreaming) themeColor else Color.Gray
                            )
                        }
                    }
                }
            }
        }

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
                                viewModel.triggerVibration(180, 255)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SlateBackground),
                            modifier = Modifier.weight(1.1f).testTag("play_chime_btn")
                        ) {
                            Text("Wii Chime", color = themeColor, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.triggerVibration(400, 255) },
                            colors = ButtonDefaults.buttonColors(containerColor = SlateBackground),
                            modifier = Modifier.weight(1f).testTag("play_rumble_btn")
                        ) {
                            Text("Rumble ⌁", color = CrimsonRed, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

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
                            focusedBorderColor = themeColor,
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
                            focusedBorderColor = themeColor,
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
                        colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                        modifier = Modifier.align(Alignment.End).testTag("save_profile_btn")
                    ) {
                        Text("Save Profile", color = Color.White)
                    }
                }
            }
        }

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
                            saveProfileIp = profile.ipAddress
                            customDsuPort = profile.port.toString()
                            viewModel.triggerVibration(30, 80)
                        }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                                color = themeColor
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
fun BluetoothSyncTab(viewModel: WiiControllerViewModel, themeColor: Color) {
    val context = LocalContext.current
    val btRole by viewModel.btManager.role.collectAsStateWithLifecycle()
    val btState by viewModel.btManager.connectionState.collectAsStateWithLifecycle()
    val btDeviceName by viewModel.btManager.connectedDeviceName.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.btManager.pairedDevices.collectAsStateWithLifecycle()
    
    val bytesProcessed by viewModel.btManager.bytesTransmitted.collectAsStateWithLifecycle()
    val btFps by viewModel.btManager.btFps.collectAsStateWithLifecycle()

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    var permissionsGranted by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (permissionsGranted) {
            viewModel.btManager.refreshPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            viewModel.btManager.refreshPairedDevices()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = themeColor, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Dolphin Dual-Device Bluetooth Link", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Pair two Android devices directly over Bluetooth. One device acts as the raw motion \"Sender\" (your handheld controller), while the other device runs as the \"Receiver Bridge\" routing packet updates instantly over local Wi-Fi to the Dolphin Emulator.",
                        fontSize = 12.sp, color = Color.LightGray, lineHeight = 16.sp
                    )
                }
            }
        }

        if (!permissionsGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CrimsonRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Bluetooth Platform Permissions Required", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Android requires local connectivity permissions before searching/communicating over physical hardware radios.", fontSize = 12.sp, color = Color.LightGray, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { launcher.launch(permissionsToRequest.toTypedArray()) },
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed)
                        ) {
                            Text("Grant Wireless permissions", color = Color.White)
                        }
                    }
                }
            }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LightSlate),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Configure Device Role", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    viewModel.btManager.startReceiverServer()
                                    viewModel.triggerVibration(60, 200)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (btRole == BluetoothRole.RECEIVER) themeColor else Color.DarkGray
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("RECEIVER", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Dolphin Bridge Node", fontSize = 9.sp, color = Color.LightGray)
                                }
                            }

                            Button(
                                onClick = {
                                    viewModel.btManager.stopAll()
                                    viewModel.btManager.refreshPairedDevices()
                                    viewModel.triggerVibration(60, 200)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (btRole == BluetoothRole.SENDER) themeColor else Color.DarkGray
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("SENDER", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Handheld Controller", fontSize = 9.sp, color = Color.LightGray)
                                }
                            }
                        }

                        if (btRole != BluetoothRole.IDLE) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.btManager.stopAll() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed)
                            ) {
                                Text("Disconnect Session", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            if (btState != BtConnectionState.NONE) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LightSlate),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Bluetooth Link Telemetry", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TelemetryBox("State", btState.name, if (btState == BtConnectionState.CONNECTED) Color.Green else Color.Yellow)
                                TelemetryBox("Throughput", "$btFps Hz", themeColor)
                                TelemetryBox("Bytes Shared", "$bytesProcessed B", Color.LightGray)
                            }
                        }
                    }
                }
            }

            if (btRole == BluetoothRole.RECEIVER) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LightSlate.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Receiver Active", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Server is listening. Make sure the other device is configured as SENDER and connects to this device. Ensure local DSU Server is also started so incoming Bluetooth inputs are piped to your computer.",
                                fontSize = 12.sp, color = Color.LightGray
                            )
                        }
                    }
                }
            }

            if (btRole == BluetoothRole.SENDER) {
                item {
                    Text("Select Paired Device to Transmit To", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                if (pairedDevices.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = LightSlate.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("No Paired Devices Found", color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Pair both phones in Android Bluetooth Settings first, then re-open this tab.", color = Color.DarkGray, fontSize = 11.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                } else {
                    items(pairedDevices) { device ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = LightSlate.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.btManager.connectAsSender(device)
                                    viewModel.triggerVibration(40, 100)
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = themeColor, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        @SuppressLint("MissingPermission")
                                        val dName = device.name ?: "Unnamed Device"
                                        Text(dName, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(device.address, color = Color.Gray, fontSize = 11.sp)
                                    }
                                }
                                Icon(Icons.Default.Share, contentDescription = "Connect", tint = Color.LightGray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomizerTab(viewModel: WiiControllerViewModel, themeColor: Color) {
    val preset by viewModel.layoutPreset.collectAsStateWithLifecycle()
    val scale by viewModel.buttonScale.collectAsStateWithLifecycle()
    val themeColorName by viewModel.themeColor.collectAsStateWithLifecycle()
    val mappings by viewModel.buttonMappings.collectAsStateWithLifecycle()

    var showMappingDropdownFor by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Controller Theme Mode", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(10.dp))

                    val themesList = listOf("Wii Blue", "Carbon Grey", "Nintendo Red", "Teal Fusion")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        themesList.forEach { th ->
                            val isSelected = th == themeColorName
                            Button(
                                onClick = { viewModel.themeColor.value = th },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) themeColor else Color.DarkGray
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(th.split(" ").first(), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Controller Layout Orientation", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(10.dp))

                    val layoutsList = listOf("Classic Wii", "Horizontal Gamepad", "Big Buttons")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        layoutsList.forEach { lay ->
                            val isSelected = lay == preset
                            Button(
                                onClick = { viewModel.layoutPreset.value = lay },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) themeColor else Color.DarkGray
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(lay, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Tactile Button Size: ${scale}x", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value = scale,
                        onValueChange = { viewModel.buttonScale.value = (Math.round(it * 10f) / 10f).coerceIn(0.8f, 1.5f) },
                        valueRange = 0.8f..1.5f,
                        steps = 6,
                        colors = SliderDefaults.colors(
                            thumbColor = themeColor,
                            activeTrackColor = themeColor
                        )
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Button Remapping Engine", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Map dynamic targets for each physical button", fontSize = 11.sp, color = Color.Gray)
                        }
                        TextButton(
                            onClick = { viewModel.resetButtonMappings() },
                            colors = ButtonDefaults.textButtonColors(contentColor = themeColor)
                        ) {
                            Text("RESET MAPPINGS", fontSize = 9.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    val keys = listOf("A", "B", "MINUS", "PLUS", "HOME", "ONE", "TWO", "UP", "DOWN", "LEFT", "RIGHT")
                    val targets = listOf("A", "B", "MINUS", "PLUS", "HOME", "ONE", "TWO", "UP", "DOWN", "LEFT", "RIGHT", "SHAKE")

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.forEach { srcKey ->
                            val currentTgt = mappings[srcKey] ?: srcKey
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SlateBackground)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(themeColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(srcKey.take(2), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Button $srcKey", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }

                                Box {
                                    Button(
                                        onClick = { showMappingDropdownFor = srcKey },
                                        colors = ButtonDefaults.buttonColors(containerColor = LightSlate),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("➔ Target: $currentTgt", fontSize = 10.sp, color = Color.White)
                                    }

                                    DropdownMenu(
                                        expanded = showMappingDropdownFor == srcKey,
                                        onDismissRequest = { showMappingDropdownFor = null },
                                        modifier = Modifier.background(LightSlate)
                                    ) {
                                        targets.forEach { target ->
                                            DropdownMenuItem(
                                                text = { Text(target, color = Color.White, fontSize = 12.sp) },
                                                onClick = {
                                                    viewModel.updateButtonMapping(srcKey, target)
                                                    showMappingDropdownFor = null
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InstructionTab(themeColor: Color) {
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
                        color = themeColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    GuideStep("1", "Check Wireless LAN Environment", "Ensure both your Android device and the emulator host computer (PC/Mac/Linux) are on the exact same Wi-Fi subnet.")
                    GuideStep("2", "Start DSU input Server", "Tap 'Start DSU' on the 'Server Hub' Tab. Note your phone's Wi-Fi IP address shown in the status bar (e.g., 192.168.1.10).")
                    GuideStep("3", "Configure Dolphin Alternate Inputs", "Open Dolphin. Go to Options -> Controller Settings -> under 'Wii Remotes' choose 'Emulated Wii Remote' -> Click 'Configure' -> Click 'Alternate Input Sources' checkbox -> Enable -> Add Server and write your Phone's Wi-Fi IP. Press OK.")
                    GuideStep("4", "Map Controls & Calibrate Motion", "In Dolphin Controller mapping settings, choose 'DSU/0/Wii' as your active input device. Map your keys (A, B, D-pad, 1, 2). Under Motion Input, mapping layout will pick up accelerometer and gyroscope values dynamically from the continuous DSU stream!")
                    GuideStep("5", "Enable Real-time Speaker sound effects", "Deploy the 'dolphin_wiimote_sound_streamer.py' python script on your computer. Run:\n`python dolphin_wiimote_sound_streamer.py --ip YOUR_PHONE_IP` to route retro sound effects smoothly.")
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSlate),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Bluetooth Pairing & Bridgeless setup",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = themeColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    GuideStep("A", "System Bluetooth Pairing", "Pair Phone A and Phone B together in System Bluetooth Settings first of your Android devices.")
                    GuideStep("B", "Receiver Phone (Bridge Gateway)", "Start the DSU Server and speaker servers on Phone B. Switch Phone B to RECEIVER role on the Bluetooth Sync tab.")
                    GuideStep("C", "Sender Phone (Handheld)", "Switch Phone A to SENDER role on the Bluetooth Sync tab. Select Phone B from paired list. It will connect and stream motions/button state instantly.")
                    GuideStep("D", "Launch Emulator!", "Now, hold Phone A in your hand to aim, shake, or play games, while Phone B reports data seamlessly to your PC over local Wi-Fi. This creates an ultra-reliable connection that doesn't trigger Wi-Fi telemetry packet drops!")
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

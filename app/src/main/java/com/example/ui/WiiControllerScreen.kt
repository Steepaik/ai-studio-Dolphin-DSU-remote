package com.example.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.CrashReportEntity
import com.example.data.database.GameProfileEntity
import com.example.network.BluetoothRole
import com.example.network.BtConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Console Theme Color Palette
val ConsoleDark = Color(0xFF0B111E)
val CardDark = Color(0xFF16213E)
val ElectricBlue = Color(0xFF00D2FF)
val ActiveGreen = Color(0xFF00FF88)
val SoftGrey = Color(0xFFB0C4DE)
val ErrorCrimson = Color(0xFFFF4D4D)
val MagentaAccent = Color(0xFFFF007F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiiControllerScreen(viewModel: WiiControllerViewModel) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(0) } // 0 = Controller, 1 = Profiles, 2 = Settings

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = CardDark,
                tonalElevation = 12.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Wii Remote Gamepad") },
                    label = { Text("Gamepad", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricBlue,
                        selectedTextColor = ElectricBlue,
                        indicatorColor = ConsoleDark,
                        unselectedIconColor = SoftGrey,
                        unselectedTextColor = SoftGrey
                    ),
                    modifier = Modifier.testTag("nav_tab_controller")
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Game Profiles") },
                    label = { Text("Profiles", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricBlue,
                        selectedTextColor = ElectricBlue,
                        indicatorColor = ConsoleDark,
                        unselectedIconColor = SoftGrey,
                        unselectedTextColor = SoftGrey
                    ),
                    modifier = Modifier.testTag("nav_tab_profiles")
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Wii Connection Settings") },
                    label = { Text("Settings", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricBlue,
                        selectedTextColor = ElectricBlue,
                        indicatorColor = ConsoleDark,
                        unselectedIconColor = SoftGrey,
                        unselectedTextColor = SoftGrey
                    ),
                    modifier = Modifier.testTag("nav_tab_settings")
                )
            }
        },
        containerColor = ConsoleDark
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(ConsoleDark, Color(0xFF020617))
                    )
                )
        ) {
            when (activeTab) {
                0 -> ControllerTab(viewModel)
                1 -> ProfilesTab(viewModel)
                2 -> SettingsTab(viewModel)
            }
        }
    }
}

@Composable
fun ControllerTab(viewModel: WiiControllerViewModel) {
    val context = LocalContext.current
    val accel by viewModel.accelState.collectAsStateWithLifecycle()
    val gyro by viewModel.gyroState.collectAsStateWithLifecycle()
    val isDsu by viewModel.isDsuRunning.collectAsStateWithLifecycle()
    val btState by viewModel.btConnectionState.collectAsStateWithLifecycle()
    val btRole by viewModel.btRole.collectAsStateWithLifecycle()
    val isIrEnabled by viewModel.isIrModeEnabled.collectAsStateWithLifecycle()

    val pitch = -accel.second * 0.15f
    val roll = accel.first * 0.15f

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("controller_tab_root"),
        contentPadding = PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Nintendo Wii Role & Sync Hub Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, 
                    if (btState == BtConnectionState.CONNECTED) ActiveGreen.copy(alpha = 0.4f) else ElectricBlue.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header Row with glowing icon and connection state indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (btState == BtConnectionState.CONNECTED || isDsu) ActiveGreen 
                                        else if (btState == BtConnectionState.CONNECTING) ElectricBlue 
                                        else SoftGrey
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Wii Console Transmit System",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        
                        // Active Badge
                        Box(
                            modifier = Modifier
                                .background(
                                    if (btRole == BluetoothRole.RECEIVER) ActiveGreen.copy(alpha = 0.15f)
                                    else if (btRole == BluetoothRole.SENDER) ElectricBlue.copy(alpha = 0.15f)
                                    else Color.DarkGray.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = when (btRole) {
                                    BluetoothRole.RECEIVER -> "CONSOLE HOST"
                                    BluetoothRole.SENDER -> "WII REMOTE"
                                    BluetoothRole.HID_GAMEPAD -> "HID GAMEPAD"
                                    else -> "STANDALONE"
                                },
                                color = when (btRole) {
                                    BluetoothRole.RECEIVER -> ActiveGreen
                                    BluetoothRole.SENDER -> ElectricBlue
                                    BluetoothRole.HID_GAMEPAD -> MagentaAccent
                                    else -> SoftGrey
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // Segmented Selector Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ConsoleDark, RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = { 
                                viewModel.btManager.startReceiverServer() 
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (btRole == BluetoothRole.RECEIVER) CardDark else Color.Transparent,
                                contentColor = if (btRole == BluetoothRole.RECEIVER) ActiveGreen else SoftGrey
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Console Host", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { 
                                viewModel.btManager.startSenderMode() 
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (btRole == BluetoothRole.SENDER) CardDark else Color.Transparent,
                                contentColor = if (btRole == BluetoothRole.SENDER) ElectricBlue else SoftGrey
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("WiiMote Client", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { 
                                viewModel.btManager.startHidGamepadMode() 
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (btRole == BluetoothRole.HID_GAMEPAD) CardDark else Color.Transparent,
                                contentColor = if (btRole == BluetoothRole.HID_GAMEPAD) MagentaAccent else SoftGrey
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1.1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("HID Gamepad", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Mode Details Panel
                    when (btRole) {
                        BluetoothRole.RECEIVER -> {
                            // Wii Console Hub (Receiver) Details Panel
                            val isRun by viewModel.isDsuRunning.collectAsStateWithLifecycle()
                            val ipAddr by viewModel.ipAddress.collectAsStateWithLifecycle()
                            val clients by viewModel.btClientsList.collectAsStateWithLifecycle()
                            val clipboard = LocalClipboardManager.current
                            val syncUrl = "wiibt://02:00:00:00:00:00/1f8bd4b2-0382-4aa8-a53b-fde5bc63ee28"
                            
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("DSU Broadcast IP Endpoint", fontSize = 11.sp, color = SoftGrey)
                                        Text("$ipAddr:26760", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ActiveGreen)
                                    }
                                    
                                    Button(
                                        onClick = {
                                            if (isRun) viewModel.stopDsuServer() else viewModel.startDsuServer(26760)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isRun) ErrorCrimson else ElectricBlue
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (isRun) "STOP" else "START DSU", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ConsoleDark, RoundedCornerShape(8.dp))
                                        .clickable {
                                            clipboard.setText(AnnotatedString(syncUrl))
                                            Toast.makeText(context, "Copied connection metadata URL!", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Sync URL: $syncUrl",
                                        fontSize = 11.sp,
                                        color = ElectricBlue,
                                        maxLines = 1
                                    )
                                }

                                Text("Multiplayer Gamepad Slots:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(ActiveGreen))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Player P1: LOCAL SENSOR INPUT", fontSize = 11.sp, color = ActiveGreen)
                                    }

                                    for (slot in 2..4) {
                                        val cConnected = clients.find { it.contains("Slot $slot") }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (cConnected != null) ActiveGreen else Color.DarkGray)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = cConnected ?: "Player P$slot: [Awaiting Bluetooth sync controller client]",
                                                fontSize = 11.sp,
                                                color = if (cConnected != null) ActiveGreen else SoftGrey
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        BluetoothRole.SENDER -> {
                            // Wii Remote Client (Sender) Details Panel
                            val devList = viewModel.btManager.pairedDevices.collectAsStateWithLifecycle().value
                            val isRecon by viewModel.isReconnecting.collectAsStateWithLifecycle()
                            val reconAttempt by viewModel.reconnectAttempt.collectAsStateWithLifecycle()
                            
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (isRecon) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(ErrorCrimson.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = "Reconnecting to Host... (Attempt $reconAttempt/10)",
                                            fontSize = 11.sp,
                                            color = ErrorCrimson,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (btState == BtConnectionState.CONNECTED) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(ConsoleDark, RoundedCornerShape(8.dp))
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Active RFCOMM Sync Status", fontSize = 10.sp, color = SoftGrey)
                                            Text("Connected to Host Hub", fontSize = 12.sp, color = ActiveGreen, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = { viewModel.btManager.stopAll() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("DISCONNECT", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Text("Select a Console Host to sync sensors with:", fontSize = 11.sp, color = SoftGrey)
                                    if (devList.isEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(ConsoleDark, RoundedCornerShape(8.dp))
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorCrimson, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("No bonded devices. Pair first in Android bluetooth settings.", fontSize = 10.sp, color = ErrorCrimson)
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier.heightIn(max = 120.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            devList.forEach { dev ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(ConsoleDark, RoundedCornerShape(8.dp))
                                                        .clickable { viewModel.btManager.connectAsSender(dev) }
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Person, contentDescription = null, tint = SoftGrey, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(dev.name ?: "Unnamed Device", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                                    }
                                                    Text("SYNC LINK", fontSize = 10.sp, color = ElectricBlue, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        BluetoothRole.HID_GAMEPAD -> {
                            // emulated Bluetooth HID Gamepad Details Panel
                            val isRegistered = viewModel.btManager.isHidRegistered
                            val connectedDeviceName = viewModel.btManager.connectedDeviceName.collectAsStateWithLifecycle().value
                            
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MagentaAccent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Emulated Bluetooth HID Controller Mode",
                                            fontSize = 11.sp,
                                            color = MagentaAccent,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Transforms your phone into a real Bluetooth gamepad. Connects cleanly to any PC/Mac/Phone/Tablet/TV without any recipient client apps!",
                                            fontSize = 10.sp,
                                            color = SoftGrey
                                        )
                                    }
                                }

                                if (btState == BtConnectionState.CONNECTED && connectedDeviceName != null) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(ConsoleDark, RoundedCornerShape(8.dp))
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Active Bluetooth HID Link", fontSize = 10.sp, color = SoftGrey)
                                            Text("Connected to: $connectedDeviceName", fontSize = 12.sp, color = MagentaAccent, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = { viewModel.btManager.stopAll() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("DISCONNECT", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("How to pair & connect:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("1. Open Bluetooth Settings on your target device (PC, iPad, TV, etc.)", fontSize = 10.sp, color = SoftGrey)
                                        Text("2. Search for and pair with this Android device", fontSize = 10.sp, color = SoftGrey)
                                        Text("3. Once pairing is established, it will connect as a standard emulated controller!", fontSize = 10.sp, color = SoftGrey)
                                        
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Physical Motion Dynamics mapping:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("• Shake detection acts as a dedicated controller button", fontSize = 10.sp, color = SoftGrey)
                                        Text("• Accelerometer / Tilting controls secondary raw joysticks", fontSize = 10.sp, color = SoftGrey)
                                        Text("• Nunchuk Joystick controls the left stick axes cleanly", fontSize = 10.sp, color = SoftGrey)
                                    }
                                }
                            }
                        }

                        else -> {
                            // Standalone / Offline Controller Mode
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            ) {
                                Text("No Active Multiplayer Link Synced", fontSize = 12.sp, color = SoftGrey)
                                Text("Choose Console Host or WiiMote Client mode above to wire several gamepads.", fontSize = 11.sp, color = SoftGrey.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    
                    // Force alignment button and calibrate option at bottom
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.btManager.stopAll() },
                            colors = ButtonDefaults.textButtonColors(contentColor = SoftGrey)
                        ) {
                            Text("Reset Wireless Sockets", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { viewModel.calibrateMotionPlus() },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Calibrate Gyro", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            // Visualizer Panel (Perspective Rotating 3D wireframe Cube based on pitch & roll!)
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "3D Gyro Perspective Preview",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricBlue
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(ConsoleDark)
                            .border(1.dp, ElectricBlue.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        GyroPerspectiveCube(pitch = pitch, roll = roll, modifier = Modifier.fillMaxSize())
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ACCEL PITCH", fontSize = 10.sp, color = SoftGrey)
                            Text(String.format("%.2f°", pitch * 57.29), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ACCEL ROLL", fontSize = 10.sp, color = SoftGrey)
                            Text(String.format("%.2f°", roll * 57.29), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("GYRO Z", fontSize = 10.sp, color = SoftGrey)
                            Text(String.format("%.2f rad/s", gyro.third), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            // Wii Mote Body Representation
            WiiMoteMockupLayout(viewModel)
        }

        item {
            // Dolphin Native Application Shortcut
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val success = viewModel.launchDolphinApp(context)
                        if (!success) {
                            Toast
                                .makeText(
                                    context,
                                    "Is Dolphin installed? Launched intent triggers not found.",
                                    Toast.LENGTH_SHORT
                                )
                                .show()
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = ActiveGreen,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Connect Android Dolphin", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Tap to quickly open default Dolphin app on this phone", fontSize = 11.sp, color = SoftGrey)
                    }
                }
            }
        }
    }
}

@Composable
fun GyroPerspectiveCube(pitch: Float, roll: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val scale = 50f

        // 3D coordinates for Cube vertices
        val boxPoints = arrayOf(
            floatArrayOf(-1f, -1f, -1f),
            floatArrayOf(1f, -1f, -1f),
            floatArrayOf(1f, 1f, -1f),
            floatArrayOf(-1f, 1f, -1f),
            floatArrayOf(-1f, -1f, 1f),
            floatArrayOf(1f, -1f, 1f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(-1f, 1f, 1f)
        )

        val cp = cos(pitch)
        val sp = sin(pitch)
        val cr = cos(roll)
        val sr = sin(roll)

        val projPoints = boxPoints.map { pt ->
            val x = pt[0]
            val y = pt[1]
            val z = pt[2]

            // Rotate along pitch axis (X axis representation)
            val rY1 = y * cp - z * sp
            val rZ1 = y * sp + z * cp

            // Rotate along roll axis (Z/Y representation)
            val rX2 = x * cr + rZ1 * sr
            val rZ2 = -x * sr + rZ1 * cr

            // 3D point perspective layout
            val cameraDist = 3.5f
            val calculatedX = cx + (rX2 * scale) / (1f + (rZ2 / cameraDist))
            val calculatedY = cy + (rY1 * scale) / (1f + (rZ2 / cameraDist))
            Offset(calculatedX, calculatedY)
        }

        val indices = arrayOf(
            Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 0), // back Face
            Pair(4, 5), Pair(5, 6), Pair(6, 7), Pair(7, 4), // front Face
            Pair(0, 4), Pair(1, 5), Pair(2, 6), Pair(3, 7)  // edges of perspective
        )

        indices.forEach { edge ->
            drawLine(
                color = ElectricBlue,
                start = projPoints[edge.first],
                end = projPoints[edge.second],
                strokeWidth = 3.dp.toPx()
            )
        }
    }
}

@Composable
fun WiiMoteMockupLayout(viewModel: WiiControllerViewModel) {
    val isNunchuck by viewModel.isNunchuckEnabled.collectAsStateWithLifecycle()
    val isIr by viewModel.isIrModeEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark, shape = RoundedCornerShape(24.dp))
            .border(1.dp, SoftGrey.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tactile Simulation Panel", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNunchuck) {
                    Box(modifier = Modifier.background(ElectricBlue.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("NUNCHUCK", fontSize = 9.sp, color = ElectricBlue, fontWeight = FontWeight.Bold)
                    }
                }
                if (isIr) {
                    Box(modifier = Modifier.background(ActiveGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("IR ON", fontSize = 9.sp, color = ActiveGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Custom Analog stick container (simulate Nunchuck stick details or primary stick)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Stick (Deadzone Applied)", fontSize = 10.sp, color = SoftGrey)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(ConsoleDark)
                    .border(2.dp, SoftGrey, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                var stickOffsetX by remember { mutableStateOf(0f) }
                var stickOffsetY by remember { mutableStateOf(0f) }

                Box(
                    modifier = Modifier
                        .offset(stickOffsetX.dp, stickOffsetY.dp)
                        .size(48.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(ElectricBlue)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = {
                                    stickOffsetX = 0f
                                    stickOffsetY = 0f
                                    viewModel.onStickMoved(0f, 0f)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                stickOffsetX = (stickOffsetX + dragAmount.x / 3f).coerceIn(-40f, 40f)
                                stickOffsetY = (stickOffsetY + dragAmount.y / 3f).coerceIn(-40f, 40f)
                                viewModel.onStickMoved(stickOffsetX / 40f, -stickOffsetY / 40f)
                            }
                        }
                )
            }
        }

        // Standard Wii Controller Button Matrix Layout (Vertical Stack representation)
        Column(
            modifier = Modifier.width(180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // D-PAD Block
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(ConsoleDark, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Cross Arrows
                TactileButton(modifier = Modifier.align(Alignment.TopCenter).size(28.dp), tag = "UP", text = "▲", viewModel = viewModel)
                TactileButton(modifier = Modifier.align(Alignment.BottomCenter).size(28.dp), tag = "DOWN", text = "▼", viewModel = viewModel)
                TactileButton(modifier = Modifier.align(Alignment.CenterStart).size(28.dp), tag = "LEFT", text = "◀", viewModel = viewModel)
                TactileButton(modifier = Modifier.align(Alignment.CenterEnd).size(28.dp), tag = "RIGHT", text = "▶", viewModel = viewModel)
            }

            // Big Blue 'A' Button
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(ElectricBlue)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { viewModel.onButtonPressed("A", false) }
                        ) { change, _ ->
                            change.consume()
                            viewModel.onButtonPressed("A", true)
                        }
                    }
                    .testTag("button_a_simulation"),
                contentAlignment = Alignment.Center
            ) {
                Text("A", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            // Trigger 'B' Button (rendered beneath or as a simple long selector button)
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { viewModel.onButtonPressed("B", false) }
                        ) { change, _ ->
                            change.consume()
                            viewModel.onButtonPressed("B", true)
                        }
                    }
                    .testTag("button_b_simulation")
            ) {
                Text("B (TRIGGER)", color = Color.White, fontWeight = FontWeight.Bold)
            }

            // Numeric layout row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TactileButton(modifier = Modifier.size(44.dp), tag = "MINUS", text = "—", viewModel = viewModel)
                TactileButton(modifier = Modifier.size(44.dp), tag = "HOME", text = "⌂", viewModel = viewModel)
                TactileButton(modifier = Modifier.size(44.dp), tag = "PLUS", text = "＋", viewModel = viewModel)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TactileButton(modifier = Modifier.size(48.dp), tag = "ONE", text = "1", viewModel = viewModel)
                TactileButton(modifier = Modifier.size(48.dp), tag = "TWO", text = "2", viewModel = viewModel)
            }

            // Custom Action sound triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { viewModel.playSyntheticSound(1) },
                    colors = ButtonDefaults.buttonColors(containerColor = CardDark),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sound 1", fontSize = 10.sp, maxLines = 1)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = { viewModel.playSyntheticSound(2) },
                    colors = ButtonDefaults.buttonColors(containerColor = CardDark),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sound 2", fontSize = 10.sp, maxLines = 1)
                }
            }

            // Physical Shake motion simulator button
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = ErrorCrimson),
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { viewModel.onButtonPressed("SHAKE", false) }
                        ) { change, _ ->
                            change.consume()
                            viewModel.onButtonPressed("SHAKE", true)
                        }
                    }
                    .testTag("shake_simulation_button")
            ) {
                Text("SIMULATE SHAKE", fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun TactileButton(modifier: Modifier, tag: String, text: String, viewModel: WiiControllerViewModel) {
    Box(
        modifier = modifier
            .background(Color.Gray, shape = CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { viewModel.onButtonPressed(tag, false) }
                ) { change, _ ->
                    change.consume()
                    viewModel.onButtonPressed(tag, true)
                }
            }
            .testTag("btn_${tag.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun ProfilesTab(viewModel: WiiControllerViewModel) {
    val profiles by viewModel.gameProfiles.collectAsStateWithLifecycle()
    var isInserting by remember { mutableStateOf(false) }

    var pName by remember { mutableStateOf("") }
    var pSx by remember { mutableStateOf(1.0f) }
    var pSy by remember { mutableStateOf(1.0f) }
    var pSz by remember { mutableStateOf(1.0f) }
    var pDz by remember { mutableStateOf(0.05f) }
    var pSt by remember { mutableStateOf(1.5f) }
    var pIr by remember { mutableStateOf(false) }
    var pVol by remember { mutableStateOf(100f) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("profiles_tab_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Wii Console Profiles", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Switch or build dynamic custom game sensitivity calibrations", fontSize = 12.sp, color = SoftGrey)
        }

        if (isInserting) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, ElectricBlue, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add Custom Mapping", fontWeight = FontWeight.Bold, color = Color.White)
                        OutlinedTextField(
                            value = pName,
                            onValueChange = { pName = it },
                            label = { Text("Profile Name") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = ElectricBlue,
                                unfocusedBorderColor = SoftGrey
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("profile_name_input")
                        )

                        Text("Sensitivity Multipliers", fontSize = 12.sp, color = SoftGrey, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("X Axis: ${String.format("%.1f×", pSx)}", fontSize = 10.sp, color = SoftGrey)
                                Slider(value = pSx, onValueChange = { pSx = it }, valueRange = 0.2f..3.0f)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Y Axis: ${String.format("%.1f×", pSy)}", fontSize = 10.sp, color = SoftGrey)
                                Slider(value = pSy, onValueChange = { pSy = it }, valueRange = 0.2f..3.0f)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Z Axis: ${String.format("%.1f×", pSz)}", fontSize = 10.sp, color = SoftGrey)
                                Slider(value = pSz, onValueChange = { pSz = it }, valueRange = 0.2f..3.0f)
                            }
                        }

                        Text("Stick Deadzone: ${String.format("%.0f%%", pDz * 100f)}", fontSize = 11.sp, color = Color.White)
                        Slider(value = pDz, onValueChange = { pDz = it }, valueRange = 0.0f..0.20f)

                        Text("Shake Threshold: ${String.format("%.1f G", pSt)}", fontSize = 11.sp, color = Color.White)
                        Slider(value = pSt, onValueChange = { pSt = it }, valueRange = 0.5f..3.0f)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = pIr, onCheckedChange = { pIr = it })
                            Text("IR Pointer Emulation", color = SoftGrey, fontSize = 12.sp)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { isInserting = false }) {
                                Text("Cancel", color = Color.LightGray)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (pName.isNotBlank()) {
                                        viewModel.saveProfile(pName, pSx, pSy, pSz, pDz, pSt, pIr, pVol)
                                        pName = ""
                                        isInserting = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                modifier = Modifier.testTag("profile_save_button")
                            ) {
                                Text("Save Profile")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Button(
                    onClick = { isInserting = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    modifier = Modifier.fillMaxWidth().testTag("add_profile_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Custom Configuration")
                }
            }
        }

        items(profiles) { profile ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (profile.isBuiltIn) ElectricBlue.copy(alpha = 0.5f) else SoftGrey.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            if (profile.isBuiltIn) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(modifier = Modifier.background(ElectricBlue.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                    Text("Preset", fontSize = 8.sp, color = ElectricBlue, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (!profile.isBuiltIn) {
                            IconButton(onClick = { viewModel.deleteProfile(profile.name) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove setting", tint = ErrorCrimson)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SensX (Axis X): ${profile.sensX}× | Stick Deadzone: ${String.format("%.0f%%", profile.deadzone * 100f)} | ShakeG: ${profile.shakeThreshold}G",
                        fontSize = 11.sp,
                        color = SoftGrey
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.applyProfile(profile) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (profile.isBuiltIn) ElectricBlue else Color.DarkGray),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply Calibration Setup", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(viewModel: WiiControllerViewModel) {
    val context = LocalContext.current
    var expandedSection by remember { mutableStateOf(-1) } // 0=DSU Server, 1=Sensor Controls, 2=Audio Speaker, 3=Bluetooth Sync, 4=Setup Onboarding, 5=Diagnostics Vault

    Text(
        text = "Wii Mote Settings",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.padding(16.dp).statusBarsPadding()
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_tab_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // SECTION 1: DSU SERVER & LOCAL NETWORKING
        item {
            ConfigHeaderItem(
                title = "DSU Server & Networking Connection",
                subtitle = "Manage ports, DSU server, IP metrics and DNS states",
                id = 0,
                expandedId = expandedSection
            ) { expandedSection = if (expandedSection == 0) -1 else 0 }
            
            if (expandedSection == 0) {
                DsuServerConfigBlock(viewModel)
            }
        }

        // SECTION 2: PHYSICAL GYRO ACCELEROMETER CONTROLS
        item {
            ConfigHeaderItem(
                title = "Live Motion & Sensor Controls",
                subtitle = "Smoothness, per-axis multipliers, shake parameters",
                id = 1,
                expandedId = expandedSection
            ) { expandedSection = if (expandedSection == 1) -1 else 1 }

            if (expandedSection == 1) {
                SensorConfigBlock(viewModel)
            }
        }

        // SECTION 3: AUDIO RECEIVER SPEAKER
        item {
            ConfigHeaderItem(
                title = "Wii Controller Speaker Audio",
                subtitle = "Jitter buffers, volume multipliers, wave audio visualization",
                id = 2,
                expandedId = expandedSection
            ) { expandedSection = if (expandedSection == 2) -1 else 2 }

            if (expandedSection == 2) {
                AudioConfigBlock(viewModel)
            }
        }

        // SECTION 4: BLUETOOTH MULTIPLAYER INTERACTION
        item {
            ConfigHeaderItem(
                title = "Multiplayer Bluetooth Hub",
                subtitle = "Sync several phones classic sockets for four player splits",
                id = 3,
                expandedId = expandedSection
            ) { expandedSection = if (expandedSection == 3) -1 else 3 }

            if (expandedSection == 3) {
                BluetoothConfigBlock(viewModel)
            }
        }

        // SECTION 5: dolphin setup onboarding guide
        item {
            ConfigHeaderItem(
                title = "Setup Onboarding Wizard",
                subtitle = "Step-by-step controller mapping steps",
                id = 4,
                expandedId = expandedSection
            ) { expandedSection = if (expandedSection == 4) -1 else 4 }

            if (expandedSection == 4) {
                SetupWizardConfigBlock(viewModel)
            }
        }

        // SECTION 6: CRASH LOGGER REPORT
        item {
            ConfigHeaderItem(
                title = "Crash Logger Vault Diagnostics",
                subtitle = "Last unhandled stack traces and recovery audits",
                id = 5,
                expandedId = expandedSection
            ) { expandedSection = if (expandedSection == 5) -1 else 5 }

            if (expandedSection == 5) {
                CrashDiagnosticsConfigBlock(viewModel)
            }
        }
    }
}

@Composable
fun ConfigHeaderItem(title: String, subtitle: String, id: Int, expandedId: Int, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1.getFloat())) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, fontSize = 11.sp, color = SoftGrey)
            }
            Icon(
                imageVector = if (expandedId == id) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = ElectricBlue
            )
        }
    }
}

fun Int.getFloat(): Float = this.toFloat()

@Composable
fun DsuServerConfigBlock(viewModel: WiiControllerViewModel) {
    val isRunning by viewModel.isDsuRunning.collectAsStateWithLifecycle()
    val isNsd by viewModel.isNsdDiscoverable.collectAsStateWithLifecycle()
    val clients by viewModel.registeredClients.collectAsStateWithLifecycle()
    val fps by viewModel.dsuFps.collectAsStateWithLifecycle()
    val ipAddr by viewModel.ipAddress.collectAsStateWithLifecycle()

    var customDsuPort by remember { mutableStateOf("26760") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DSU Endpoint Host: $ipAddr (Wildcard binds ::)", color = ActiveGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = customDsuPort,
            onValueChange = { customDsuPort = it },
            label = { Text("UDP Handshake socket port") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                focusedLabelColor = ElectricBlue,
                unfocusedBorderColor = SoftGrey
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Cemuhook mDNS Advertising", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Broadcasts UDP endpoint to Dolphin instantly", fontSize = 10.sp, color = SoftGrey)
            }
            Switch(checked = isNsd, onCheckedChange = { viewModel.setNsdEnabled(it) })
        }

        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = {
                val pInt = customDsuPort.toIntOrNull() ?: 26760
                if (isRunning) {
                    viewModel.stopDsuServer()
                } else {
                    viewModel.startDsuServer(pInt)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) ErrorCrimson else ElectricBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning) "STOP DSU SERVER" else "RUN DSU BACKGROUND INSTANCE")
        }

        if (isRunning) {
            Card(colors = CardDefaults.cardColors(containerColor = ConsoleDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Live Broadcaster Metrics:", color = ElectricBlue, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Broadcast Stream FPS: $fps Hz (Adaptive throttled)", fontSize = 11.sp, color = Color.White)
                    Text("Subscriber Subscriber Slots: ${clients.size}/4 IP Clients", fontSize = 11.sp, color = Color.White)
                    if (clients.isNotEmpty()) {
                        clients.forEach {
                            Text(" ➔ Subscriber Client ID: $it", fontSize = 10.sp, color = ActiveGreen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SensorConfigBlock(viewModel: WiiControllerViewModel) {
    val perAxis by viewModel.perAxisMode.collectAsStateWithLifecycle()
    val sX by viewModel.sensX.collectAsStateWithLifecycle()
    val sY by viewModel.sensY.collectAsStateWithLifecycle()
    val sZ by viewModel.sensZ.collectAsStateWithLifecycle()
    val smoothing by viewModel.motionSmoothing.collectAsStateWithLifecycle()
    val dz by viewModel.analogDeadzone.collectAsStateWithLifecycle()
    val st by viewModel.shakeThreshold.collectAsStateWithLifecycle()
    val testShakeState by viewModel.isShakeTestDetected.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Per-Axis Sensitivity Mode", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Customize sensitivity modifiers uniquely on axes", fontSize = 10.sp, color = SoftGrey)
            }
            Switch(checked = perAxis, onCheckedChange = { viewModel.perAxisMode.value = it })
        }

        if (perAxis) {
            Column {
                Text("Gyro sensitivity multiplier X: ${String.format("%.1f×", sX)}", fontSize = 11.sp, color = Color.White)
                Slider(value = sX, onValueChange = { viewModel.sensX.value = it }, valueRange = 0.2f..3.0f)

                Text("Gyro sensitivity multiplier Y: ${String.format("%.1f×", sY)}", fontSize = 11.sp, color = Color.White)
                Slider(value = sY, onValueChange = { viewModel.sensY.value = it }, valueRange = 0.2f..3.0f)

                Text("Gyro sensitivity multiplier Z: ${String.format("%.1f×", sZ)}", fontSize = 11.sp, color = Color.White)
                Slider(value = sZ, onValueChange = { viewModel.sensZ.value = it }, valueRange = 0.2f..3.0f)
            }
        } else {
            Column {
                Text("Universal Gyro Sensitivity: ${String.format("%.1f×", sX)}", fontSize = 11.sp, color = Color.White)
                Slider(
                    value = sX,
                    onValueChange = {
                        viewModel.sensX.value = it
                        viewModel.sensY.value = it
                        viewModel.sensZ.value = it
                    },
                    valueRange = 0.2f..3.0f
                )
            }
        }

        Column {
            Text("Motion Smoothing filter (LPF coefficient): ${String.format("%.0f%%", smoothing * 100f)}", fontSize = 11.sp, color = Color.White)
            Slider(value = smoothing, onValueChange = { viewModel.motionSmoothing.value = it }, valueRange = 0.1f..1.0f)
        }

        Column {
            Text("Stick Deadzone: ${String.format("%.0f%%", dz * 100f)}", fontSize = 11.sp, color = Color.White)
            Slider(value = dz, onValueChange = { viewModel.analogDeadzone.value = it }, valueRange = 0.0f..0.20f)
        }

        Column {
            Text("Shake detection trigger sensitivity: ${String.format("%.1f G", st)}", fontSize = 11.sp, color = Color.White)
            Slider(value = st, onValueChange = { viewModel.shakeThreshold.value = it }, valueRange = 0.5f..3.0f)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (testShakeState) ActiveGreen else CardDark, shape = RoundedCornerShape(8.dp))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (testShakeState) "SHAKE DETECTED!" else "Test Shake",
                    color = if (testShakeState) ConsoleDark else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = { viewModel.calibrateMotionPlus() },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Recalibrate Sensor", fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun AudioConfigBlock(viewModel: WiiControllerViewModel) {
    val isRunning by viewModel.isAudioRunning.collectAsStateWithLifecycle()
    val trackingBytes by viewModel.audioBytesReceived.collectAsStateWithLifecycle()
    val status by viewModel.audioStatusString.collectAsStateWithLifecycle()
    val waveState by viewModel.audioWaveform.collectAsStateWithLifecycle()
    val vol by viewModel.currentVolume.collectAsStateWithLifecycle()

    var audioPortInput by remember { mutableStateOf("26761") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = audioPortInput,
            onValueChange = { audioPortInput = it },
            label = { Text("Audio UDP broadcast port") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                focusedBorderColor = ElectricBlue
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Audio Stream Volume: ${vol.toInt()}%", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Asymmetric audio amplifier multiplier scale", fontSize = 10.sp, color = SoftGrey)
            }
        }
        Slider(value = vol, onValueChange = { viewModel.currentVolume.value = it }, valueRange = 0f..150f)

        Button(
            onClick = {
                val pInt = audioPortInput.toIntOrNull() ?: 26761
                viewModel.toggleAudioServer(pInt)
            },
            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) ErrorCrimson else ElectricBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning) "STOP AUDIO STREAM SPEAKER" else "INITIALIZE AUDIO RECEIVE STREAM")
        }

        if (isRunning) {
            Card(colors = CardDefaults.cardColors(containerColor = ConsoleDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Active Speaker Stream Diagnostic Details:", color = ElectricBlue, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Stream State: $status", fontSize = 11.sp, color = Color.White)
                    Text("Received sound chunks: ${trackingBytes / 1024L} KB", fontSize = 11.sp, color = Color.White)

                    Text("Oscilloscope Audios Waveform Visuals:", color = SoftGrey, fontSize = 10.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(CardDark, RoundedCornerShape(4.dp))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val step = w / waveState.size.getFloat()
                            
                            for (i in 0 until waveState.size - 1) {
                                val sX = i.getFloat() * step
                                val sY = h / 2f + (waveState[i] * (h / 2f))
                                val eX = (i + 1).getFloat() * step
                                val eY = h / 2f + (waveState[i + 1] * (h / 2f))

                                drawLine(
                                    color = ElectricBlue,
                                    start = Offset(sX, sY),
                                    end = Offset(eX, eY),
                                    strokeWidth = 2f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothConfigBlock(viewModel: WiiControllerViewModel) {
    val btState by viewModel.btConnectionState.collectAsStateWithLifecycle()
    val btRole by viewModel.btRole.collectAsStateWithLifecycle()
    val connectedName by viewModel.btDeviceName.collectAsStateWithLifecycle()
    val clientsList by viewModel.btClientsList.collectAsStateWithLifecycle()
    val reconnectAttempt by viewModel.reconnectAttempt.collectAsStateWithLifecycle()
    val isReconnecting by viewModel.isReconnecting.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val service = viewModel.isServiceBound.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Multiplayer Classic RFCOMM Sync", color = ElectricBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.btManager.startReceiverServer()
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (btRole == BluetoothRole.RECEIVER) ActiveGreen else Color.DarkGray),
                modifier = Modifier.weight(1f)
            ) {
                Text("HOST BRIDGE", fontSize = 10.sp)
            }

            Button(
                onClick = {
                    viewModel.btManager.startSenderMode()
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (btRole == BluetoothRole.SENDER) ActiveGreen else Color.DarkGray),
                modifier = Modifier.weight(1f)
            ) {
                Text("ACT AS CLIENT", fontSize = 10.sp)
            }
        }

        if (isReconnecting) {
            Box(modifier = Modifier.fillMaxWidth().background(ErrorCrimson.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text("Reconnecting to host Remote Receiver... (Attempt $reconnectAttempt/10)", fontSize = 11.sp, color = ErrorCrimson, fontWeight = FontWeight.Bold)
            }
        }

        if (btRole == BluetoothRole.RECEIVER) {
            Card(colors = CardDefaults.cardColors(containerColor = ConsoleDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local QR pairing code MAC metadata info string:", color = SoftGrey, fontSize = 11.sp)
                    val wifiMac = "02:00:00:00:00:00"
                    val uuid = "1f8bd4b2-0382-4aa8-a53b-fde5bc63ee28"
                    val syncUrl = "wiibt://$wifiMac/$uuid"
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            clipboard.setText(AnnotatedString(syncUrl))
                            Toast.makeText(context, "Copied connection metadata URL!", Toast.LENGTH_SHORT).show()
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                        Text(syncUrl, fontSize = 11.sp, color = ElectricBlue)
                    }

                    Text("Active Multiplayer Player Slots (Limit 3 Client Senders):", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Slot P1: (LOCAL PHYSICAL PHONE)", fontSize = 11.sp, color = ActiveGreen)
                    for (slot in 2..4) {
                        val cConnected = clientsList.find { it.contains("Slot $slot") }
                        if (cConnected != null) {
                            Text(cConnected, fontSize = 11.sp, color = ActiveGreen)
                        } else {
                            Text("Slot P$slot: [Empty, awaiting client Bluetooth connection]", fontSize = 11.sp, color = SoftGrey)
                        }
                    }
                }
            }
        }

        if (btRole == BluetoothRole.SENDER) {
            Text("Select HOST to bind and transmit inputs to:", fontSize = 12.sp, color = Color.White)
            val devList = viewModel.btManager.pairedDevices.collectAsStateWithLifecycle().value
            if (devList.isEmpty()) {
                Text("No bonded classic devices. Bond from system settings first.", fontSize = 11.sp, color = ErrorCrimson)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                    items(devList) { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.btManager.connectAsSender(dev) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(dev.name ?: "Bonded Bluetooth Dev", fontSize = 12.sp, color = Color.White)
                            Text("TAP TO SYNC", fontSize = 10.sp, color = ElectricBlue)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { viewModel.btManager.stopAll() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("STOP ALL BLUETOOTH")
        }
    }
}

@Composable
fun SetupWizardConfigBlock(viewModel: WiiControllerViewModel) {
    var stepIndex by remember { mutableStateOf(0) }
    val details = listOf(
        "Open Dolphin on your computer. Access Controllers settings under Configuration options.",
        "Add an alternate source controller on Slot 1. Pick 'Alternative Source Input' or select DSU Client.",
        "Enter DSU host IP address ${viewModel.ipAddress.collectAsStateWithLifecycle().value} with port 26760 manually, then click test connect.",
        "That's it! Your physical android sensors will translate seamlessly to Wii Motion Plus pointers in Dolphin!"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Wii Link Setup Assistant (Step ${stepIndex + 1}/4)", color = ActiveGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ConsoleDark, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(details[stepIndex], fontSize = 12.sp, color = Color.White)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = { if (stepIndex > 0) stepIndex-- },
                enabled = stepIndex > 0
            ) {
                Text("Previous")
            }

            TextButton(
                onClick = { if (stepIndex < 3) stepIndex++ },
                enabled = stepIndex < 3
            ) {
                Text("Next Step")
            }
        }
    }
}

@Composable
fun CrashDiagnosticsConfigBlock(viewModel: WiiControllerViewModel) {
    val logs by viewModel.crashReports.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Collected Crash Stack Diagnostics (${logs.size} reports):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            IconButton(onClick = { viewModel.clearCrashReports() }) {
                Icon(Icons.Default.Delete, contentDescription = "Wipe stack logs", tint = ErrorCrimson)
            }
        }

        if (logs.isEmpty()) {
            Text("No crashes logged yet. Running perfectly!", color = ActiveGreen, fontSize = 11.sp)
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                items(logs) { log ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ConsoleDark),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(log.exceptionMessage, color = ErrorCrimson, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text("Timestamp: ${log.timestamp}", color = SoftGrey, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

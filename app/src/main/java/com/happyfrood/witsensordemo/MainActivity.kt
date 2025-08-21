package com.happyfrood.witsensordemo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.happyfrood.witsensordemo.ui.theme.WitSensorDemoTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class SensorViewModel(private val witMotionService: WitMotionService) : ViewModel() {
    val isScanning = witMotionService.isScanning.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val isConnected = witMotionService.isConnected.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val deviceName = witMotionService.deviceName.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val imuData = witMotionService.imuData.stateIn(viewModelScope, SharingStarted.Lazily, ImuData())
    val errorMessage = witMotionService.errorMessage.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val timerData = witMotionService.timerData.stateIn(viewModelScope, SharingStarted.Lazily, TimerData())

    fun startScanAndConnect() { witMotionService.startScanAndConnect() }
    fun reset() { witMotionService.reset() }

    class Factory(private val service: WitMotionService) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SensorViewModel(service) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

class MainActivity : ComponentActivity() {
    private val witMotionService = WitMotionService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        witMotionService.initialize(this)
        setContent {
            WitSensorDemoTheme {
                val viewModel: SensorViewModel = remember {
                    ViewModelProvider(this, SensorViewModel.Factory(witMotionService))[SensorViewModel::class.java]
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionWrapper { MainScreen(viewModel) }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        witMotionService.disconnect()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(Unit) {
        if (!permissionState.allPermissionsGranted) {
            permissionState.launchMultiplePermissionRequest()
        }
    }

    if (permissionState.allPermissionsGranted) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Bluetooth permissions are required to use this app.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: SensorViewModel) {
    val isConnected by viewModel.isConnected.collectAsState()
    val imuData by viewModel.imuData.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val timerData by viewModel.timerData.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WitMotion Sensor Timing Test") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Timer Display
            TimerDisplay(timerData)

            Spacer(modifier = Modifier.height(16.dp))

            // Control Buttons
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { viewModel.startScanAndConnect() },
                    enabled = !timerData.isRunning && !isConnected
                ) {
                    Text("Start Test")
                }
                Button(
                    onClick = { viewModel.reset() },
                    enabled = !timerData.isRunning
                ) {
                    Text("Reset")
                }
            }

            // Error Display
            errorMessage?.let {
                Text(
                    it,
                    color = Color.Red,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sensor Data Display
            if (isConnected) {
                SensorDataDisplay(data = imuData)
            }
        }
    }
}

@Composable
fun TimerDisplay(timerData: TimerData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (timerData.firstDataTime != null)
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                "Connection Timing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Current elapsed time
            Text(
                "Total Elapsed: ${String.format("%.3f", timerData.currentElapsed)} seconds",
                style = MaterialTheme.typography.titleMedium,
                color = if (timerData.isRunning) Color.Blue else Color.Black
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Step timings
            TimingRow("Scan Started", timerData.scanStartTime)
            TimingRow("Device Found", timerData.deviceFoundTime)
            TimingRow("Connected", timerData.connectedTime)
            TimingRow("Configured", timerData.configuredTime)
            TimingRow("First Data", timerData.firstDataTime, isHighlight = true)

            // Total time to first data
            timerData.firstDataTime?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "⏱️ TIME TO FIRST DATA: ${String.format("%.3f", it)} seconds",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
fun TimingRow(label: String, time: Float?, isHighlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text = time?.let {
                String.format("%.3f s", it)
            } ?: "—",
            fontWeight = FontWeight.Bold,
            color = when {
                time == null -> Color.Gray
                isHighlight -> Color(0xFF4CAF50)
                else -> Color.Black
            }
        )
    }
}

private fun getOutputModeDescription(mode: String?): String {
    return when (mode) {
        "0" -> "Mode 0: Acc+Gyro+Angle"
        "1" -> "Mode 1: Displacement+Speed+Angle"
        "2" -> "Mode 2: Acc+Gyro+Timestamp"
        "3" -> "Mode 3: Displacement+Speed+Timestamp"
        else -> "Mode: ${mode ?: "Unknown"}"
    }
}

private fun isTimestampMode(mode: String?): Boolean {
    return mode == "2" || mode == "3"
}

@Composable
fun SensorDataDisplay(data: ImuData) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        // Output mode display
        if (data.outputMode != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isTimestampMode(data.outputMode))
                            Color(0xFF4CAF50).copy(alpha = 0.1f)
                        else Color(0xFF2196F3).copy(alpha = 0.1f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "✅ ${getOutputModeDescription(data.outputMode)}",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (isTimestampMode(data.outputMode)) {
                                "Timestamp mode: AngleX/Y contain timestamp data"
                            } else {
                                "Angle mode: AngleX/Y/Z contain rotation angles"
                            },
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        item { DataHeader("Acceleration (G)") }
        item { DataRow("Acc X", data.accX) }
        item { DataRow("Acc Y", data.accY) }
        item { DataRow("Acc Z", data.accZ) }

        item { DataHeader("Angular Velocity (°/s)") }
        item { DataRow("Gyro X", data.gyroX) }
        item { DataRow("Gyro Y", data.gyroY) }
        item { DataRow("Gyro Z", data.gyroZ) }

        if (isTimestampMode(data.outputMode)) {
            item { DataHeader("Timestamp Mode Data") }
            if (data.outputMode == "2") {
                item { DataRow("Heading (°)", data.angleZ) }
            }
            item { DataRow("Timestamp (ms)", data.timestamp) }
        } else {
            item { DataHeader("Angle (°)") }
            item { DataRow("Angle X", data.angleX) }
            item { DataRow("Angle Y", data.angleY) }
            item { DataRow("Angle Z", data.angleZ) }
            item { DataRow("Chip Time (ms)", data.timestamp) }
        }

        item { DataHeader("Quaternion") }
        item { DataRow("q0 (W)", data.q0) }
        item { DataRow("q1 (X)", data.q1) }
        item { DataRow("q2 (Y)", data.q2) }
        item { DataRow("q3 (Z)", data.q3) }

        item { DataHeader("Device Info") }
        item { DataRow("Temperature (°C)", data.temperature) }
        item { DataRow("Battery (%)", data.battery) }
    }
}

@Composable
fun DataHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun DataRow(label: String, value: Float?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Text(
            text = value?.let { String.format("%.3f", it) } ?: "N/A",
            fontWeight = FontWeight.Bold,
            color = if (value == null) Color.Gray else Color.Unspecified
        )
    }
}

@Composable
fun DataRow(label: String, value: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Text(
            text = value?.toString() ?: "N/A",
            fontWeight = FontWeight.Bold,
            color = if (value == null) Color.Gray else Color.Unspecified
        )
    }
}
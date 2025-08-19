package com.happyfrood.witsensordemo

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

    fun initialize(context: Context) { witMotionService.initialize(context) }
    fun startScan() { witMotionService.startScan() }
    fun stopScan() { witMotionService.stopScan() }
    fun disconnect() { witMotionService.disconnect() }
    fun setReturnRate(rate: Int) { witMotionService.setReturnRate(rate) }
    fun setBandwidth(bandwidth: Int) { witMotionService.setBandwidth(bandwidth) }
    fun setDataOutputMode(mode: Int) { witMotionService.setDataOutputMode(mode) }

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
                    PermissionWrapper { AppNavigator(viewModel) }
                }
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        witMotionService.disconnect()
    }
}

@Composable
fun AppNavigator(viewModel: SensorViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") { MainScreen(viewModel = viewModel, navController = navController) }
        composable("settings") { SettingsScreen(viewModel = viewModel, navController = navController) }
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
                Button(onClick = { permissionState.launchMultiplePermissionRequest() }) { Text("Grant Permissions") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: SensorViewModel, navController: NavController) {
    val isConnected by viewModel.isConnected.collectAsState()
    val imuData by viewModel.imuData.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WitMotion Sensor") },
                actions = {
                    if (isConnected) {
                        Button(onClick = { navController.navigate("settings") }) {
                            Text("Settings")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ConnectionStatus(viewModel = viewModel)
            errorMessage?.let {
                Text(it, color = Color.Red, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            SensorDataDisplay(data = imuData)
        }
    }
}

@Composable
fun ConnectionStatus(viewModel: SensorViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    isConnected -> "Connected to ${deviceName ?: "Unknown"}"
                    isScanning -> "Scanning for devices..."
                    else -> "Disconnected"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { viewModel.startScan() }, enabled = !isConnected) {
                    Text(if (isScanning) "Stop Scan" else "Start Scan")
                }
                Button(onClick = { viewModel.disconnect() }, enabled = isConnected) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
fun SensorDataDisplay(data: ImuData) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item { DataHeader("Acceleration (G)") }
        item { DataRow("Acc X", data.accX) }
        item { DataRow("Acc Y", data.accY) }
        item { DataRow("Acc Z", data.accZ) }

        item { DataHeader("Angular Velocity (°/s)") }
        item { DataRow("Gyro X", data.gyroX) }
        item { DataRow("Gyro Y", data.gyroY) }
        item { DataRow("Gyro Z", data.gyroZ) }

        // Conditionally display Angle or Timestamp
        if (data.timestamp != null) {
            item { DataHeader("Device Info") }
            item { DataRow("Chip Time (ms)", data.timestamp) }
        } else {
            item { DataHeader("Angle (°)") }
            item { DataRow("Angle X", data.angleX) }
            item { DataRow("Angle Y", data.angleY) }
            item { DataRow("Angle Z", data.angleZ) }
        }

//        item { DataHeader("Quaternion") }
//        item { DataRow("q0 (W)", data.q0) }
//        item { DataRow("q1 (X)", data.q1) }
//        item { DataRow("q2 (Y)", data.q2) }
//        item { DataRow("q3 (Z)", data.q3) }

        item { DataHeader("Device Info") }
        item { DataRow("Temperature (°C)", data.temperature)}
        item { DataRow("Battery (%)", data.battery) }
    }
}

@Composable
fun DataHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun DataRow(label: String, value: Float?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label)
        Text(text = value?.let { String.format("%.3f", it) } ?: "N/A", fontWeight = FontWeight.Bold, color = if (value == null) Color.Gray else Color.Unspecified)
    }
}

@Composable
fun DataRow(label: String, value: Long?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label)
        Text(text = value?.toString() ?: "N/A", fontWeight = FontWeight.Bold, color = if (value == null) Color.Gray else Color.Unspecified)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SensorViewModel, navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensor Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Return Rate (Hz)", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { viewModel.setReturnRate(10) }) { Text("10") }
                Button(onClick = { viewModel.setReturnRate(50) }) { Text("50") }
                Button(onClick = { viewModel.setReturnRate(100) }) { Text("100") }
                Button(onClick = { viewModel.setReturnRate(200) }) { Text("200") }
            }

            Text("Bandwidth (Hz Filter)", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { viewModel.setBandwidth(20) }) { Text("20") }
                Button(onClick = { viewModel.setBandwidth(42) }) { Text("42") }
                Button(onClick = { viewModel.setBandwidth(98) }) { Text("98") }
            }

            Text("Data Content Mode", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.setDataOutputMode(0) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mode 0: Accel, Gyro & Angle")
                }
                Button(onClick = { viewModel.setDataOutputMode(2) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mode 2: Accel, Gyro & Timestamp")
                }
            }
        }
    }
}
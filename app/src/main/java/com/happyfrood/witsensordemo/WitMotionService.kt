package com.happyfrood.witsensordemo

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.wit.witsdk.modular.sensor.device.exceptions.OpenDeviceException
import com.wit.witsdk.modular.sensor.example.ble5.Bwt901ble
import com.wit.witsdk.modular.sensor.example.ble5.interfaces.IBwt901bleRecordObserver
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.BluetoothBLE
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.WitBluetoothManager
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.exceptions.BluetoothBLEException
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.interfaces.IBluetoothFoundObserver
import com.wit.witsdk.modular.sensor.modular.processor.constant.WitSensorKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ImuData(
    val accX: Float? = null,
    val accY: Float? = null,
    val accZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val angleX: Float? = null,
    val angleY: Float? = null,
    val angleZ: Float? = null,
    val q0: Float? = null,
    val q1: Float? = null,
    val q2: Float? = null,
    val q3: Float? = null,
    val timestamp: Long? = null,
    val temperature: Float? = null,
    val battery: Float? = null
)

data class TimerData(
    val isRunning: Boolean = false,
    val currentElapsed: Float = 0f,
    val scanStartTime: Float? = null,
    val deviceFoundTime: Float? = null,
    val connectedTime: Float? = null,
    val configuredTime: Float? = null,
    val firstDataTime: Float? = null
)

@SuppressLint("MissingPermission")
class WitMotionService : IBluetoothFoundObserver, IBwt901bleRecordObserver {

    companion object {
        private const val TAG = "WitMotionService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var witBluetoothManager: WitBluetoothManager? = null
    private var connectedSensor: Bwt901ble? = null

    // Timer management
    private var timerJob: Job? = null
    private var startTimeMillis: Long = 0
    private var hasReceivedFirstData = false
    private var configurationComplete = false

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName = _deviceName.asStateFlow()
    private val _imuData = MutableStateFlow(ImuData())
    val imuData = _imuData.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _timerData = MutableStateFlow(TimerData())
    val timerData = _timerData.asStateFlow()

    fun initialize(context: Context) {
        try {
            WitBluetoothManager.initInstance(context)
            witBluetoothManager = WitBluetoothManager.getInstance()
        } catch (e: Exception) {
            _errorMessage.value = "SDK Initialization Failed: ${e.message}"
        }
    }

    fun startScanAndConnect() {
        if (_isScanning.value || _isConnected.value) return

        // Reset state
        hasReceivedFirstData = false
        configurationComplete = false
        _errorMessage.value = null

        // Start timer
        startTimeMillis = System.currentTimeMillis()
        _timerData.update {
            TimerData(
                isRunning = true,
                scanStartTime = 0f
            )
        }

        // Start timer updates
        timerJob = serviceScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
                _timerData.update { current ->
                    current.copy(currentElapsed = elapsed)
                }
                delay(10) // Update every 10ms
            }
        }

        // Start scanning
        serviceScope.launch {
            try {
                _isScanning.value = true
                witBluetoothManager?.registerObserver(this@WitMotionService)
                witBluetoothManager?.startDiscovery()
                Log.d(TAG, "Started scanning for devices")
            } catch (e: BluetoothBLEException) {
                _errorMessage.value = "Scan Error: ${e.message}"
                _isScanning.value = false
                stopTimer()
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerData.update { it.copy(isRunning = false) }
    }

    override fun onFoundBle(device: BluetoothBLE) {
        Log.d(TAG, "Device discovered: ${device.name} ")
        if (device.name?.startsWith("WT") == true) {
            // Record device found time
            val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
            _timerData.update { it.copy(deviceFoundTime = elapsed) }
            Log.d(TAG, "Found device: ${device.name} at ${elapsed}s")

            // Stop scanning and connect
            witBluetoothManager?.stopDiscovery()
            witBluetoothManager?.removeObserver(this@WitMotionService)
            _isScanning.value = false

            connectToDevice(device)
        }
    }

    override fun onFoundSPP(p0: com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.BluetoothSPP?) {}

    private fun connectToDevice(device: BluetoothBLE) {
        serviceScope.launch {
            try {
                val sensor = Bwt901ble(device)
                sensor.registerRecordObserver(this@WitMotionService)
                sensor.open()

                connectedSensor = sensor
                _isConnected.value = true
                _deviceName.value = sensor.deviceName

                // Record connected time
                val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
                _timerData.update { it.copy(connectedTime = elapsed) }
                Log.d(TAG, "Connected to device at ${elapsed}s")

                // Start configuration
                configureSensor()

            } catch (e: OpenDeviceException) {
                _errorMessage.value = "Connection Failed: ${e.message}"
                _isConnected.value = false
                stopTimer()
            }
        }
    }

    private fun configureSensor() {
        serviceScope.launch {
            connectedSensor?.let { sensor ->
                try {
                    Log.d(TAG, "Starting sensor configuration...")

                    // Unlock registers command: FF AA 69 88 B5
                    val unlockCommand = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69.toByte(), 0x88.toByte(), 0xB5.toByte())
                    sensor.sendProtocolData(unlockCommand, 50)
                    delay(200)
                    Log.d(TAG, "Unlocked registers")

                    // Set return rate to 200Hz (0x0B) via register 0x03: FF AA 03 0B 00
                    var command = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x03.toByte(), 0x0B.toByte(), 0x00.toByte())
                    sensor.sendProtocolData(command, 50)
                    delay(250)
                    Log.d(TAG, "Set return rate to 200Hz via register write")

                    // Set data output mode to 2 (acc + gyro + timestamp) via register 0x96: FF AA 96 02 00
                    command = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x96.toByte(), 0x02.toByte(), 0x00.toByte())
                    sensor.sendProtocolData(command, 50)
                    delay(200)
                    Log.d(TAG, "Set output mode to 2 (Acc+Gyro+Timestamp)")

                    // Set bandwidth to 256Hz (0x00) via register 0x1F: FF AA 1F 00 00
                    command = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x1F.toByte(), 0x00.toByte(), 0x00.toByte())
                    sensor.sendProtocolData(command, 50)
                    delay(200)
                    Log.d(TAG, "Set bandwidth to 256Hz")

                    // Save configuration command: FF AA 00 00 00
                    val saveCommand = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
                    sensor.sendProtocolData(saveCommand, 50)
                    delay(300)
                    Log.d(TAG, "Saved configuration")

                    // Read back return rate register (0x03) to verify: FF AA 27 03 00
                    val readRateCmd = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27.toByte(), 0x03.toByte(), 0x00.toByte())
                    sensor.sendProtocolData(readRateCmd, 50)
                    delay(100)
                    Log.d(TAG, "Sent command to read back return rate for verification")

                    configurationComplete = true

                    // Record configuration complete time
                    val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
                    _timerData.update { it.copy(configuredTime = elapsed) }
                    Log.d(TAG, "Configuration complete at ${elapsed}s")

                } catch (e: Exception) {
                    _errorMessage.value = "Configuration failed: ${e.message}"
                    Log.e(TAG, "Failed to configure sensor", e)
                }
            }
        }
    }

    override fun onRecord(sensor: Bwt901ble) {
        // Record first data time
        if (!hasReceivedFirstData && configurationComplete) {
            hasReceivedFirstData = true
            val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
            _timerData.update { it.copy(firstDataTime = elapsed) }
            Log.d(TAG, "🎯 FIRST DATA RECEIVED at ${elapsed}s")
            stopTimer()

            // Start reading quaternions periodically since they're not in the main data packet
            startQuaternionReading(sensor)
        }

        // Parse sensor data
        val accX = sensor.getDeviceData(WitSensorKey.AccX)?.toFloatOrNull()
        val accY = sensor.getDeviceData(WitSensorKey.AccY)?.toFloatOrNull()
        val accZ = sensor.getDeviceData(WitSensorKey.AccZ)?.toFloatOrNull()

        val gyroX = sensor.getDeviceData(WitSensorKey.AsX)?.toFloatOrNull()
        val gyroY = sensor.getDeviceData(WitSensorKey.AsY)?.toFloatOrNull()
        val gyroZ = sensor.getDeviceData(WitSensorKey.AsZ)?.toFloatOrNull()

        val angleX = sensor.getDeviceData(WitSensorKey.AngleX)?.toFloatOrNull()
        val angleY = sensor.getDeviceData(WitSensorKey.AngleY)?.toFloatOrNull()
        val angleZ = sensor.getDeviceData(WitSensorKey.AngleZ)?.toFloatOrNull()

        val q0 = sensor.getDeviceData(WitSensorKey.Q0)?.toFloatOrNull()
        val q1 = sensor.getDeviceData(WitSensorKey.Q1)?.toFloatOrNull()
        val q2 = sensor.getDeviceData(WitSensorKey.Q2)?.toFloatOrNull()
        val q3 = sensor.getDeviceData(WitSensorKey.Q3)?.toFloatOrNull()

        // The timestamp should be automatically parsed by the SDK when output mode 2 is active
        val timestamp = sensor.getDeviceData(WitSensorKey.ChipTime)?.toLongOrNull()

        val temperature = sensor.getDeviceData(WitSensorKey.T)?.toFloatOrNull()
        val battery = sensor.getDeviceData(WitSensorKey.ElectricQuantityPercentage)?.toFloatOrNull()

        _imuData.update {
            ImuData(
                accX = accX, accY = accY, accZ = accZ,
                gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
                angleX = angleX, angleY = angleY, angleZ = angleZ,
                q0 = q0, q1 = q1, q2 = q2, q3 = q3,
                timestamp = timestamp,
                temperature = temperature,
                battery = battery
            )
        }
    }

    private fun startQuaternionReading(sensor: Bwt901ble) {
        serviceScope.launch {
            // Request quaternion data periodically
            while (_isConnected.value) {
                try {
                    // Read quaternion register (0x51): FF AA 27 51 00
                    val readQuatCmd = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27.toByte(), 0x51.toByte(), 0x00.toByte())
                    sensor.sendProtocolData(readQuatCmd, 50)
                } catch (e: Exception) {
                    // Ignore errors in quaternion reading
                }
                delay(100) // Read quaternions every 100ms
            }
        }
    }

    fun disconnect() {
        serviceScope.launch {
            stopTimer()
            connectedSensor?.close()
            connectedSensor?.removeRecordObserver(this@WitMotionService)
            connectedSensor = null
            _isConnected.value = false
            _deviceName.value = null
            _imuData.value = ImuData()
        }
    }

    fun reset() {
        disconnect()
        _timerData.value = TimerData()
        _errorMessage.value = null
        hasReceivedFirstData = false
        configurationComplete = false
    }
}
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
    val battery: Float? = null,
    val outputMode: String? = null
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

    // Signal to indicate that GATT services are ready (i.e., first data packet received)
    private var gattReadySignal = CompletableDeferred<Unit>()

    private var timerJob: Job? = null
    private var startTimeMillis: Long = 0
    private var hasReceivedFirstData = false
    private var configurationComplete = false
    private var configuredOutputMode: String? = null

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
        configuredOutputMode = null
        _errorMessage.value = null

        // Start timer
        startTimeMillis = System.currentTimeMillis()
        _timerData.update {
            TimerData(isRunning = true, scanStartTime = 0f)
        }

        // Start timer updates
        timerJob = serviceScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
                _timerData.update { current ->
                    current.copy(currentElapsed = elapsed)
                }
                delay(10)
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
        Log.d(TAG, "Device discovered: ${device.name}")
        if (device.name?.startsWith("WT") == true) {
            val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
            _timerData.update { it.copy(deviceFoundTime = elapsed) }
            Log.d(TAG, "Found WT device: ${device.name} at ${elapsed}s")

            // Stop scanning and connect
            witBluetoothManager?.stopDiscovery()
            witBluetoothManager?.removeObserver(this@WitMotionService)

            connectToDevice(device)
        }
    }

    override fun onFoundSPP(p0: com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.BluetoothSPP?) {}

    private fun connectToDevice(device: BluetoothBLE) {
        serviceScope.launch {
            try {
                val sensor = Bwt901ble(device)
                sensor.registerRecordObserver(this@WitMotionService)

                // Re-initialize the signal for each new connection attempt
                gattReadySignal = CompletableDeferred()
                sensor.open()

                connectedSensor = sensor
                _isConnected.value = true
                _deviceName.value = sensor.deviceName

                val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
                _timerData.update { it.copy(connectedTime = elapsed) }
                Log.d(TAG, "Connected to device at ${elapsed}s. Waiting for GATT services...")

                // Wait for the first data packet to signal that services are ready
                withTimeout(10000L) { // 10-second timeout
                    gattReadySignal.await()
                }

                Log.d(TAG, "GATT services are ready, proceeding with configuration.")

                _isScanning.value = false
                configureSensor() // Now called after the signal is received

            } catch (e: Exception) { // Catches OpenDeviceException, TimeoutCancellationException, etc.
                _errorMessage.value = "Connection failed or timed out: ${e.message}"
                Log.e(TAG, "Error in connectToDevice", e)
                disconnect() // Ensure cleanup
                stopTimer()
            }
        }
    }

    private suspend fun configureSensor() {
        connectedSensor?.let { sensor ->
            try {
                Log.d(TAG, "🔧 Starting WitMotion sensor configuration...")

                // Phase 2: Configure sensor (writes and save)
                Log.d(TAG, "⚙️ Phase 2: Configuring sensor...")
                performConfiguration(sensor)

                // Phase 3: Verify configuration (reads)
                Log.d(TAG, "📋 Phase 3: Verifying configuration...")
                verifyConfiguration(sensor)

                // Mark configuration as complete
                configurationComplete = true
                val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
                _timerData.update { it.copy(configuredTime = elapsed) }
                Log.d(TAG, "✅✅✅ Configuration complete at ${elapsed}s")

            } catch (e: Exception) {
                _errorMessage.value = "Configuration failed: ${e.message}"
                Log.e(TAG, "Failed to configure sensor", e)
            }
        }
    }

    private suspend fun performConfiguration(sensor: Bwt901ble) {
        Log.d(TAG, "🔧 Starting configuration writes...")

        // Step 1: Unlock registers
        Log.d(TAG, "🔓 Unlocking registers...")
        sensor.unlockReg()
        delay(200) // Give time for unlock to take effect

        // Step 2: Set return rate to 200Hz
        Log.d(TAG, "📊 Setting return rate...")
        setReturnRateWrite(sensor, "200Hz")
        delay(200)

        // Step 3: Set bandwidth to 256Hz
        Log.d(TAG, "📶 Setting bandwidth...")
        setBandwidthWrite(sensor, "256Hz")
        delay(200)

        // Step 4: Set output mode to 2 (Acc+Gyro+Timestamp)
        Log.d(TAG, "📤 Setting output mode...")
        setOutputModeWrite(sensor, "2")
        delay(200)

        // Step 5: Save configuration
        Log.d(TAG, "💾 Saving configuration...")
        saveConfiguration(sensor)
        delay(200) // Give time for save to complete

        Log.d(TAG, "✅ All configuration writes completed")
    }

    private suspend fun verifyConfiguration(sensor: Bwt901ble) {
        Log.d(TAG, "📋 Starting configuration verification...")

        // Give the sensor time to process the saved configuration
        delay(200)

        // Verify each setting
        verifyReturnRate(sensor)
        delay(200)

        verifyBandwidth(sensor)
        delay(200)

        Log.d(TAG, "✅ Configuration verification completed")
    }

    private suspend fun setReturnRateWrite(sensor: Bwt901ble, rate: String) {
        Log.d(TAG, "Setting return rate to $rate...")

        val rateValue = when (rate) {
            "0.1Hz" -> 0x01
            "0.5Hz" -> 0x02
            "1Hz" -> 0x03
            "2Hz" -> 0x04
            "5Hz" -> 0x05
            "10Hz" -> 0x06
            "20Hz" -> 0x07
            "50Hz" -> 0x08
            "100Hz" -> 0x09
            "200Hz" -> 0x0B
            else -> {
                Log.e(TAG, "Invalid return rate: $rate")
                return
            }
        }

        val command = byteArrayOf(
            0xFF.toByte(), 0xAA.toByte(), 0x03.toByte(),
            rateValue.toByte(), 0x00.toByte()
        )
        sensor.sendProtocolData(command, 200)
        delay(200)

        Log.d(
            TAG,
            "📤 Return rate command sent: $rate (value: 0x${String.format("%02X", rateValue)})"
        )
    }

    private suspend fun setBandwidthWrite(sensor: Bwt901ble, bandwidth: String) {
        Log.d(TAG, "Setting bandwidth to $bandwidth...")

        val bandwidthValue = when (bandwidth) {
            "256Hz" -> 0x00
            "188Hz" -> 0x01
            "98Hz" -> 0x02
            "42Hz" -> 0x03
            "20Hz" -> 0x04
            "10Hz" -> 0x05
            "5Hz" -> 0x06
            else -> {
                Log.e(TAG, "Invalid bandwidth: $bandwidth")
                return
            }
        }

        val command = byteArrayOf(
            0xFF.toByte(),
            0xAA.toByte(),
            0x1F.toByte(),
            bandwidthValue.toByte(),
            0x00.toByte()
        )

        sensor.sendProtocolData(command, 200)
        delay(200)

        Log.d(
            TAG,
            "📤 Bandwidth command sent: $bandwidth (value: 0x${
                String.format(
                    "%02X",
                    bandwidthValue
                )
            })"
        )
    }

    private suspend fun setOutputModeWrite(sensor: Bwt901ble, mode: String) {
        Log.d(TAG, "Setting output mode to $mode (Acc+Gyro+Timestamp)...")

        val modeValue = when (mode) {
            "0" -> 0x00
            "1" -> 0x01
            "2" -> 0x02
            "3" -> 0x03
            else -> {
                Log.e(TAG, "Invalid output mode: $mode")
                return
            }
        }

        val command = byteArrayOf(
            0xFF.toByte(), 0xAA.toByte(), 0x96.toByte(),
            modeValue.toByte(), 0x00.toByte()
        )
        sensor.sendProtocolData(command, 200)
        delay(200)

        configuredOutputMode = mode
        Log.d(
            TAG,
            "📤 Output mode command sent: Mode $mode (value: 0x${String.format("%02X", modeValue)})"
        )
    }

    private suspend fun saveConfiguration(sensor: Bwt901ble) {
        Log.d(TAG, "Saving configuration...")

        val command =
            byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        sensor.sendProtocolData(command, 200)

        Log.d(TAG, "💾 Configuration saved")
    }

    private suspend fun verifyReturnRate(sensor: Bwt901ble) {
        Log.d(TAG, "📊 Verifying return rate...")

        try {
            sensor.sendProtocolData(
                byteArrayOf(
                    0xFF.toByte(),
                    0xAA.toByte(),
                    0x27.toByte(),
                    0x03.toByte(),
                    0x00.toByte()
                ),
                200
            )
            delay(200)

            val value = sensor.getDeviceData("03")

            if (value != null) {
                val rateDesc = when (value) {
                    "1" -> "0.1Hz"
                    "2" -> "0.5Hz"
                    "3" -> "1Hz"
                    "4" -> "2Hz"
                    "5" -> "5Hz"
                    "6" -> "10Hz"
                    "7" -> "20Hz"
                    "8" -> "50Hz"
                    "9" -> "100Hz"
                    "11" -> "200Hz"
                    else -> "Unknown (${value})"
                }
                Log.d(TAG, "✅ Return rate verified: $rateDesc (register value: $value)")
            } else {
                Log.w(TAG, "⚠️ Could not read return rate register")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to verify return rate: ${e.message}")
        }
    }

    private suspend fun verifyBandwidth(sensor: Bwt901ble) {
        Log.d(TAG, "📶 Verifying bandwidth...")

        try {
            sensor.sendProtocolData(
                byteArrayOf(
                    0xFF.toByte(),
                    0xAA.toByte(),
                    0x27.toByte(),
                    0x1F.toByte(),
                    0x00.toByte()
                ),
                200
            )
            delay(200)

            val bandwidthValue = sensor.getDeviceData("1F")?.toIntOrNull()

            val bandwidthDesc = when (bandwidthValue) {
                0 -> "256Hz"
                1 -> "188Hz"
                2 -> "98Hz"
                3 -> "42Hz"
                4 -> "20Hz"
                5 -> "10Hz"
                6 -> "5Hz"
                else -> "Unknown (${bandwidthValue})"
            }
            Log.d(TAG, "✅ Bandwidth verified: $bandwidthDesc ( bandwidth value: $bandwidthValue)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to verify bandwidth: ${e.message}")
        }
    }

    override fun onRecord(sensor: Bwt901ble) {
        // On the very first data packet, signal that the GATT services are ready.
        if (!gattReadySignal.isCompleted) {
            gattReadySignal.complete(Unit)
        }

        // Get raw angle data
        val rawAngleX = sensor.getDeviceData(WitSensorKey.AngleX)?.toFloatOrNull()
        val rawAngleY = sensor.getDeviceData(WitSensorKey.AngleY)?.toFloatOrNull()
        val rawAngleZ = sensor.getDeviceData(WitSensorKey.AngleZ)?.toFloatOrNull()

        if (!hasReceivedFirstData && configurationComplete) {
            hasReceivedFirstData = true
            val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000f
            _timerData.update { it.copy(firstDataTime = elapsed) }
            Log.d(TAG, "🎯 FIRST DATA RECEIVED at ${elapsed}s")
            stopTimer()
        }

        val accX = sensor.getDeviceData(WitSensorKey.AccX)?.toFloatOrNull()
        val accY = sensor.getDeviceData(WitSensorKey.AccY)?.toFloatOrNull()
        val accZ = sensor.getDeviceData(WitSensorKey.AccZ)?.toFloatOrNull()
        val gyroX = sensor.getDeviceData(WitSensorKey.AsX)?.toFloatOrNull()
        val gyroY = sensor.getDeviceData(WitSensorKey.AsY)?.toFloatOrNull()
        val gyroZ = sensor.getDeviceData(WitSensorKey.AsZ)?.toFloatOrNull()

        val (angleX, angleY, angleZ, timestamp) = when (configuredOutputMode) {
            "2" -> {
                val computedTimestamp = extractTimestampFromAngleData(rawAngleX, rawAngleY)
                Quadruple(null, null, rawAngleZ, computedTimestamp)
            }
            else -> {
                val chipTime = sensor.getDeviceData(WitSensorKey.ChipTime)?.toLongOrNull()
                Quadruple(rawAngleX, rawAngleY, rawAngleZ, chipTime)
            }
        }

        val q0 = sensor.getDeviceData(WitSensorKey.Q0)?.toFloatOrNull()
        val q1 = sensor.getDeviceData(WitSensorKey.Q1)?.toFloatOrNull()
        val q2 = sensor.getDeviceData(WitSensorKey.Q2)?.toFloatOrNull()
        val q3 = sensor.getDeviceData(WitSensorKey.Q3)?.toFloatOrNull()
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
                battery = battery,
                outputMode = configuredOutputMode
            )
        }
    }

    private fun extractTimestampFromAngleData(angleX: Float?, angleY: Float?): Long? {
        if (angleX == null || angleY == null) return null

        return try {
            val ms1ms2 = angleX.toInt()
            val ms3ms4 = angleY.toInt()

            val ms1 = ms1ms2 and 0xFF
            val ms2 = (ms1ms2 shr 8) and 0xFF
            val ms3 = ms3ms4 and 0xFF
            val ms4 = (ms3ms4 shr 8) and 0xFF

            (ms4.toLong() shl 24) or
                    (ms3.toLong() shl 16) or
                    (ms2.toLong() shl 8) or
                    ms1.toLong()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract timestamp: ${e.message}")
            null
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
            configuredOutputMode = null
        }
    }

    fun reset() {
        disconnect()
        _timerData.value = TimerData()
        _errorMessage.value = null
        hasReceivedFirstData = false
        configurationComplete = false
        configuredOutputMode = null
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private suspend fun readAllSensorRegisters(sensor: Bwt901ble) {
        Log.d(TAG, "🔍 Reading all sensor registers (Hex: 0x00-0xFF & Dec: 0-99)...")

        val foundRegisters = mutableMapOf<String, String>()
        val emptyRegisters = mutableSetOf<String>()
        val keysToTest = mutableSetOf<String>()

        for (i in 0..255) {
            keysToTest.add(String.format("%02X", i))
        }
        for (i in 0..99) {
            keysToTest.add(i.toString())
        }
        Log.d(TAG, "Total unique keys to test: ${keysToTest.size}")

        for (key in keysToTest.sorted()) {
            try {
                val value = sensor.getDeviceData(key)

                if (value != null && value.isNotEmpty()) {
                    foundRegisters[key] = value
                    Log.d(TAG, "📊 Register '$key' = $value")
                } else {
                    emptyRegisters.add(key)
                }
                delay(50)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error reading register '$key': ${e.message}")
            }
        }

        Log.d(TAG, "📋 === REGISTER SCAN SUMMARY ===")
        Log.d(TAG, "✅ Found ${foundRegisters.size} registers with data:")
        foundRegisters.toSortedMap().forEach { (key, value) ->
            Log.d(TAG, "   $key: $value")
        }
        Log.d(TAG, "❌ Found ${emptyRegisters.size} empty or unreadable registers.")
        if (emptyRegisters.isNotEmpty() && emptyRegisters.size <= 50) {
            Log.d(TAG, "   Empty keys: ${emptyRegisters.joinToString(", ")}")
        }
    }
}
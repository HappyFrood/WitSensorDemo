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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val timestamp: Long? = null,
    val temperature: Float? = null,
    val battery: Float? = null
)

@SuppressLint("MissingPermission")
class WitMotionService : IBluetoothFoundObserver, IBwt901bleRecordObserver {

    companion object {
        private const val TAG = "WitMotionService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var witBluetoothManager: WitBluetoothManager? = null
    private var connectedSensor: Bwt901ble? = null
    private var currentDataMode = 0
    private var initialStateRead = false // Flag to ensure we only read state once

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

    fun initialize(context: Context) {
        try {
            WitBluetoothManager.initInstance(context)
            witBluetoothManager = WitBluetoothManager.getInstance()
        } catch (e: Exception) {
            _errorMessage.value = "SDK Initialization Failed: ${e.message}"
        }
    }

    fun startScan() {
        if (_isScanning.value || _isConnected.value) return
        serviceScope.launch {
            try {
                _isScanning.value = true
                _errorMessage.value = null
                witBluetoothManager?.registerObserver(this@WitMotionService)
                witBluetoothManager?.startDiscovery()
            } catch (e: BluetoothBLEException) {
                _errorMessage.value = "Scan Error: ${e.message}"
                _isScanning.value = false
            }
        }
    }

    fun stopScan() {
        serviceScope.launch {
            witBluetoothManager?.stopDiscovery()
            witBluetoothManager?.removeObserver(this@WitMotionService)
            _isScanning.value = false
        }
    }

    override fun onFoundBle(device: BluetoothBLE) {
        if (device.name?.startsWith("WT") == true) {
            stopScan()
            connectToDevice(device)
        }
    }

    override fun onFoundSPP(p0: com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.BluetoothSPP?) {}

    private fun connectToDevice(device: BluetoothBLE) {
        serviceScope.launch {
            try {
                initialStateRead = false // Reset flag on new connection
                val sensor = Bwt901ble(device)
                sensor.registerRecordObserver(this@WitMotionService)
                sensor.open()

                connectedSensor = sensor
                _isConnected.value = true
                _deviceName.value = sensor.deviceName

            } catch (e: OpenDeviceException) {
                _errorMessage.value = "Connection Failed: ${e.message}"
                _isConnected.value = false
            }
        }
    }

    fun disconnect() {
        serviceScope.launch {
            if (currentDataMode != 0) {
                setDataOutputMode(0)
                delay(250)
            }
            connectedSensor?.close()
            connectedSensor?.removeRecordObserver(this@WitMotionService)
            connectedSensor = null
            _isConnected.value = false
            _deviceName.value = null
            _imuData.value = ImuData()
            currentDataMode = 0
        }
    }

    override fun onRecord(sensor: Bwt901ble) {
        // If this is the first data packet, read the initial state.
        if (!initialStateRead) {
            readAndLogInitialState()
            initialStateRead = true
        }

        var angleX: Float? = null; var angleY: Float? = null; var angleZ: Float? = null
        var timestamp: Long? = null

        if (currentDataMode == 0) {
            angleX = sensor.getDeviceData(WitSensorKey.AngleX)?.toFloatOrNull()
            angleY = sensor.getDeviceData(WitSensorKey.AngleY)?.toFloatOrNull()
            angleZ = sensor.getDeviceData(WitSensorKey.AngleZ)?.toFloatOrNull()
        } else if (currentDataMode == 2) {
            try {
                val tsBytes12 = sensor.getDeviceData("3d")?.toShort()
                val tsBytes34 = sensor.getDeviceData("3e")?.toShort()

                if (tsBytes12 != null && tsBytes34 != null) {
                    val high = (tsBytes34.toLong() and 0xFFFF) shl 16
                    val low = tsBytes12.toLong() and 0xFFFF
                    timestamp = high or low
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse timestamp from raw registers", e)
            }
        }

        val accX = sensor.getDeviceData(WitSensorKey.AccX)?.toFloatOrNull()
        val accY = sensor.getDeviceData(WitSensorKey.AccY)?.toFloatOrNull()
        val accZ = sensor.getDeviceData(WitSensorKey.AccZ)?.toFloatOrNull()
        val gyroX = sensor.getDeviceData(WitSensorKey.AsX)?.toFloatOrNull()
        val gyroY = sensor.getDeviceData(WitSensorKey.AsY)?.toFloatOrNull()
        val gyroZ = sensor.getDeviceData(WitSensorKey.AsZ)?.toFloatOrNull()

        _imuData.update {
            it.copy(
                accX = accX, accY = accY, accZ = accZ,
                gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
                angleX = angleX, angleY = angleY, angleZ = angleZ,
                timestamp = timestamp,
                temperature = sensor.getDeviceData(WitSensorKey.T)?.toFloatOrNull(),
                battery = sensor.getDeviceData(WitSensorKey.ElectricQuantityPercentage)?.toFloatOrNull()
            )
        }
    }

    private fun sendCommand(register: Byte, value: Byte, description: String) {
        serviceScope.launch {
            connectedSensor?.let { sensor ->
                try {
                    sensor.unlockReg()
                    delay(100)
                    val command = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), register, value, 0x00)
                    sensor.sendProtocolData(command, 50)
                    delay(100)
                    val saveCommand = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00, 0x00, 0x00)
                    sensor.sendProtocolData(saveCommand, 50)
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to set $description"
                }
            }
        }
    }

    fun setDataOutputMode(mode: Int) {
        serviceScope.launch {
            readRegister(0x96.toByte()) { currentMode ->
                if (currentMode != null && currentMode.toInt() == mode) {
                    Log.d(TAG, "Mode is already set to $mode. No action needed.")
                    currentDataMode = mode
                    return@readRegister
                }

                Log.d(TAG, "Changing mode from $currentMode to $mode.")
                currentDataMode = mode
                val modeByte = mode.toByte()
                sendCommand(0x96.toByte(), modeByte, "Data Mode to $mode")
            }
        }
    }

    private fun readRegister(register: Byte, callback: (Short?) -> Unit) {
        connectedSensor?.let { sensor ->
            try {
                val registerKey = String.format("%02x", register)

                val observer = object : IBwt901bleRecordObserver {
                    override fun onRecord(bwt901ble: Bwt901ble) {
                        val deviceData = bwt901ble.getDeviceData(registerKey)
                        if (deviceData != null) {
                            try {
                                val value = deviceData.toShort()
                                callback(value)
                                sensor.removeRecordObserver(this)
                            } catch (e: NumberFormatException) {
                                Log.e(TAG, "Failed to parse Short from register $registerKey", e)
                                callback(null)
                                sensor.removeRecordObserver(this)
                            }
                        }
                    }
                }

                sensor.registerRecordObserver(observer)

                val readCmd = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, register, 0x00)
                sensor.sendProtocolData(readCmd, 50)

            } catch (e: Exception) {
                Log.e(TAG, "Generic failure to read register $register", e)
                callback(null)
            }
        } ?: callback(null)
    }

    fun readAndLogInitialState() {
        serviceScope.launch {
            Log.d(TAG, "========================================")
            Log.d(TAG, "First data packet received, reading initial state...")

            readRegister(0x96.toByte()) { outputMode ->
                if (outputMode != null) {
                    Log.d(TAG, "✅ Current Output Mode (Reg 0x96): ${outputMode.toInt()}")
                    currentDataMode = outputMode.toInt()
                } else {
                    Log.e(TAG, "❌ Failed to read Output Mode.")
                }
            }

            delay(200)

            readRegister(0x03.toByte()) { returnRate ->
                if (returnRate != null) {
                    Log.d(TAG, "✅ Current Return Rate (Reg 0x03): ${returnRate.toInt()}")
                } else {
                    Log.e(TAG, "❌ Failed to read Return Rate.")
                }
            }

            delay(200)

            readRegister(0x1F.toByte()) { bandwidth ->
                if (bandwidth != null) {
                    Log.d(TAG, "✅ Current Bandwidth (Reg 0x1F): ${bandwidth.toInt()}")
                } else {
                    Log.e(TAG, "❌ Failed to read Bandwidth.")
                }
                Log.d(TAG, "========================================")
            }
        }
    }

    fun setReturnRate(rateHz: Int) {
        val value = when (rateHz) {
            10 -> 0x06.toByte(); 50 -> 0x08.toByte(); 100 -> 0x09.toByte(); 200 -> 0x0B.toByte()
            else -> 0x08.toByte()
        }
        sendCommand(0x03.toByte(), value, "Return Rate to $rateHz Hz")
    }

    fun setBandwidth(bandwidthHz: Int) {
        val value = when (bandwidthHz) {
            256 -> 0x00.toByte(); 188 -> 0x01.toByte(); 98 -> 0x02.toByte(); 42 -> 0x03.toByte();
            20 -> 0x04.toByte(); 10 -> 0x05.toByte(); 5 -> 0x06.toByte()
            else -> 0x04.toByte()
        }
        sendCommand(0x1F.toByte(), value, "Bandwidth to $bandwidthHz Hz")
    }
}
package com.hunter.trippercustom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

@SuppressLint("MissingPermission")
class TripperBleService : Service() {

    companion object {
        private const val TAG = "TripperBleService"
        private const val NOTIF_CHANNEL_ID = "tripper_service_channel"
        private const val NOTIF_ID = 1001

        const val PREFS_NAME = "TripperHunterPrefs"
        const val KEY_SAVED_MAC = "saved_mac_address"
        const val KEY_SAVED_NAME = "saved_device_name"
        const val KEY_PREF_NAV = "pref_nav"
        const val KEY_PREF_CALLS = "pref_calls"
        const val KEY_PREF_MUSIC = "pref_music"
        const val KEY_PREF_BATTERY = "pref_battery"

        // Broadcast actions
        const val ACTION_BLE_STATUS = "com.hunter.trippercustom.ACTION_BLE_STATUS"
        const val ACTION_DEVICE_FOUND = "com.hunter.trippercustom.ACTION_DEVICE_FOUND"
        const val ACTION_CONNECT_MAC = "com.hunter.trippercustom.ACTION_CONNECT_MAC"
        const val ACTION_DISCONNECT = "com.hunter.trippercustom.ACTION_DISCONNECT"
        const val ACTION_SEND_TEST = "com.hunter.trippercustom.ACTION_SEND_TEST"

        const val EXTRA_IS_CONNECTED = "extra_is_connected"
        const val EXTRA_STATUS_MSG = "extra_status_msg"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_MAC = "extra_device_mac"
        const val EXTRA_TEST_TYPE = "extra_test_type"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var isScanning = false

    private lateinit var prefs: SharedPreferences
    private var lastNavPacket: ByteArray? = null
    private var isCallActive = false
    private var isNearTurn = false

    private val handler = Handler(Looper.getMainLooper())

    private val revertToNavRunnable = Runnable {
        if (!isCallActive && isConnected) {
            lastNavPacket?.let { sendPacket(it) } ?: sendPacket(TripperProtocol.createHeartbeatPacket())
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                if (!isCallActive && lastNavPacket == null) {
                    sendPacket(TripperProtocol.createHeartbeatPacket())
                }
                handler.postDelayed(this, 3000)
            }
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECT_MAC -> {
                    val mac = intent.getStringExtra(EXTRA_DEVICE_MAC)
                    if (!mac.isNullOrEmpty()) {
                        connectToDevice(mac)
                    }
                }
                ACTION_DISCONNECT -> {
                    disconnectGatt()
                }
                ACTION_SEND_TEST -> {
                    val testType = intent.getStringExtra(EXTRA_TEST_TYPE) ?: "turn"
                    executeTest(testType)
                }
                MapsNotificationListener.ACTION_NAV_UPDATE -> {
                    if (!prefs.getBoolean(KEY_PREF_NAV, true)) return

                    val icon = intent.getByteExtra(MapsNotificationListener.EXTRA_ICON, TripperProtocol.ICON_STRAIGHT)
                    val distVal = intent.getIntExtra(MapsNotificationListener.EXTRA_DIST_VAL, 0)
                    val distUnit = intent.getByteExtra(MapsNotificationListener.EXTRA_DIST_UNIT, TripperProtocol.UNIT_METERS)
                    val etaH = intent.getIntExtra(MapsNotificationListener.EXTRA_ETA_H, 0)
                    val etaM = intent.getIntExtra(MapsNotificationListener.EXTRA_ETA_M, 0)

                    isNearTurn = (distUnit == TripperProtocol.UNIT_METERS && distVal <= 100)
                    val packet = TripperProtocol.createNavPacket(icon, distVal, distUnit, etaH, etaM)
                    lastNavPacket = packet

                    if (!isCallActive) {
                        sendPacket(packet)
                    }
                }
                CallReceiver.ACTION_CALLER_UPDATE -> {
                    if (!prefs.getBoolean(KEY_PREF_CALLS, true)) return

                    val isRinging = intent.getBooleanExtra(CallReceiver.EXTRA_IS_RINGING, false)
                    isCallActive = isRinging
                    if (isRinging) {
                        handler.removeCallbacks(revertToNavRunnable)
                        val callerName = intent.getStringExtra(CallReceiver.EXTRA_CALLER_NAME) ?: "Caller"
                        sendPacket(TripperProtocol.createCallerPacket("CALL: $callerName"))
                    } else {
                        lastNavPacket?.let { sendPacket(it) } ?: sendPacket(TripperProtocol.createHeartbeatPacket())
                    }
                }
                MapsNotificationListener.ACTION_MEDIA_UPDATE -> {
                    if (!prefs.getBoolean(KEY_PREF_MUSIC, true)) return

                    if (!isCallActive && !isNearTurn) {
                        val song = intent.getStringExtra(MapsNotificationListener.EXTRA_TRACK_TITLE) ?: ""
                        sendPacket(TripperProtocol.createCallerPacket("🎵 $song"))
                        handler.removeCallbacks(revertToNavRunnable)
                        handler.postDelayed(revertToNavRunnable, 3500)
                    }
                }
                MapsNotificationListener.ACTION_BATTERY_ALERT -> {
                    if (!prefs.getBoolean(KEY_PREF_BATTERY, true)) return

                    if (!isCallActive) {
                        val pct = intent.getIntExtra("battery_pct", 15)
                        sendPacket(TripperProtocol.createCallerPacket("⚠️ BATT $pct%"))
                        handler.removeCallbacks(revertToNavRunnable)
                        handler.postDelayed(revertToNavRunnable, 4000)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT_MAC)
            addAction(ACTION_DISCONNECT)
            addAction(ACTION_SEND_TEST)
            addAction(MapsNotificationListener.ACTION_NAV_UPDATE)
            addAction(CallReceiver.ACTION_CALLER_UPDATE)
            addAction(MapsNotificationListener.ACTION_MEDIA_UPDATE)
            addAction(MapsNotificationListener.ACTION_BATTERY_ALERT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        startForeground(NOTIF_ID, createNotification("Searching for Royal Enfield Tripper Pod..."))

        // Auto-connect to saved MAC address if available, else start scan
        val savedMac = prefs.getString(KEY_SAVED_MAC, null)
        if (!savedMac.isNullOrEmpty()) {
            notifyStatus(false, "Connecting to saved pod ($savedMac)...")
            connectToDevice(savedMac)
        } else {
            startScan()
        }
    }

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (isScanning) return

        isScanning = true
        notifyStatus(false, "Scanning for Tripper Pod...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        Log.d(TAG, "BLE Scan started")

        // Auto-stop scan after 30 seconds if nothing connected
        handler.postDelayed({
            stopScan()
        }, 30000)
    }

    private fun stopScan() {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "BLE Scan stopped")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: ""
            val upperName = name.uppercase()

            val isTripper = upperName.contains("RE") ||
                            upperName.contains("DISP") ||
                            upperName.contains("TRIPPER") ||
                            upperName.contains("ROYAL") ||
                            upperName.contains("ENFIELD")

            // Broadcast found device to UI
            val foundIntent = Intent(ACTION_DEVICE_FOUND).apply {
                putExtra(EXTRA_DEVICE_NAME, if (name.isNotEmpty()) name else "Royal Enfield Pod")
                putExtra(EXTRA_DEVICE_MAC, device.address)
            }
            sendBroadcast(foundIntent)

            // Auto-connect if matches RE pattern and not currently connecting
            if (isTripper && !isConnected && bluetoothGatt == null) {
                Log.d(TAG, "Matched Tripper: $name (${device.address})")
                stopScan()
                connectToDevice(device.address)
            }
        }
    }

    private fun connectToDevice(mac: String) {
        val adapter = bluetoothAdapter ?: return
        stopScan()

        try {
            val device = adapter.getRemoteDevice(mac)
            notifyStatus(false, "Connecting to ${device.name ?: mac}...")
            updateNotification("Connecting to ${device.name ?: "Tripper Pod"}...")

            // Disconnect old gatt
            bluetoothGatt?.close()
            bluetoothGatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}")
            notifyStatus(false, "Connection failed: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "ConnectionStateChanged: status=$status, newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                val devName = gatt.device.name ?: "Tripper Pod"
                val devMac = gatt.device.address

                // Save bonded MAC for automatic reconnect next time
                prefs.edit()
                    .putString(KEY_SAVED_MAC, devMac)
                    .putString(KEY_SAVED_NAME, devName)
                    .apply()

                notifyStatus(true, "Connected: $devName", devName, devMac)
                updateNotification("Connected to $devName")

                // Discover services
                handler.postDelayed({
                    gatt.discoverServices()
                }, 300)

                handler.post(heartbeatRunnable)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                writeCharacteristic = null
                handler.removeCallbacks(heartbeatRunnable)
                notifyStatus(false, "Disconnected from Tripper Pod")
                updateNotification("Disconnected. Scanning...")

                bluetoothGatt?.close()
                bluetoothGatt = null

                // Auto retry after 3 seconds
                handler.postDelayed({
                    if (!isConnected) {
                        val saved = prefs.getString(KEY_SAVED_MAC, null)
                        if (!saved.isNullOrEmpty()) {
                            connectToDevice(saved)
                        } else {
                            startScan()
                        }
                    }
                }, 3000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                for (service in gatt.services) {
                    val char = service.getCharacteristic(TripperProtocol.CHAR_WRITE_UUID)
                    if (char != null) {
                        writeCharacteristic = char
                        Log.d(TAG, "GATT Write Endpoint Bound: ${service.uuid} -> ${char.uuid}")
                        
                        // Send welcome heartbeat
                        sendPacket(TripperProtocol.createHeartbeatPacket())
                        break
                    }
                }
            }
        }
    }

    private fun sendPacket(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        if (!isConnected) return

        try {
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(char)
        } catch (e: Exception) {
            Log.e(TAG, "Write error: ${e.message}")
        }
    }

    private fun executeTest(type: String) {
        when (type) {
            "turn_right" -> {
                sendPacket(TripperProtocol.createNavPacket(TripperProtocol.ICON_TURN_RIGHT, 250, TripperProtocol.UNIT_METERS, 12, 45))
            }
            "turn_left" -> {
                sendPacket(TripperProtocol.createNavPacket(TripperProtocol.ICON_TURN_LEFT, 500, TripperProtocol.UNIT_METERS, 12, 48))
            }
            "roundabout" -> {
                sendPacket(TripperProtocol.createNavPacket(TripperProtocol.ICON_ROUNDABOUT_2, 350, TripperProtocol.UNIT_METERS, 12, 50))
            }
            "caller" -> {
                sendPacket(TripperProtocol.createCallerPacket("CALL: DAD"))
                handler.postDelayed({
                    sendPacket(TripperProtocol.createHeartbeatPacket())
                }, 4000)
            }
            "music" -> {
                sendPacket(TripperProtocol.createCallerPacket("🎵 HUKUM - ANIRUDH"))
                handler.postDelayed({
                    sendPacket(TripperProtocol.createHeartbeatPacket())
                }, 3500)
            }
            "battery" -> {
                sendPacket(TripperProtocol.createCallerPacket("⚠️ BATT 15%"))
                handler.postDelayed({
                    sendPacket(TripperProtocol.createHeartbeatPacket())
                }, 3000)
            }
        }
    }

    private fun disconnectGatt() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        notifyStatus(false, "Disconnected")
        updateNotification("Pod disconnected")
    }

    private fun notifyStatus(connected: Boolean, msg: String, deviceName: String = "", deviceMac: String = "") {
        val intent = Intent(ACTION_BLE_STATUS).apply {
            putExtra(EXTRA_IS_CONNECTED, connected)
            putExtra(EXTRA_STATUS_MSG, msg)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra(EXTRA_DEVICE_MAC, deviceMac)
        }
        sendBroadcast(intent)
    }

    private fun createNotification(contentText: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Tripper Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Hunter 350 Tripper Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(controlReceiver)
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(revertToNavRunnable)
        disconnectGatt()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

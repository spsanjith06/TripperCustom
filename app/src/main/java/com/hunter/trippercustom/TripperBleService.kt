package com.hunter.trippercustom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        const val ACTION_BLE_STATUS = "com.hunter.trippercustom.ACTION_BLE_STATUS"
        const val EXTRA_IS_CONNECTED = "extra_is_connected"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                sendPacket(TripperProtocol.createHeartbeatPacket())
                handler.postDelayed(this, 3000) // Keep-alive every 3s
            }
        }
    }

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MapsNotificationListener.ACTION_NAV_UPDATE -> {
                    val icon = intent.getByteExtra(MapsNotificationListener.EXTRA_ICON, TripperProtocol.ICON_STRAIGHT)
                    val distVal = intent.getIntExtra(MapsNotificationListener.EXTRA_DIST_VAL, 0)
                    val distUnit = intent.getByteExtra(MapsNotificationListener.EXTRA_DIST_UNIT, TripperProtocol.UNIT_METERS)
                    val etaH = intent.getIntExtra(MapsNotificationListener.EXTRA_ETA_H, 0)
                    val etaM = intent.getIntExtra(MapsNotificationListener.EXTRA_ETA_M, 0)

                    val packet = TripperProtocol.createNavPacket(icon, distVal, distUnit, etaH, etaM)
                    sendPacket(packet)
                }
                CallReceiver.ACTION_CALLER_UPDATE -> {
                    val isRinging = intent.getBooleanExtra(CallReceiver.EXTRA_IS_RINGING, false)
                    if (isRinging) {
                        val callerName = intent.getStringExtra(CallReceiver.EXTRA_CALLER_NAME) ?: "Caller"
                        val packet = TripperProtocol.createCallerPacket(callerName)
                        sendPacket(packet)
                    } else {
                        // Return to normal heartbeat / navigation
                        sendPacket(TripperProtocol.createHeartbeatPacket())
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        val filter = IntentFilter().apply {
            addAction(MapsNotificationListener.ACTION_NAV_UPDATE)
            addAction(CallReceiver.ACTION_CALLER_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dataReceiver, filter)
        }

        startForeground(NOTIF_ID, createNotification("Searching for Royal Enfield Tripper Pod..."))
        startScan()
    }

    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setDeviceName(TripperProtocol.DEVICE_NAME_PREFIX)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "BLE scan started for prefix ${TripperProtocol.DEVICE_NAME_PREFIX}")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: ""
            if (name.startsWith(TripperProtocol.DEVICE_NAME_PREFIX) || name.contains("RE_")) {
                Log.d(TAG, "Tripper found: $name, connecting...")
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                bluetoothGatt = device.connectGatt(this@TripperBleService, true, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server.")
                isConnected = true
                gatt.discoverServices()
                notifyStatus(true, gatt.device.name ?: "Tripper Pod")
                updateNotification("Connected to ${gatt.device.name ?: "Tripper Pod"}")
                handler.post(heartbeatRunnable)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
                isConnected = false
                writeCharacteristic = null
                handler.removeCallbacks(heartbeatRunnable)
                notifyStatus(false, "")
                updateNotification("Disconnected. Retrying scan...")
                startScan()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(TripperProtocol.SERVICE_UUID)
                writeCharacteristic = service?.getCharacteristic(TripperProtocol.CHAR_WRITE_UUID)
                Log.d(TAG, "Services discovered. Write Char: $writeCharacteristic")
            }
        }
    }

    private fun sendPacket(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        if (!isConnected) return

        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.writeCharacteristic(char)
    }

    private fun notifyStatus(connected: Boolean, deviceName: String) {
        val intent = Intent(ACTION_BLE_STATUS).apply {
            putExtra(EXTRA_IS_CONNECTED, connected)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
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
        unregisterReceiver(dataReceiver)
        handler.removeCallbacks(heartbeatRunnable)
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

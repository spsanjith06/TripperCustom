package com.hunter.trippercustom

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLiveInfo: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnNotifPermission: Button

    private var isServiceRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TripperBleService.ACTION_BLE_STATUS -> {
                    val connected = intent.getBooleanExtra(TripperBleService.EXTRA_IS_CONNECTED, false)
                    val deviceName = intent.getStringExtra(TripperBleService.EXTRA_DEVICE_NAME) ?: ""
                    updateUiStatus(connected, deviceName)
                }
                MapsNotificationListener.ACTION_NAV_UPDATE -> {
                    val rawText = intent.getStringExtra(MapsNotificationListener.EXTRA_RAW_TEXT) ?: ""
                    tvLiveInfo.text = "Active Nav: $rawText"
                }
                CallReceiver.ACTION_CALLER_UPDATE -> {
                    val isRinging = intent.getBooleanExtra(CallReceiver.EXTRA_IS_RINGING, false)
                    if (isRinging) {
                        val caller = intent.getStringExtra(CallReceiver.EXTRA_CALLER_NAME) ?: "Caller"
                        tvLiveInfo.text = "Incoming Call: $caller (Sent to Pod)"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLiveInfo = findViewById(R.id.tvLiveInfo)
        btnConnect = findViewById(R.id.btnConnect)
        btnNotifPermission = findViewById(R.id.btnNotificationPermission)

        btnConnect.setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
                return@setOnClickListener
            }

            if (!isServiceRunning) {
                val serviceIntent = Intent(this, TripperBleService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                isServiceRunning = true
                btnConnect.text = getString(R.string.btn_disconnect)
                tvStatus.text = "Searching for RE_DISP..."
                tvStatus.setTextColor(Color.parseColor("#FFA000"))
            } else {
                val serviceIntent = Intent(this, TripperBleService::class.java)
                stopService(serviceIntent)
                isServiceRunning = false
                btnConnect.text = getString(R.string.btn_connect)
                updateUiStatus(false, "")
            }
        }

        btnNotifPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Enable 'Tripper Hunter' in the list", Toast.LENGTH_LONG).show()
        }

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(TripperBleService.ACTION_BLE_STATUS)
            addAction(MapsNotificationListener.ACTION_NAV_UPDATE)
            addAction(CallReceiver.ACTION_CALLER_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun updateUiStatus(connected: Boolean, deviceName: String) {
        if (connected) {
            tvStatus.text = "Connected: $deviceName"
            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(Color.parseColor("#FF5252"))
            tvLiveInfo.text = "Turn on Hunter 350 ignition and tap Connect"
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }
}

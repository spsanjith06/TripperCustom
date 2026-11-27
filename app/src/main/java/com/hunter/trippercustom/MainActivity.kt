package com.hunter.trippercustom

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusEmoji: TextView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var tvLiveTelemetry: TextView
    private lateinit var btnConnectToggle: Button
    private lateinit var btnNotifPermission: Button
    private lateinit var layoutDeviceList: LinearLayout

    private lateinit var swNav: Switch
    private lateinit var swCalls: Switch
    private lateinit var swMusic: Switch
    private lateinit var swBattery: Switch

    private lateinit var prefs: SharedPreferences
    private var isConnected = false
    private val discoveredDevices = mutableSetOf<String>()

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TripperBleService.ACTION_BLE_STATUS -> {
                    val connected = intent.getBooleanExtra(TripperBleService.EXTRA_IS_CONNECTED, false)
                    val statusMsg = intent.getStringExtra(TripperBleService.EXTRA_STATUS_MSG) ?: ""
                    val devName = intent.getStringExtra(TripperBleService.EXTRA_DEVICE_NAME) ?: ""
                    val devMac = intent.getStringExtra(TripperBleService.EXTRA_DEVICE_MAC) ?: ""
                    updateConnectionUi(connected, statusMsg, devName, devMac)
                }
                TripperBleService.ACTION_DEVICE_FOUND -> {
                    val name = intent.getStringExtra(TripperBleService.EXTRA_DEVICE_NAME) ?: "Royal Enfield"
                    val mac = intent.getStringExtra(TripperBleService.EXTRA_DEVICE_MAC) ?: return
                    addDeviceToUiList(name, mac)
                }
                MapsNotificationListener.ACTION_NAV_UPDATE -> {
                    val raw = intent.getStringExtra(MapsNotificationListener.EXTRA_RAW_TEXT) ?: "Navigation Active"
                    tvLiveTelemetry.text = "📍 $raw"
                    tvLiveTelemetry.setTextColor(Color.parseColor("#38BDF8"))
                }
                CallReceiver.ACTION_CALLER_UPDATE -> {
                    val isRinging = intent.getBooleanExtra(CallReceiver.EXTRA_IS_RINGING, false)
                    if (isRinging) {
                        val caller = intent.getStringExtra(CallReceiver.EXTRA_CALLER_NAME) ?: "Caller"
                        tvLiveTelemetry.text = "📞 CALLING: $caller (Pushed to Pod)"
                        tvLiveTelemetry.setTextColor(Color.parseColor("#EF4444"))
                    } else {
                        tvLiveTelemetry.text = "Call Ended - Nav Restored"
                        tvLiveTelemetry.setTextColor(Color.parseColor("#10B981"))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(TripperBleService.PREFS_NAME, Context.MODE_PRIVATE)

        tvStatusEmoji = findViewById(R.id.tvStatusEmoji)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSub = findViewById(R.id.tvStatusSub)
        tvLiveTelemetry = findViewById(R.id.tvLiveTelemetry)
        btnConnectToggle = findViewById(R.id.btnConnectToggle)
        btnNotifPermission = findViewById(R.id.btnNotifPermission)
        layoutDeviceList = findViewById(R.id.layoutDeviceList)

        swNav = findViewById(R.id.swNav)
        swCalls = findViewById(R.id.swCalls)
        swMusic = findViewById(R.id.swMusic)
        swBattery = findViewById(R.id.swBattery)

        setupPreferenceSwitches()
        setupTestButtons()

        btnConnectToggle.setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
                return@setOnClickListener
            }

            if (!isConnected) {
                // Start scan & connect
                val serviceIntent = Intent(this, TripperBleService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                tvStatusEmoji.text = "⚡"
                tvStatusTitle.text = "SEARCHING FOR POD..."
                tvStatusTitle.setTextColor(Color.parseColor("#FFA000"))
                tvStatusSub.text = "Turn on Hunter 350 ignition key"
            } else {
                // Disconnect
                sendBroadcast(Intent(TripperBleService.ACTION_DISCONNECT))
            }
        }

        btnNotifPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Enable 'Tripper Hunter' in the list", Toast.LENGTH_LONG).show()
        }

        checkAndRequestPermissions()

        // Auto-launch service on startup if permissions are granted
        if (hasRequiredPermissions()) {
            val serviceIntent = Intent(this, TripperBleService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(TripperBleService.ACTION_BLE_STATUS)
            addAction(TripperBleService.ACTION_DEVICE_FOUND)
            addAction(MapsNotificationListener.ACTION_NAV_UPDATE)
            addAction(CallReceiver.ACTION_CALLER_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiReceiver, filter)
        }

        // Check if notification access enabled
        val isNotifEnabled = isNotificationServiceEnabled()
        if (isNotifEnabled) {
            btnNotifPermission.text = "NOTIFICATION ACCESS: ACTIVE ✓"
            btnNotifPermission.setTextColor(Color.parseColor("#10B981"))
        } else {
            btnNotifPermission.text = "ENABLE NOTIFICATION ACCESS"
            btnNotifPermission.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(uiReceiver)
    }

    private fun updateConnectionUi(connected: Boolean, msg: String, devName: String, devMac: String) {
        isConnected = connected
        if (connected) {
            tvStatusEmoji.text = "🏍️"
            tvStatusTitle.text = "CONNECTED"
            tvStatusTitle.setTextColor(Color.parseColor("#10B981"))
            tvStatusSub.text = "$devName ($devMac)"
            btnConnectToggle.text = "DISCONNECT TRIPPER POD"
            btnConnectToggle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
            tvLiveTelemetry.text = "Pod Linked! Google Maps & Calls will stream directly."
            tvLiveTelemetry.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvStatusEmoji.text = "⚡"
            tvStatusTitle.text = "DISCONNECTED"
            tvStatusTitle.setTextColor(Color.parseColor("#EF4444"))
            tvStatusSub.text = if (msg.isNotEmpty()) msg else "Turn on Hunter 350 ignition key"
            btnConnectToggle.text = "CONNECT TRIPPER POD"
            btnConnectToggle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        }
    }

    private fun addDeviceToUiList(name: String, mac: String) {
        if (discoveredDevices.contains(mac)) return
        discoveredDevices.add(mac)

        val itemBtn = Button(this).apply {
            text = "⚡ $name ($mac) - TAP TO PAIR"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4, 0, 8)
            }
            layoutParams = params
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Connecting to $name...", Toast.LENGTH_SHORT).show()
                val connectIntent = Intent(TripperBleService.ACTION_CONNECT_MAC).apply {
                    putExtra(TripperBleService.EXTRA_DEVICE_MAC, mac)
                }
                sendBroadcast(connectIntent)
            }
        }
        layoutDeviceList.addView(itemBtn)
    }

    private fun setupPreferenceSwitches() {
        swNav.isChecked = prefs.getBoolean(TripperBleService.KEY_PREF_NAV, true)
        swCalls.isChecked = prefs.getBoolean(TripperBleService.KEY_PREF_CALLS, true)
        swMusic.isChecked = prefs.getBoolean(TripperBleService.KEY_PREF_MUSIC, true)
        swBattery.isChecked = prefs.getBoolean(TripperBleService.KEY_PREF_BATTERY, true)

        swNav.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(TripperBleService.KEY_PREF_NAV, checked).apply()
        }
        swCalls.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(TripperBleService.KEY_PREF_CALLS, checked).apply()
        }
        swMusic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(TripperBleService.KEY_PREF_MUSIC, checked).apply()
        }
        swBattery.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(TripperBleService.KEY_PREF_BATTERY, checked).apply()
        }
    }

    private fun setupTestButtons() {
        findViewById<Button>(R.id.btnTestTurn).setOnClickListener {
            sendTest("turn_right")
            Toast.makeText(this, "Sent Turn Right (250m) to Pod", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTestRoundabout).setOnClickListener {
            sendTest("roundabout")
            Toast.makeText(this, "Sent Roundabout (Exit 2) to Pod", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTestCaller).setOnClickListener {
            sendTest("caller")
            Toast.makeText(this, "Sent CALL: DAD to Pod", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTestMusic).setOnClickListener {
            sendTest("music")
            Toast.makeText(this, "Sent 🎵 HUKUM to Pod", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTest(type: String) {
        val intent = Intent(TripperBleService.ACTION_SEND_TEST).apply {
            putExtra(TripperBleService.EXTRA_TEST_TYPE, type)
        }
        sendBroadcast(intent)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
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

package com.hunter.trippercustom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BlePairingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BlePairingReceiver"
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val name = device?.name ?: ""
            Log.d(TAG, "Pairing request from: $name (${device?.address})")

            if (name.contains("RE") || name.contains("DISP") || name.contains("TRIPPER") || name.contains("ROYAL")) {
                try {
                    // Try auto confirmation for Just-Works / Numeric comparison
                    device.setPairingConfirmation(true)
                    abortBroadcast()
                    Log.d(TAG, "Pairing confirmation auto-accepted for $name")
                } catch (e: Exception) {
                    Log.e(TAG, "Auto pair failed: ${e.message}")
                    // Fallback try setting 0000 / 1234 PIN
                    try {
                        val pin = "000000".toByteArray(Charsets.UTF_8)
                        device.setPin(pin)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Set pin failed: ${e2.message}")
                    }
                }
            }
        }
    }
}

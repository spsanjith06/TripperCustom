package com.hunter.trippercustom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        const val ACTION_CALLER_UPDATE = "com.hunter.trippercustom.ACTION_CALLER_UPDATE"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_IS_RINGING = "extra_is_ringing"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone State: $state, Number: $incomingNumber")

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            val contactName = if (!incomingNumber.isNullOrEmpty()) {
                getContactName(context, incomingNumber) ?: incomingNumber
            } else {
                "Incoming Call"
            }

            val callIntent = Intent(ACTION_CALLER_UPDATE).apply {
                putExtra(EXTRA_CALLER_NAME, contactName)
                putExtra(EXTRA_IS_RINGING, true)
            }
            context.sendBroadcast(callIntent)
        } else if (state == TelephonyManager.EXTRA_STATE_IDLE || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            val callIntent = Intent(ACTION_CALLER_UPDATE).apply {
                putExtra(EXTRA_IS_RINGING, false)
            }
            context.sendBroadcast(callIntent)
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (index != -1) cursor.getString(index) else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed: ${e.message}")
            null
        } finally {
            cursor?.close()
        }
    }
}

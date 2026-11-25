package com.hunter.trippercustom

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MapsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MapsNotifListener"
        const val ACTION_NAV_UPDATE = "com.hunter.trippercustom.ACTION_NAV_UPDATE"
        const val EXTRA_ICON = "extra_icon"
        const val EXTRA_DIST_VAL = "extra_dist_val"
        const val EXTRA_DIST_UNIT = "extra_dist_unit"
        const val EXTRA_ETA_H = "extra_eta_h"
        const val EXTRA_ETA_M = "extra_eta_m"
        const val EXTRA_RAW_TEXT = "extra_raw_text"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName
        if (pkg != "com.google.android.apps.maps") return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        val fullText = "$title $text $subText".lowercase()
        Log.d(TAG, "Maps Notif: title='$title', text='$text', subText='$subText'")

        // Parse turn direction
        val icon = parseManeuverIcon(fullText)

        // Parse distance
        val (distVal, distUnit) = parseDistance(title)

        // Parse ETA
        val (etaH, etaM) = parseEta(subText.ifEmpty { text })

        val intent = Intent(ACTION_NAV_UPDATE).apply {
            putExtra(EXTRA_ICON, icon)
            putExtra(EXTRA_DIST_VAL, distVal)
            putExtra(EXTRA_DIST_UNIT, distUnit)
            putExtra(EXTRA_ETA_H, etaH)
            putExtra(EXTRA_ETA_M, etaM)
            putExtra(EXTRA_RAW_TEXT, "$title | $text")
        }
        sendBroadcast(intent)
    }

    private fun parseManeuverIcon(text: String): Byte {
        return when {
            text.contains("destination") || text.contains("arrived") -> TripperProtocol.ICON_DESTINATION
            text.contains("u-turn") || text.contains("make a u-turn") -> TripperProtocol.ICON_U_TURN
            text.contains("1st exit") || text.contains("first exit") -> TripperProtocol.ICON_ROUNDABOUT_1
            text.contains("2nd exit") || text.contains("second exit") -> TripperProtocol.ICON_ROUNDABOUT_2
            text.contains("3rd exit") || text.contains("third exit") -> TripperProtocol.ICON_ROUNDABOUT_3
            text.contains("4th exit") || text.contains("fourth exit") -> TripperProtocol.ICON_ROUNDABOUT_4
            text.contains("roundabout") -> TripperProtocol.ICON_ROUNDABOUT_1
            text.contains("sharp left") -> TripperProtocol.ICON_SHARP_LEFT
            text.contains("sharp right") -> TripperProtocol.ICON_SHARP_RIGHT
            text.contains("slight left") || text.contains("keep left") || text.contains("fork left") -> TripperProtocol.ICON_SLIGHT_LEFT
            text.contains("slight right") || text.contains("keep right") || text.contains("fork right") -> TripperProtocol.ICON_SLIGHT_RIGHT
            text.contains("turn left") || text.contains("left") -> TripperProtocol.ICON_TURN_LEFT
            text.contains("turn right") || text.contains("right") -> TripperProtocol.ICON_TURN_RIGHT
            text.contains("head") || text.contains("continue") || text.contains("straight") -> TripperProtocol.ICON_STRAIGHT
            else -> TripperProtocol.ICON_STRAIGHT
        }
    }

    private fun parseDistance(text: String): Pair<Int, Byte> {
        // Examples: "500 m", "1.2 km", "350 ft", "2 mi"
        val regex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(m|km|ft|mi)""", RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        if (match != null) {
            val num = match.groupValues[1].toFloatOrNull() ?: 0f
            val unitStr = match.groupValues[2].lowercase()
            return when (unitStr) {
                "km" -> Pair((num * 10).toInt(), TripperProtocol.UNIT_KILOMETERS)
                "m" -> Pair(num.toInt(), TripperProtocol.UNIT_METERS)
                "mi" -> Pair((num * 10).toInt(), TripperProtocol.UNIT_MILES)
                "ft" -> Pair(num.toInt(), TripperProtocol.UNIT_FEET)
                else -> Pair(num.toInt(), TripperProtocol.UNIT_METERS)
            }
        }
        return Pair(0, TripperProtocol.UNIT_METERS)
    }

    private fun parseEta(text: String): Pair<Int, Int> {
        // Examples: "5:45 PM", "18:30", "ETA 12:15"
        val regex = Regex("""([0-9]{1,2}):([0-9]{2})""")
        val match = regex.find(text)
        if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            return Pair(h, m)
        }
        return Pair(0, 0)
    }
}

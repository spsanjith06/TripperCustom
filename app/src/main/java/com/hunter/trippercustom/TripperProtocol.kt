package com.hunter.trippercustom

import java.util.UUID

object TripperProtocol {
    // RE Tripper Pod standard BLE identifiers
    val DEVICE_NAME_PREFIX = "RE_DISP"
    val SERVICE_UUID: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_UUID: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("0000fee2-0000-1000-8000-00805f9b34fb")

    // Maneuver Icons defined in the stock firmware
    const val ICON_STRAIGHT: Byte = 0x01
    const val ICON_TURN_LEFT: Byte = 0x02
    const val ICON_TURN_RIGHT: Byte = 0x03
    const val ICON_SLIGHT_LEFT: Byte = 0x04
    const val ICON_SLIGHT_RIGHT: Byte = 0x05
    const val ICON_SHARP_LEFT: Byte = 0x06
    const val ICON_SHARP_RIGHT: Byte = 0x07
    const val ICON_U_TURN: Byte = 0x08
    const val ICON_ROUNDABOUT_1: Byte = 0x09
    const val ICON_ROUNDABOUT_2: Byte = 0x0A
    const val ICON_ROUNDABOUT_3: Byte = 0x0B
    const val ICON_ROUNDABOUT_4: Byte = 0x0C
    const val ICON_ROUNDABOUT_5: Byte = 0x0D
    const val ICON_DESTINATION: Byte = 0x0E
    const val ICON_BLANK: Byte = 0x00

    // Units
    const val UNIT_METERS: Byte = 0x01
    const val UNIT_KILOMETERS: Byte = 0x02
    const val UNIT_FEET: Byte = 0x03
    const val UNIT_MILES: Byte = 0x04

    // Opcodes
    private const val HEADER_BYTE: Byte = 0xAA.toByte()
    private const val CMD_NAVIGATION: Byte = 0x10
    private const val CMD_CALLER_ID: Byte = 0x20
    private const val CMD_HEARTBEAT: Byte = 0x30

    /**
     * Builds navigation frame:
     * [HEADER, CMD, ICON, UNIT, DIST_HIGH, DIST_LOW, ETA_H, ETA_M, CHECKSUM]
     */
    fun createNavPacket(
        icon: Byte,
        distanceVal: Int,
        distanceUnit: Byte,
        etaHours: Int,
        etaMinutes: Int
    ): ByteArray {
        val distH = ((distanceVal shr 8) and 0xFF).toByte()
        val distL = (distanceVal and 0xFF).toByte()
        val h = (etaHours and 0xFF).toByte()
        val m = (etaMinutes and 0xFF).toByte()

        val packet = byteArrayOf(
            HEADER_BYTE,
            CMD_NAVIGATION,
            0x06, // Payload length
            icon,
            distanceUnit,
            distH,
            distL,
            h,
            m,
            0x00 // Placeholder for checksum
        )
        packet[packet.size - 1] = calculateChecksum(packet)
        return packet
    }

    /**
     * Builds incoming caller ID display packet to show contact name
     */
    fun createCallerPacket(callerName: String): ByteArray {
        val cleanName = callerName.take(16).padEnd(16, ' ')
        val nameBytes = cleanName.toByteArray(Charsets.US_ASCII)

        val packet = ByteArray(4 + nameBytes.size)
        packet[0] = HEADER_BYTE
        packet[1] = CMD_CALLER_ID
        packet[2] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, packet, 3, nameBytes.size)
        packet[packet.size - 1] = calculateChecksum(packet)
        return packet
    }

    /**
     * Keep-alive ping so pod doesn't go to sleep / disconnect during riding
     */
    fun createHeartbeatPacket(): ByteArray {
        val packet = byteArrayOf(HEADER_BYTE, CMD_HEARTBEAT, 0x00, 0x00)
        packet[packet.size - 1] = calculateChecksum(packet)
        return packet
    }

    private fun calculateChecksum(data: ByteArray): Byte {
        var sum = 0
        for (i in 0 until data.size - 1) {
            sum += (data[i].toInt() and 0xFF)
        }
        return (sum and 0xFF).toByte()
    }
}

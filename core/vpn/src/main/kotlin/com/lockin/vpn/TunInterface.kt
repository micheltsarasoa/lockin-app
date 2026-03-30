package com.lockin.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket

/**
 * Manages the TUN file descriptor opened by [VpnService.Builder].
 *
 * Provides:
 *   - [readPacket] — reads one IP packet from the TUN interface (blocking)
 *   - [writePacket] — writes one IP packet to the TUN interface (client sees it as incoming)
 *   - [protectSocket] — marks a socket to bypass the VPN (prevents routing loops)
 */
class TunInterface(
    private val tunFd: ParcelFileDescriptor,
    private val vpnService: VpnService,
) {
    private val inputStream = FileInputStream(tunFd.fileDescriptor)
    private val outputStream = FileOutputStream(tunFd.fileDescriptor)

    // Max IP packet size (MTU 1500 bytes, plus some headroom)
    private val readBuffer = ByteArray(32767)

    /**
     * Reads one packet from the TUN interface.
     * Returns the packet bytes, or null if the interface is closed.
     */
    fun readPacket(): ByteArray? {
        return try {
            val length = inputStream.read(readBuffer)
            if (length <= 0) null else readBuffer.copyOf(length)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Writes a packet to the TUN interface.
     * The OS will deliver this to the app that originated the corresponding request
     * (e.g., a synthesized DNS NXDOMAIN response).
     */
    fun writePacket(packet: ByteArray) {
        try {
            outputStream.write(packet)
        } catch (e: Exception) {
            // Interface may have been closed; VPN service handles restart
        }
    }

    /**
     * Marks a socket to bypass the VPN tunnel.
     * MUST be called on any socket used for real network I/O (DoH client, etc.)
     * to prevent packets from looping back through this VPN service.
     */
    fun protectSocket(socket: Socket): Boolean = vpnService.protect(socket)

    /** Closes the TUN file descriptor. The VPN service will restart if always-on is active. */
    fun close() {
        try {
            inputStream.close()
            outputStream.close()
            tunFd.close()
        } catch (e: Exception) {
            // Ignore — already closed
        }
    }
}

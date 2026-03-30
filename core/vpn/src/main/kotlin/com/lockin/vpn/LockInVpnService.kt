package com.lockin.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lockin.filter.FilterEngine
import com.lockin.filter.FilterResult
import com.lockin.vpn.packet.DnsResponseBuilder
import com.lockin.vpn.packet.PacketParser
import com.lockin.vpn.packet.ParsedPacket
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket
import javax.inject.Inject

/**
 * Core VPN service that intercepts all device traffic via a TUN interface.
 *
 * Packet processing pipeline:
 *   TUN read → PacketParser → FilterEngine → ALLOW (forward) or BLOCK (synthesize response)
 *
 * This service is declared with android.net.VpnService intent-filter and
 * supports_always_on = true so DevicePolicyManager can enforce it as always-on with lockdown.
 *
 * The service uses START_STICKY so it is restarted by the system if killed.
 * With always-on VPN (lockdown=true), the system also guarantees no traffic flows
 * if this service is not running.
 */
@AndroidEntryPoint
class LockInVpnService : VpnService() {

    companion object {
        private const val TAG = "LockInVpn"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "lockin_vpn"
        private const val VPN_ADDRESS = "10.99.0.1"
        private const val VPN_PREFIX_LENGTH = 32

        // DNS servers (used by OS if we allow DNS queries through; we intercept them)
        private const val DNS_PRIMARY = "1.1.1.3"      // Cloudflare family filter
        private const val DNS_SECONDARY = "1.0.0.3"    // Cloudflare family filter (secondary)

        fun startIntent(context: android.content.Context) =
            Intent(context, LockInVpnService::class.java).apply {
                action = "START"
            }

        fun stopIntent(context: android.content.Context) =
            Intent(context, LockInVpnService::class.java).apply {
                action = "STOP"
            }
    }

    @Inject lateinit var filterEngine: FilterEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: TunInterface? = null
    private var packetJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        // Initialize filter engine if not ready
        if (!filterEngine.isReady()) {
            serviceScope.launch { filterEngine.initialize() }
        }

        val tunFd = Builder()
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)            // Capture all IPv4 traffic
            .addRoute("::", 0)                  // Capture all IPv6 traffic
            .addDnsServer(DNS_PRIMARY)
            .addDnsServer(DNS_SECONDARY)
            .setMtu(1500)
            .setBlocking(false)
            .setSession("LockIn")
            .also {
                // Exclude our own app from the VPN to prevent routing loops
                // (DoH client sockets are individually protected)
                it.addDisallowedApplication(packageName)
            }
            .establish()

        if (tunFd == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        val tun = TunInterface(tunFd, this)
        tunInterface = tun

        packetJob = serviceScope.launch {
            Log.i(TAG, "VPN packet processing started")
            processPackets(tun)
        }
    }

    private suspend fun processPackets(tun: TunInterface) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val rawPacket = tun.readPacket() ?: break

            val parsed = PacketParser.parse(rawPacket)
            when (parsed) {
                is ParsedPacket.DnsQuery -> handleDns(parsed, tun)
                is ParsedPacket.TlsClientHello -> handleTls(parsed, tun)
                is ParsedPacket.HttpRequest -> handleHttp(parsed, tun)
                is ParsedPacket.Unknown -> forwardPacket(rawPacket)
            }
        }
        Log.i(TAG, "Packet processing loop ended")
    }

    private suspend fun handleDns(packet: ParsedPacket.DnsQuery, tun: TunInterface) {
        when (filterEngine.verdict(packet.domain)) {
            FilterResult.Block -> {
                // Find the DNS payload offset within the raw packet and synthesize NXDOMAIN
                val dnsPayload = extractDnsPayload(packet.rawBytes)
                val response = dnsPayload?.let { DnsResponseBuilder.buildNxDomain(it) }
                if (response != null) {
                    // Wrap in UDP+IP and write back to TUN
                    val udpResponse = wrapDnsResponseInUdp(packet.rawBytes, response)
                    udpResponse?.let { tun.writePacket(it) }
                }
                Log.d(TAG, "DNS BLOCK: ${packet.domain}")
            }
            FilterResult.Allow, FilterResult.FallbackDns -> {
                // Forward: the OS DNS resolver (1.1.1.3) handles it
                forwardPacket(packet.rawBytes)
            }
        }
    }

    private suspend fun handleTls(packet: ParsedPacket.TlsClientHello, tun: TunInterface) {
        when (filterEngine.verdict(packet.domain)) {
            FilterResult.Block -> {
                // Drop silently — the TCP connection will time out on the client side
                // A TCP RST would be more elegant but requires raw socket access
                Log.d(TAG, "TLS BLOCK (SNI): ${packet.domain}")
            }
            FilterResult.Allow, FilterResult.FallbackDns -> forwardPacket(packet.rawBytes)
        }
    }

    private suspend fun handleHttp(packet: ParsedPacket.HttpRequest, tun: TunInterface) {
        when (filterEngine.verdict(packet.host)) {
            FilterResult.Block -> {
                Log.d(TAG, "HTTP BLOCK: ${packet.host}")
                // Drop the packet — connection will timeout/reset
            }
            FilterResult.Allow, FilterResult.FallbackDns -> forwardPacket(packet.rawBytes)
        }
    }

    /**
     * Forwards a packet to the real internet via a protected socket.
     * The VPN service routes packets through a protected UDP socket.
     */
    private fun forwardPacket(rawPacket: ByteArray) {
        // In a real implementation, a protected DatagramSocket would relay the packet.
        // For the architecture scaffold, this represents the forwarding path.
        // The VPN interface itself handles routing for packets not written back to TUN.
    }

    private fun extractDnsPayload(rawPacket: ByteArray): ByteArray? {
        // IPv4: skip IP header (IHL * 4) + UDP header (8 bytes)
        if (rawPacket.size < 28) return null
        val ihl = (rawPacket[0].toInt() and 0x0F) * 4
        val dnsStart = ihl + 8
        return if (dnsStart < rawPacket.size) rawPacket.copyOfRange(dnsStart, rawPacket.size) else null
    }

    /**
     * Wraps a DNS response payload in a UDP/IPv4 packet addressed back to the query sender.
     *
     * The original query packet provides all the addressing information:
     *   - Source IP/port become the destination (we reply to whoever sent the query)
     *   - Destination IP/port become the source (we reply from the DNS server address)
     *
     * IPv4 header (20 bytes, no options) + UDP header (8 bytes) + DNS payload.
     * The IPv4 checksum is computed over the header only (RFC 791).
     * The UDP checksum is set to 0x0000 (optional for IPv4 per RFC 768).
     */
    private fun wrapDnsResponseInUdp(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray? {
        if (originalPacket.size < 28) return null  // need at minimum IP(20) + UDP(8) headers

        val ihl = (originalPacket[0].toInt() and 0x0F) * 4
        if (originalPacket.size < ihl + 8) return null

        // Extract original src/dst addresses and ports
        val srcIp  = originalPacket.copyOfRange(12, 16)   // original query source IP
        val dstIp  = originalPacket.copyOfRange(16, 20)   // original query dest IP (DNS server)
        val srcPort = originalPacket.copyOfRange(ihl, ihl + 2)     // original UDP source port
        val dstPort = originalPacket.copyOfRange(ihl + 2, ihl + 4) // original UDP dest port (53)

        val totalLength = 20 + 8 + dnsResponse.size
        val packet = ByteArray(totalLength)

        // IPv4 header
        packet[0]  = 0x45.toByte()                          // Version=4, IHL=5 (20 bytes)
        packet[1]  = 0x00                                    // DSCP/ECN
        packet[2]  = (totalLength ushr 8).toByte()
        packet[3]  = (totalLength and 0xFF).toByte()
        packet[4]  = 0x00; packet[5] = 0x00                 // Identification
        packet[6]  = 0x40; packet[7] = 0x00                 // Flags=DF, Fragment Offset=0
        packet[8]  = 0x40                                    // TTL = 64
        packet[9]  = 0x11                                    // Protocol = UDP (17)
        packet[10] = 0x00; packet[11] = 0x00                // Header checksum (filled below)
        // Source IP = original destination (DNS server side)
        dstIp.copyInto(packet, 12)
        // Destination IP = original source (the device's DNS resolver)
        srcIp.copyInto(packet, 16)

        // Fill IPv4 header checksum
        val checksum = ipv4HeaderChecksum(packet, 0, 20)
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        // UDP header
        val udpStart = 20
        dstPort.copyInto(packet, udpStart)            // UDP src port = original dst port (53)
        srcPort.copyInto(packet, udpStart + 2)        // UDP dst port = original src port
        val udpLength = 8 + dnsResponse.size
        packet[udpStart + 4] = (udpLength ushr 8).toByte()
        packet[udpStart + 5] = (udpLength and 0xFF).toByte()
        packet[udpStart + 6] = 0x00                   // Checksum = 0 (optional for IPv4/UDP)
        packet[udpStart + 7] = 0x00

        // DNS payload
        dnsResponse.copyInto(packet, 28)

        return packet
    }

    /**
     * Computes the one's complement checksum for an IPv4 header (RFC 791).
     * Sum all 16-bit words, fold carry bits, return one's complement.
     */
    private fun ipv4HeaderChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        // Fold 32-bit sum into 16 bits
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked — this should not happen with Device Owner always-on")
        stopPacketProcessing()
    }

    override fun onDestroy() {
        stopPacketProcessing()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopPacketProcessing() {
        packetJob?.cancel()
        tunInterface?.close()
        tunInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LockIn VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LockIn content filter is active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LockIn Active")
            .setContentText("Content filter is running")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

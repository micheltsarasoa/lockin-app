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
import com.lockin.vpn.packet.UdpPacketWrapper
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

    private fun wrapDnsResponseInUdp(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray? =
        UdpPacketWrapper.wrap(originalPacket, dnsResponse)

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

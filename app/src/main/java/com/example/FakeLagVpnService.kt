package com.example

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.experimental.and

class FakeLagVpnService : VpnService() {

    companion object {
        private const val TAG = "FakeLagVpnService"
        const val ACTION_START = "com.example.START_VPN"
        const val ACTION_STOP = "com.example.STOP_VPN"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Queue of packets for standard non-UDP simulation/delay
    private val packetQueue = ConcurrentLinkedQueue<DelayedPacket>()
    
    private class DelayedPacket(
        val data: ByteArray,
        val length: Int,
        var releaseTime: Long
    )

    // UDP NAT & Forwarding Maps
    private val udpSockets = ConcurrentHashMap<String, DatagramSocketInfo>()

    private class DatagramSocketInfo(
        val socket: DatagramSocket,
        val job: Job
    )

    private val teleportBuffer = ConcurrentLinkedQueue<TeleportPacket>()

    private class TeleportPacket(
        val srcIp: String,
        val srcPort: Int,
        val destIp: String,
        val destPort: Int,
        val payload: ByteArray
    )

    // TCP NAT & Proxy Mapping
    private val tcpConnections = ConcurrentHashMap<String, TcpConnectionInfo>()

    private inner class TcpConnectionInfo(
        val connectionKey: String,
        val srcIp: String,
        val srcPort: Int,
        val destIp: String,
        val destPort: Int,
        val clientSeq: Long,
        val serverSeq: Long
    ) {
        var socket: java.net.Socket? = null
        var job: Job? = null
        var isClosed = false
        var localSeq = 1000L
        var remoteAck = 0L

        fun close() {
            isClosed = true
            try {
                socket?.close()
            } catch (e: Exception) {}
            try {
                job?.cancel()
            } catch (e: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        if (vpnInterface != null) return
        
        FakeLagSettings.log("⚡ Khởi động VPN (Transparent IP Proxy)...", FakeLagSettings.LogType.INFO)
        try {
            val builder = Builder()
                .setSession("FakeLagVpn")
                .setMtu(1300) // Lower MTU for game stability
                .addAddress("10.8.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0) // IPv4
                .addRoute("::", 0)       // IPv6

            // Dynamic allowed applications routing
            val apps = FakeLagSettings.allowedApps.value
            var appsAdded = 0
            if (apps.isNotEmpty()) {
                builder.addRoute("0.0.0.0", 0)
                for (app in apps) {
                    try {
                        builder.addAllowedApplication(app)
                        FakeLagSettings.log("📡 Intercepting target app: $app", FakeLagSettings.LogType.SUCCESS)
                        appsAdded++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add allowed app $app", e)
                    }
                }
            }
            if (appsAdded == 0) {
                // Fallback: If neither Free Fire target is installed, intercept our own package so builder can establish and run
                try {
                    builder.addAllowedApplication(packageName)
                    builder.addRoute("0.0.0.0", 0)
                    FakeLagSettings.log("⚠️ Không tìm thấy Free Fire. Đang cấu hình VPN tự động chặn ứng dụng này để chạy thử nghiệm.", FakeLagSettings.LogType.WARNING)
                } catch (e: Exception) {
                    builder.addRoute("10.8.0.0", 24)
                    FakeLagSettings.log("⚠️ Chế độ VPN bypass hệ thống.", FakeLagSettings.LogType.WARNING)
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                FakeLagSettings.isVpnActive.value = true
                FakeLagSettings.log("✅ VPN Core Established. Intercepting packets...", FakeLagSettings.LogType.SUCCESS)
                FakeLagSettings.playBeep()
                startPacketLoop()
            } else {
                FakeLagSettings.log("❌ Thiết lập VPN thất bại - Builder return null", FakeLagSettings.LogType.ERROR)
            }
        } catch (e: Exception) {
            FakeLagSettings.log("❌ Lỗi VPN: ${e.localizedMessage}", FakeLagSettings.LogType.ERROR)
            stopVpn()
        }
    }

    private fun stopVpn() {
        vpnJob?.cancel()
        vpnJob = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        FakeLagSettings.isVpnActive.value = false
        
        // Clean up UDP sockets
        for (info in udpSockets.values) {
            try {
                info.socket.close()
                info.job.cancel()
            } catch (e: Exception) {
                // Ignore
            }
        }
        udpSockets.clear()

        // Clean up TCP sockets
        for (info in tcpConnections.values) {
            info.close()
        }
        tcpConnections.clear()
        teleportBuffer.clear()
        
        FakeLagSettings.log("🛑 Dừng VPN Core. Hoàn tất khôi phục kết nối.", FakeLagSettings.LogType.WARNING)
        FakeLagSettings.playBeep()
    }

    private fun startPacketLoop() {
        vpnJob = serviceScope.launch {
            val pfd = vpnInterface ?: return@launch
            val fis = FileInputStream(pfd.fileDescriptor)
            val fos = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteBuffer.allocate(65536) // Increased buffer for large packets

                    // Start delay release thread for any non-forwarded packets (e.g. general raw delay queue)
            val releaseJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val iterator = packetQueue.iterator()
                    while (iterator.hasNext()) {
                        val packet = iterator.next()
                        if (now >= packet.releaseTime) {
                            directWrite(packet.data, packet.length, fos)
                            iterator.remove()
                        }
                    }
                    delay(2)
                }
            }

            // Start teleport phase observer to release buffered UDP packets with staggered burst
            val teleportJob = launch {
                FakeLagSettings.teleportPhase.collect { phase ->
                    if (phase == 2) {
                        val count = teleportBuffer.size
                        if (count > 0) {
                            val window = FakeLagSettings.teleportReleaseWindow.value.toLong()
                            val delayPerPacket = if (count > 1) window / count else 0L
                            
                            FakeLagSettings.log("🌀 VPN: Releasing $count packets over ${window}ms...", FakeLagSettings.LogType.TELEPORT)
                            
                            var packet = teleportBuffer.poll()
                            while (packet != null) {
                                performUdpSend(
                                    srcIp = packet.srcIp,
                                    srcPort = packet.srcPort,
                                    destIp = packet.destIp,
                                    destPort = packet.destPort,
                                    payload = packet.payload,
                                    fos = fos
                                )
                                if (delayPerPacket > 0) delay(delayPerPacket)
                                packet = teleportBuffer.poll()
                            }
                        }
                    } else if (phase == 0) {
                        teleportBuffer.clear()
                    }
                }
            }

            try {
                while (isActive) {
                    buffer.clear()
                    val readBytes = withContext(Dispatchers.IO) { fis.read(buffer.array()) }
                    if (readBytes <= 0) {
                        delay(10)
                        continue
                    }

                    val packetData = buffer.array().copyOf(readBytes)
                    processIncomingPacket(packetData, readBytes, fos)
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error in packet loop", e)
                FakeLagSettings.log("❌ Lỗi luồng dữ liệu VPN: ${e.localizedMessage}", FakeLagSettings.LogType.ERROR)
            } finally {
                releaseJob.cancel()
                teleportJob.cancel()
            }
        }
    }

    private fun processIncomingPacket(packet: ByteArray, length: Int, fos: FileOutputStream) {
        if (length < 20) return // Invalid IP packet

        // Parse IPv4 header
        val versionAndIHL = packet[0]
        val version = (versionAndIHL.toInt() ushr 4) and 0x0F
        if (version != 4) {
            // Only parse IPv4 for simplicity, forward directly to prevent breaking connection
            directWrite(packet, length, fos)
            return
        }

        val protocol = packet[9].toInt()
        val srcIp = parseIp(packet, 12)
        val destIp = parseIp(packet, 16)

        val ihl = (packet[0].toInt() and 0x0F) * 4

        // Process TCP protocol packets with high-performance proxy (No interference/lag applied)
        if (protocol == 6) {
            val srcPort = parsePort(packet, ihl)
            val destPort = parsePort(packet, ihl + 2)
            handleTcpPacket(packet, length, ihl, srcIp, srcPort, destIp, destPort, fos)
            return
        }

        // Only handle UDP (17) for lag effects. TCP is proxied for stability.
        if (protocol == 6) {
            val srcPort = parsePort(packet, ihl)
            val destPort = parsePort(packet, ihl + 2)
            handleTcpPacket(packet, length, ihl, srcIp, srcPort, destIp, destPort, fos)
            return
        }

        if (protocol != 17) {
            directWrite(packet, length, fos)
            return
        }

        val srcPort = parsePort(packet, ihl)
        val destPort = parsePort(packet, ihl + 2)

        // DNS Bypass (Port 53)
        if (destPort == 53 || srcPort == 53) {
            val payloadStart = ihl + 8
            val payloadLength = length - payloadStart
            if (payloadLength > 0) {
                val payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength)
                performUdpSend(srcIp, srcPort, destIp, destPort, payload, fos)
            }
            return
        }

        val payloadStart = ihl + 8
        val payloadLength = length - payloadStart
        if (payloadLength <= 0) {
            directWrite(packet, length, fos)
            return
        }
        val payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength)

        val telePhase = FakeLagSettings.teleportPhase.value
        if (telePhase == 1) {
            if (length < 70) {
                // Heartbeat/Keep-alive: Allow only tiny packets to keep ping green.
                performUdpSend(srcIp, srcPort, destIp, destPort, payload, fos)
                return
            }
            // Phase 1: Buffer everything else (Movement + Damage) to release all at once at destination.
            teleportBuffer.add(TeleportPacket(srcIp, srcPort, destIp, destPort, payload))
            return
        }

        // LAG EFFECTS (Only on UDP)
        if (FakeLagSettings.isFreezeActive.value) {
            // Freeze logic handles inbound in performUdpSend, so we just forward outbound here
            performUdpSend(srcIp, srcPort, destIp, destPort, payload, fos)
            return
        }

        if (processGhostLogic(length, srcIp, srcPort, destIp, destPort, payload, fos)) return

        if (FakeLagSettings.bwBlockUpload.value) return

        val basePing = FakeLagSettings.simulatedPing.value
        val mode = FakeLagSettings.pingMode.value
        val jitter = FakeLagSettings.pingJitter.value
        val ping: Long = when (mode) {
            "Jitter" -> {
                if (jitter > 0) {
                    val randomOffset = (0..jitter).random() - (jitter / 2)
                    (basePing + randomOffset).coerceAtLeast(0).toLong()
                } else {
                    basePing.toLong()
                }
            }
            "Wave" -> {
                val timeOffset = (System.currentTimeMillis() / 800.0)
                val waveFactor = kotlin.math.sin(timeOffset)
                val waveOffset = (waveFactor * jitter).toInt()
                (basePing + waveOffset).coerceAtLeast(0).toLong()
            }
            else -> basePing.toLong() // Static
        }

        if (ping > 0) {
            serviceScope.launch {
                delay(ping)
                performUdpSend(srcIp, srcPort, destIp, destPort, payload, fos)
            }
        } else {
            performUdpSend(srcIp, srcPort, destIp, destPort, payload, fos)
        }
    }

    /**
     * GHOST MODE:
     * Chặn các gói di chuyển để server thấy đứng im (Tàng hình).
     * Cho phép gói sát thương/bắn (< 40 bytes hoặc > 180 bytes) đi qua ngay.
     */
    private fun processGhostLogic(
        length: Int,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        payload: ByteArray,
        fos: FileOutputStream
    ): Boolean {
        if (!FakeLagSettings.isGhostActive.value) return false

        // Dựa trên kích thước payload UDP (không phải tổng IP packet):
        val payloadLen = payload.size
        val minSize = FakeLagSettings.ghostMinSize.value
        val maxSize = FakeLagSettings.ghostMaxSize.value
        
        // Dải di chuyển chuẩn: 400-500 bytes
        if (payloadLen in minSize..maxSize) {
            // Chặn di chuyển -> Tàng hình
            return true
        }

        // Ngoài dải di chuyển (Bắn, Sát thương, Heartbeat) -> Cho phép ngay
        performUdpSend(srcIp, srcPort, destIp, destPort, payload, fos)
        return true
    }

    private fun performUdpSend(
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        payload: ByteArray,
        fos: FileOutputStream
    ) {
        val key = "$srcPort:$destIp:$destPort"
        var info = udpSockets[key]
        if (info == null || info.socket.isClosed) {
            try {
                val socket = DatagramSocket()
                protect(socket)
                socket.setSendBufferSize(65536)
                socket.setReceiveBufferSize(65536)
                socket.connect(InetAddress.getByName(destIp), destPort)
                
                // Start a coroutine to listen for replies from this socket
                val job = serviceScope.launch(Dispatchers.IO) {
                    val receiveBuffer = ByteArray(32768)
                    val recvPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    while (isActive && !socket.isClosed) {
                        try {
                            socket.receive(recvPacket)
                            val dataLength = recvPacket.length
                            val replyPayload = recvPacket.data.copyOf(dataLength)
                            
                            // 2. FREEZE LAG SWITCH (Inbound filter: f = "udp.PayloadLength >= 20 and udp.PayloadLength <= 500 and inbound")
                            if (FakeLagSettings.isFreezeActive.value) {
                                val minSize = FakeLagSettings.freezeMinSize.value
                                val maxSize = FakeLagSettings.freezeMaxSize.value
                                val dropRate = FakeLagSettings.freezeDropRate.value
                                
                                if (dataLength in minSize..maxSize) {
                                    val rand = (1..100).random()
                                    if (rand <= dropRate) {
                                        continue // Skip sending back to local app (Drop packet!)
                                    }
                                }
                            }
                            
                            // Send reply back to TUN interface immediately
                            val replyPacket = buildUdpPacket(
                                srcIp = destIp,
                                destIp = srcIp,
                                srcPort = destPort,
                                destPort = srcPort,
                                payload = replyPayload
                            )
                            directWrite(replyPacket, replyPacket.size, fos)
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
                info = DatagramSocketInfo(socket, job)
                udpSockets[key] = info
            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish UDP forwarding socket for $key", e)
                return
            }
        }

        try {
            val dp = DatagramPacket(payload, payload.size)
            info.socket.send(dp)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending UDP payload to $key", e)
            info.socket.close()
            info.job.cancel()
            udpSockets.remove(key)
        }
    }

    private fun buildUdpPacket(
        srcIp: String,
        destIp: String,
        srcPort: Int,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipLen = 20 + 8 + payload.size
        val packet = ByteArray(ipLen)

        // 1. IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte() // Version = 4, IHL = 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = (ipLen ushr 8).toByte()
        packet[3] = (ipLen and 0xFF).toByte()
        
        // ID
        val id = (0..65535).random()
        packet[4] = (id ushr 8).toByte()
        packet[5] = (id and 0xFF).toByte()
        
        packet[6] = 0x40.toByte() // Flags: Don't Fragment (DF = 1, MF = 0)
        packet[7] = 0x00.toByte()
        
        packet[8] = 64.toByte() // TTL
        packet[9] = 17.toByte() // Protocol: UDP
        
        // Src IP
        val srcParts = srcIp.split(".")
        packet[12] = srcParts[0].toInt().toByte()
        packet[13] = srcParts[1].toInt().toByte()
        packet[14] = srcParts[2].toInt().toByte()
        packet[15] = srcParts[3].toInt().toByte()
        
        // Dest IP
        val destParts = destIp.split(".")
        packet[16] = destParts[0].toInt().toByte()
        packet[17] = destParts[1].toInt().toByte()
        packet[18] = destParts[2].toInt().toByte()
        packet[19] = destParts[3].toInt().toByte()

        // Calculate and set IPv4 Header Checksum
        val checksum = calculateIpChecksum(packet, 20)
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        // 2. UDP Header (8 bytes)
        val udpLen = 8 + payload.size
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        packet[24] = (udpLen ushr 8).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00.toByte() // UDP checksum (0 means disabled/no checksum verification needed in IPv4)
        packet[27] = 0x00.toByte()

        // 3. Payload
        System.arraycopy(payload, 0, packet, 28, payload.size)

        return packet
    }

    private fun calculateIpChecksum(buf: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        var len = length
        while (len > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
            len -= 2
        }
        if (len > 0) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF)
    }

    private fun directWrite(packet: ByteArray, length: Int, fos: FileOutputStream) {
        try {
            fos.write(packet, 0, length)
        } catch (e: Exception) {
            // Interface closed
        }
    }

    private fun parseIp(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset+1].toInt() and 0xFF}.${packet[offset+2].toInt() and 0xFF}.${packet[offset+3].toInt() and 0xFF}"
    }

    private fun parsePort(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset+1].toInt() and 0xFF)
    }

    private fun handleTcpPacket(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
        fos: FileOutputStream
    ) {
        val tcpOffset = ihl
        if (length < tcpOffset + 20) return
        val flags = packet[tcpOffset + 13].toInt() and 0xFF
        val clientSeq = parseLong(packet, tcpOffset + 4)
        
        val key = "$srcIp:$srcPort->$destIp:$destPort"
        val isSyn = (flags and 0x02) != 0
        val isRst = (flags and 0x04) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0

        if (isSyn) {
            val info = TcpConnectionInfo(key, srcIp, srcPort, destIp, destPort, clientSeq, 1000L).apply {
                remoteAck = clientSeq + 1
            }
            tcpConnections[key]?.close()
            tcpConnections[key] = info
            
            // Respond with SYN-ACK immediately
            val synAck = buildTcpPacket(destIp, srcIp, destPort, srcPort, info.localSeq, info.remoteAck, 0x12.toByte(), ByteArray(0))
            directWrite(synAck, synAck.size, fos)
            info.localSeq++
            
            info.job = serviceScope.launch(Dispatchers.IO) {
                try {
                    val socket = java.net.Socket()
                    protect(socket)
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.soTimeout = 60000
                    socket.connect(java.net.InetSocketAddress(destIp, destPort), 10000)
                    info.socket = socket
                    
                    val input = socket.getInputStream()
                    val buffer = ByteArray(32768)
                    while (isActive && !info.isClosed) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            val data = buildTcpPacket(destIp, srcIp, destPort, srcPort, info.localSeq, info.remoteAck, 0x18.toByte(), buffer.copyOfRange(0, read))
                            directWrite(data, data.size, fos)
                            info.localSeq += read
                        }
                    }
                } catch (e: Exception) {
                    val rst = buildTcpPacket(destIp, srcIp, destPort, srcPort, info.localSeq, info.remoteAck, 0x04.toByte(), ByteArray(0))
                    directWrite(rst, rst.size, fos)
                } finally {
                    info.close()
                    tcpConnections.remove(key)
                }
            }
            return
        }

        val info = tcpConnections[key] ?: return
        if (isRst || isFin) {
            info.close()
            tcpConnections.remove(key)
            return
        }

        val dataOffset = ((packet[tcpOffset + 12].toInt() and 0xF0) ushr 4) * 4
        val payloadLen = length - (tcpOffset + dataOffset)
        if (payloadLen > 0 && isAck) {
            val payload = packet.copyOfRange(tcpOffset + dataOffset, length)
            info.remoteAck = clientSeq + payloadLen
            serviceScope.launch(Dispatchers.IO) {
                try {
                    info.socket?.getOutputStream()?.write(payload)
                } catch (e: Exception) {
                    info.close()
                }
            }
        }
    }

    private fun buildTcpPacket(
        srcIp: String,
        destIp: String,
        srcPort: Int,
        destPort: Int,
        seq: Long,
        ack: Long,
        flags: Byte,
        payload: ByteArray
    ): ByteArray {
        val ipLen = 20 + 20 + payload.size
        val buffer = ByteBuffer.allocate(ipLen)
        
        // --- IP Header (20 bytes) ---
        buffer.put(0x45.toByte())
        buffer.put(0x00.toByte())
        buffer.putShort(ipLen.toShort())
        buffer.putShort(0.toShort())
        buffer.putShort(0x4000.toShort())
        buffer.put(64.toByte())
        buffer.put(6.toByte())
        buffer.putShort(0.toShort())
        
        val srcBytes = InetAddress.getByName(srcIp).address
        val destBytes = InetAddress.getByName(destIp).address
        buffer.put(srcBytes)
        buffer.put(destBytes)
        
        // --- TCP Header (20 bytes) ---
        buffer.putShort(srcPort.toShort())
        buffer.putShort(destPort.toShort())
        buffer.putInt(seq.toInt())
        buffer.putInt(ack.toInt())
        buffer.put(0x50.toByte())
        buffer.put(flags)
        buffer.putShort(0xFFFF.toShort())
        buffer.putShort(0.toShort())
        buffer.putShort(0.toShort())
        
        buffer.put(payload)
        
        val packet = buffer.array()
        
        val ipChecksum = calculateIpChecksum(packet, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()
        
        val tcpChecksum = calculateTcpChecksum(packet, 20, 20 + payload.size, srcBytes, destBytes)
        packet[36] = (tcpChecksum shr 8).toByte()
        packet[37] = (tcpChecksum and 0xFF).toByte()
        
        return packet
    }

    private fun calculateTcpChecksum(
        packet: ByteArray,
        offset: Int,
        length: Int,
        srcIp: ByteArray,
        destIp: ByteArray
    ): Int {
        var sum = 0
        
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        
        sum += ((destIp[0].toInt() and 0xFF) shl 8) or (destIp[1].toInt() and 0xFF)
        sum += ((destIp[2].toInt() and 0xFF) shl 8) or (destIp[3].toInt() and 0xFF)
        
        sum += 6
        sum += length
        
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        
        return (sum.inv()) and 0xFFFF
    }

    private fun parseLong(packet: ByteArray, offset: Int): Long {
        return ((packet[offset].toLong() and 0xFF) shl 24) or
                ((packet[offset + 1].toLong() and 0xFF) shl 16) or
                ((packet[offset + 2].toLong() and 0xFF) shl 8) or
                (packet[offset + 3].toLong() and 0xFF)
    }
}

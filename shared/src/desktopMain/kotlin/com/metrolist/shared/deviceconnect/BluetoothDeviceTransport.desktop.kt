/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Off-router Bluetooth RFCOMM fallback transport for Windows Desktop via
 * JNA bindings over native Winsock2 (ws2_32.dll) and BluetoothApis.dll.
 * Returns null on non-Windows platforms or when no Bluetooth stack is present.
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.utils.Logger
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.Reference
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

private const val DESKTOP_BT_TAG = "DeviceConnectBt"

internal actual fun createBluetoothFallbackTransport(
    context: Any?,
    onError: (message: String, throwable: Throwable?) -> Unit,
): LocalDeviceTransport? {
    if (!isWindowsDesktop()) return null
    val ws2 = runCatching { Ws232Lib.INSTANCE }.getOrNull() ?: return null
    if (!ensureWinsockInitialized(ws2, onError)) return null
    return WindowsBluetoothFallbackTransport(ws2, onError)
}

internal fun isWindowsDesktop(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

internal fun macAddressToWindowsBtAddr(mac: String): Long? {
    val normalized = normalizeBluetoothMac(mac) ?: return null
    val parts = normalized.split(':')
    var result = 0L
    for (part in parts) {
        result = (result shl 8) or (part.toInt(16).toLong() and 0xffL)
    }
    return result
}

internal fun windowsBtAddrToMacAddress(btAddr: Long): String? {
    if (btAddr == 0L) return null
    val formatted =
        (5 downTo 0).joinToString(":") { shiftBytes ->
            val byteVal = ((btAddr ushr (shiftBytes * 8)) and 0xffL).toInt()
            byteVal.toString(16).padStart(2, '0').uppercase()
        }
    return normalizeBluetoothMac(formatted)
}

@Volatile
private var winsockReady = false
private val winsockInitLock = Any()

private fun ensureWinsockInitialized(
    ws2: Ws232Lib,
    onError: (String, Throwable?) -> Unit,
): Boolean {
    if (winsockReady) return true
    return synchronized(winsockInitLock) {
        if (winsockReady) return@synchronized true
        val wsaData = Memory(512)
        val result = ws2.WSAStartup(0x0202, wsaData)
        if (result != 0) {
            onError("Winsock WSAStartup failed for Bluetooth fallback (code=$result).", null)
            false
        } else {
            winsockReady = true
            true
        }
    }
}

private class WindowsBluetoothFallbackTransport(
    private val ws2: Ws232Lib,
    private val onError: (String, Throwable?) -> Unit,
) : LocalDeviceTransport {
    override val kind: DeviceTransportKind = DeviceTransportKind.BLUETOOTH_RFCOMM

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val serviceUuid = UUID.fromString(DEVICE_CONNECT_BT_SERVICE_UUID)

    @Volatile
    private var listeningSocket: WinDef.UINT_PTR? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var sdpRegistration: SdpRegistration? = null

    override val localAddress: String?
        get() = readLocalWindowsBluetoothAddress()

    override fun startServer(
        localInfo: DeviceInfoPayload,
        onChannel: suspend (DeviceChannel) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        stopServer()
        val sock = ws2.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (isInvalidSocket(sock)) {
            val err = ws2.WSAGetLastError()
            onError("Could not open Windows Bluetooth RFCOMM socket (WSA=$err).", null)
            return false
        }
        val bindAddr =
            SockAddrBth().apply {
                addressFamily = AF_BTH.toShort()
                btAddr = 0L
                port = BT_PORT_ANY
            }
        bindAddr.write()
        if (ws2.bind(sock, bindAddr.pointer, bindAddr.size()) != 0) {
            val err = ws2.WSAGetLastError()
            ws2.closesocket(sock)
            onError("Could not bind Windows Bluetooth RFCOMM socket (WSA=$err). Ensure Bluetooth is on.", null)
            return false
        }
        val boundAddr = SockAddrBth()
        val addrLen = IntByReference(boundAddr.size())
        if (ws2.getsockname(sock, boundAddr.pointer, addrLen) != 0) {
            val err = ws2.WSAGetLastError()
            ws2.closesocket(sock)
            onError("Could not inspect bound Windows Bluetooth RFCOMM port (WSA=$err).", null)
            return false
        }
        boundAddr.read()

        if (ws2.listen(sock, 4) != 0) {
            val err = ws2.WSAGetLastError()
            ws2.closesocket(sock)
            onError("Could not listen on Windows Bluetooth RFCOMM socket (WSA=$err).", null)
            return false
        }

        val registration = registerSdpService(boundAddr, addrLen.value, localInfo.deviceId)
        if (registration == null) {
            val err = ws2.WSAGetLastError()
            ws2.closesocket(sock)
            onError("Could not register Windows Bluetooth SDP service (WSA=$err).", null)
            return false
        }

        synchronized(lock) {
            listeningSocket = sock
            sdpRegistration = registration
            acceptJob =
                scope.launch {
                    while (isActive) {
                        val peerAddr = SockAddrBth()
                        val peerLen = IntByReference(peerAddr.size())
                        val client = ws2.accept(sock, peerAddr.pointer, peerLen)
                        if (isInvalidSocket(client)) {
                            if (listeningSocket == sock && isActive) {
                                val err = ws2.WSAGetLastError()
                                onFailure(IOException("Windows Bluetooth accept failed (WSA=$err)"))
                            }
                            break
                        }
                        peerAddr.read()
                        val mac = windowsBtAddrToMacAddress(peerAddr.btAddr)
                        val remoteHost = mac?.let { "$DEVICE_CONNECT_BT_PREFIX$it" }
                        val channel = createWinsockChannel(client, remoteHost)
                        launch {
                            onChannel(channel)
                        }
                    }
                }
        }
        Logger.i(DESKTOP_BT_TAG, "Windows Bluetooth RFCOMM server started on channel ${boundAddr.port}.")
        return true
    }

    private fun registerSdpService(
        boundAddr: SockAddrBth,
        addrLength: Int,
        deviceId: String,
    ): SdpRegistration? {
        val guid = uuidToWinGuid(serviceUuid)
        guid.write()
        val csAddr =
            CsAddrInfo().apply {
                localAddr.lpSockaddr = boundAddr.pointer
                localAddr.iSockaddrLength = addrLength
                remoteAddr.lpSockaddr = boundAddr.pointer
                remoteAddr.iSockaddrLength = addrLength
                iSocketType = SOCK_STREAM
                iProtocol = BTHPROTO_RFCOMM
            }
        csAddr.write()
        val querySet =
            WsaQuerySetW().apply {
                dwSize = size()
                lpszServiceInstanceName = WString("Metrolist-$deviceId")
                lpServiceClassId = guid.pointer
                dwNameSpace = NS_BTH
                dwNumberOfCsAddrs = 1
                lpcsaBuffer = csAddr.pointer
            }
        querySet.write()
        val registration =
            SdpRegistration(
                querySet = querySet,
                guid = guid,
                csAddr = csAddr,
                boundAddr = boundAddr,
            )
        val status = ws2.WSASetServiceW(registration.querySet.pointer, RNRSERVICE_REGISTER, 0)
        if (status != 0) {
            registration.release()
            return null
        }
        return registration
    }

    override fun stopServer() {
        val (sock, job, registration) =
            synchronized(lock) {
                Triple(
                    listeningSocket.also { listeningSocket = null },
                    acceptJob.also { acceptJob = null },
                    sdpRegistration.also { sdpRegistration = null },
                )
            }
        job?.cancel()
        if (registration != null) {
            try {
                runCatching { ws2.WSASetServiceW(registration.querySet.pointer, RNRSERVICE_DELETE, 0) }
            } finally {
                registration.release()
            }
        }
        if (sock != null && !isInvalidSocket(sock)) {
            runCatching { ws2.closesocket(sock) }
        }
    }

    private class SdpRegistration(
        val querySet: WsaQuerySetW,
        private val guid: Guid.GUID,
        private val csAddr: CsAddrInfo,
        private val boundAddr: SockAddrBth,
    ) {
        fun release() {
            Reference.reachabilityFence(querySet)
            Reference.reachabilityFence(guid)
            Reference.reachabilityFence(csAddr)
            Reference.reachabilityFence(boundAddr)
            querySet.lpszServiceInstanceName = null
            querySet.lpServiceClassId = null
            querySet.lpcsaBuffer = null
            csAddr.localAddr.lpSockaddr = null
            csAddr.remoteAddr.lpSockaddr = null
        }
    }

    override fun startDiscovery(
        localInfo: DeviceInfoPayload,
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        scope.launch {
            val peers = findPairedWindowsBluetoothPeers(localInfo.deviceId)
            for (peer in peers) {
                onFound(peer)
            }
        }
    }

    override suspend fun stopDiscovery() = Unit

    override suspend fun connect(
        host: String,
        port: Int,
    ): DeviceChannel =
        withContext(Dispatchers.IO) {
            val rawMac = host.removePrefix(DEVICE_CONNECT_BT_PREFIX)
            val mac = normalizeBluetoothMac(rawMac) ?: throw IllegalArgumentException("Invalid Bluetooth MAC: $host")
            val btAddress = macAddressToWindowsBtAddr(mac) ?: throw IllegalArgumentException("Invalid Bluetooth MAC: $host")
            val sock = ws2.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
            if (isInvalidSocket(sock)) {
                val err = ws2.WSAGetLastError()
                throw IOException("Could not open Windows Bluetooth socket (WSA=$err)")
            }
            try {
                val remoteAddr =
                    SockAddrBth().apply {
                        addressFamily = AF_BTH.toShort()
                        btAddr = btAddress
                        setServiceClassUuid(serviceUuid)
                        this.port = 0
                    }
                remoteAddr.write()
                if (ws2.connect(sock, remoteAddr.pointer, remoteAddr.size()) != 0) {
                    val err = ws2.WSAGetLastError()
                    throw IOException("Windows Bluetooth connect to $mac failed (WSA=$err)")
                }
                createWinsockChannel(sock, "$DEVICE_CONNECT_BT_PREFIX$mac")
            } catch (error: Throwable) {
                runCatching { ws2.closesocket(sock) }
                if (error is CancellationException) throw error
                throw error
            }
        }

    override suspend fun close() {
        stopDiscovery()
        stopServer()
        scope.cancel()
    }

    private fun createWinsockChannel(
        socket: WinDef.UINT_PTR,
        remoteHost: String?,
    ): DeviceChannel {
        val closed = AtomicBoolean(false)
        val closeSocket = {
            if (closed.compareAndSet(false, true)) {
                runCatching { ws2.shutdown(socket, SD_BOTH) }
                runCatching { ws2.closesocket(socket) }
            }
        }
        val input = WinsockInputStream(ws2, socket, closed)
        val output = WinsockOutputStream(ws2, socket, closed)
        return createJvmStreamDeviceChannel(
            remoteHost = remoteHost,
            input = input,
            output = output,
            onClose = closeSocket,
        )
    }

    private fun readLocalWindowsBluetoothAddress(): String? =
        runCatching {
            val api = BluetoothApisLib.INSTANCE
            val params = BluetoothFindRadioParams().apply { dwSize = size() }
            val radioRef = WinNT.HANDLEByReference()
            val findHandle = api.BluetoothFindFirstRadio(params, radioRef) ?: return null
            try {
                val radio = radioRef.value ?: return null
                try {
                    val info = BluetoothRadioInfo().apply { dwSize = size() }
                    val res = api.BluetoothGetRadioInfo(radio, info)
                    if (res == 0) {
                        info.read()
                        windowsBtAddrToMacAddress(info.address)
                    } else {
                        null
                    }
                } finally {
                    Kernel32.INSTANCE.CloseHandle(radio)
                }
            } finally {
                api.BluetoothFindRadioClose(findHandle)
            }
        }.getOrNull()

    private fun findPairedWindowsBluetoothPeers(localDeviceId: String): List<DiscoveredDevice> =
        runCatching {
            val api = BluetoothApisLib.INSTANCE
            val searchParams =
                BluetoothDeviceSearchParams().apply {
                    dwSize = size()
                    fReturnAuthenticated = 1
                    fReturnRemembered = 1
                    fReturnConnected = 1
                    fReturnUnknown = 0
                    fIssueInquiry = 0
                    cTimeoutMultiplier = 0
                    hRadio = null
                }
            val deviceInfo = BluetoothDeviceInfo().apply { dwSize = size() }
            val findHandle = api.BluetoothFindFirstDevice(searchParams, deviceInfo) ?: return emptyList()
            val results = mutableListOf<DiscoveredDevice>()
            try {
                do {
                    deviceInfo.read()
                    val mac = windowsBtAddrToMacAddress(deviceInfo.address)
                    if (mac != null) {
                        val name = Native.toString(deviceInfo.szName).ifBlank { mac }
                        val synthesizedId = "bt-${mac.replace(":", "")}"
                        if (synthesizedId != localDeviceId) {
                            results +=
                                DiscoveredDevice(
                                    serviceName = "bt-$mac",
                                    deviceId = synthesizedId,
                                    deviceName = sanitizeDeviceName(name).ifBlank { mac },
                                    host = "$DEVICE_CONNECT_BT_PREFIX$mac",
                                    port = DEVICE_CONNECT_BT_PORT,
                                )
                        }
                    }
                } while (api.BluetoothFindNextDevice(findHandle, deviceInfo))
            } finally {
                api.BluetoothFindDeviceClose(findHandle)
            }
            results
        }.getOrDefault(emptyList())
}

private class WinsockInputStream(
    private val ws2: Ws232Lib,
    private val socket: WinDef.UINT_PTR,
    private val closed: AtomicBoolean,
) : InputStream() {
    private val singleByte = ByteArray(1)

    override fun read(): Int {
        val n = read(singleByte, 0, 1)
        return if (n <= 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (closed.get()) return -1
        val buffer = Memory(len.toLong())
        val received = ws2.recv(socket, buffer, len, 0)
        if (received <= 0) return -1
        buffer.read(0, b, off, received)
        return received
    }
}

private class WinsockOutputStream(
    private val ws2: Ws232Lib,
    private val socket: WinDef.UINT_PTR,
    private val closed: AtomicBoolean,
) : OutputStream() {
    private val singleByte = ByteArray(1)

    override fun write(b: Int) {
        singleByte[0] = b.toByte()
        write(singleByte, 0, 1)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (len == 0) return
        if (closed.get()) throw IOException("Winsock Bluetooth channel closed")
        val buffer = Memory(len.toLong())
        buffer.write(0, b, off, len)
        var sentTotal = 0
        while (sentTotal < len) {
            val remaining = len - sentTotal
            val sent = ws2.send(socket, buffer.share(sentTotal.toLong()), remaining, 0)
            if (sent <= 0) {
                val err = ws2.WSAGetLastError()
                throw IOException("Winsock send failed (WSA=$err)")
            }
            sentTotal += sent
        }
    }
}

private fun isInvalidSocket(socket: WinDef.UINT_PTR): Boolean {
    val raw = socket.toLong()
    return if (Native.POINTER_SIZE == 8) raw == -1L else (raw and 0xFFFFFFFFL) == 0xFFFFFFFFL
}

private fun uuidToWinGuid(uuid: UUID): Guid.GUID {
    val msb = uuid.mostSignificantBits
    val lsb = uuid.leastSignificantBits
    val data4 =
        ByteArray(8) { idx ->
            ((lsb ushr ((7 - idx) * 8)) and 0xffL).toByte()
        }
    return Guid.GUID().apply {
        Data1 = (msb ushr 32).toInt()
        Data2 = ((msb ushr 16) and 0xffffL).toShort()
        Data3 = (msb and 0xffffL).toShort()
        Data4 = data4
    }
}

@Suppress("FunctionName", "ktlint:standard:function-naming")
private interface Ws232Lib : StdCallLibrary {
    fun WSAStartup(
        wVersionRequested: Int,
        lpWSAData: Pointer,
    ): Int

    fun WSAGetLastError(): Int

    fun socket(
        af: Int,
        type: Int,
        protocol: Int,
    ): WinDef.UINT_PTR

    fun bind(
        s: WinDef.UINT_PTR,
        name: Pointer,
        namelen: Int,
    ): Int

    fun listen(
        s: WinDef.UINT_PTR,
        backlog: Int,
    ): Int

    fun accept(
        s: WinDef.UINT_PTR,
        addr: Pointer?,
        addrlen: IntByReference?,
    ): WinDef.UINT_PTR

    fun connect(
        s: WinDef.UINT_PTR,
        name: Pointer,
        namelen: Int,
    ): Int

    fun getsockname(
        s: WinDef.UINT_PTR,
        name: Pointer,
        namelen: IntByReference,
    ): Int

    fun recv(
        s: WinDef.UINT_PTR,
        buf: Pointer,
        len: Int,
        flags: Int,
    ): Int

    fun send(
        s: WinDef.UINT_PTR,
        buf: Pointer,
        len: Int,
        flags: Int,
    ): Int

    fun shutdown(
        s: WinDef.UINT_PTR,
        how: Int,
    ): Int

    fun closesocket(s: WinDef.UINT_PTR): Int

    fun WSASetServiceW(
        lpqsRegInfo: Pointer,
        essoperation: Int,
        dwControlFlags: Int,
    ): Int

    companion object {
        val INSTANCE: Ws232Lib by lazy {
            Native.load("ws2_32", Ws232Lib::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

@Suppress("FunctionName", "ktlint:standard:function-naming")
private interface BluetoothApisLib : StdCallLibrary {
    fun BluetoothFindFirstRadio(
        pbtfrp: BluetoothFindRadioParams,
        phRadio: WinNT.HANDLEByReference,
    ): WinNT.HANDLE?

    fun BluetoothFindRadioClose(hFind: WinNT.HANDLE): Boolean

    fun BluetoothGetRadioInfo(
        hRadio: WinNT.HANDLE,
        pRadioInfo: BluetoothRadioInfo,
    ): Int

    fun BluetoothFindFirstDevice(
        pbtsp: BluetoothDeviceSearchParams,
        pbtdi: BluetoothDeviceInfo,
    ): WinNT.HANDLE?

    fun BluetoothFindNextDevice(
        hFind: WinNT.HANDLE,
        pbtdi: BluetoothDeviceInfo,
    ): Boolean

    fun BluetoothFindDeviceClose(hFind: WinNT.HANDLE): Boolean

    companion object {
        val INSTANCE: BluetoothApisLib by lazy {
            Native.load("BluetoothApis", BluetoothApisLib::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }
}

@Structure.FieldOrder("addressFamily", "btAddr", "serviceData1", "serviceData2", "serviceData3", "serviceData4", "port")
internal class SockAddrBth : Structure(ALIGN_NONE) {
    @JvmField var addressFamily: Short = AF_BTH.toShort()

    @JvmField var btAddr: Long = 0L

    @JvmField var serviceData1: Int = 0

    @JvmField var serviceData2: Short = 0

    @JvmField var serviceData3: Short = 0

    @JvmField var serviceData4: ByteArray = ByteArray(8)

    @JvmField var port: Int = 0

    fun setServiceClassUuid(uuid: UUID) {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        serviceData1 = (msb ushr 32).toInt()
        serviceData2 = ((msb ushr 16) and 0xffffL).toShort()
        serviceData3 = (msb and 0xffffL).toShort()
        for (idx in 0..7) {
            serviceData4[idx] = ((lsb ushr ((7 - idx) * 8)) and 0xffL).toByte()
        }
    }
}

@Structure.FieldOrder("lpSockaddr", "iSockaddrLength")
internal class SocketAddress : Structure() {
    @JvmField var lpSockaddr: Pointer? = null

    @JvmField var iSockaddrLength: Int = 0
}

@Structure.FieldOrder("localAddr", "remoteAddr", "iSocketType", "iProtocol")
internal class CsAddrInfo : Structure() {
    @JvmField var localAddr: SocketAddress = SocketAddress()

    @JvmField var remoteAddr: SocketAddress = SocketAddress()

    @JvmField var iSocketType: Int = 0

    @JvmField var iProtocol: Int = 0
}

@Structure.FieldOrder(
    "dwSize",
    "lpszServiceInstanceName",
    "lpServiceClassId",
    "lpVersion",
    "lpszComment",
    "dwNameSpace",
    "lpNSProviderId",
    "lpszContext",
    "dwNumberOfProtocols",
    "lpafpProtocols",
    "lpszQueryString",
    "dwNumberOfCsAddrs",
    "lpcsaBuffer",
    "dwOutputFlags",
    "lpBlob",
)
internal class WsaQuerySetW : Structure() {
    @JvmField var dwSize: Int = 0

    @JvmField var lpszServiceInstanceName: WString? = null

    @JvmField var lpServiceClassId: Pointer? = null

    @JvmField var lpVersion: Pointer? = null

    @JvmField var lpszComment: WString? = null

    @JvmField var dwNameSpace: Int = 0

    @JvmField var lpNSProviderId: Pointer? = null

    @JvmField var lpszContext: WString? = null

    @JvmField var dwNumberOfProtocols: Int = 0

    @JvmField var lpafpProtocols: Pointer? = null

    @JvmField var lpszQueryString: WString? = null

    @JvmField var dwNumberOfCsAddrs: Int = 0

    @JvmField var lpcsaBuffer: Pointer? = null

    @JvmField var dwOutputFlags: Int = 0

    @JvmField var lpBlob: Pointer? = null
}

@Structure.FieldOrder("dwSize")
internal class BluetoothFindRadioParams : Structure() {
    @JvmField var dwSize: Int = 0
}

@Structure.FieldOrder("dwSize", "address", "szName", "ulClassofDevice", "lmpSubversion", "manufacturer")
internal class BluetoothRadioInfo : Structure() {
    @JvmField var dwSize: Int = 0

    @JvmField var address: Long = 0L

    @JvmField var szName: CharArray = CharArray(248)

    @JvmField var ulClassofDevice: Int = 0

    @JvmField var lmpSubversion: Short = 0

    @JvmField var manufacturer: Short = 0
}

@Structure.FieldOrder(
    "dwSize",
    "fReturnAuthenticated",
    "fReturnRemembered",
    "fReturnUnknown",
    "fReturnConnected",
    "fIssueInquiry",
    "cTimeoutMultiplier",
    "hRadio",
)
internal class BluetoothDeviceSearchParams : Structure() {
    @JvmField var dwSize: Int = 0

    @JvmField var fReturnAuthenticated: Int = 0

    @JvmField var fReturnRemembered: Int = 0

    @JvmField var fReturnUnknown: Int = 0

    @JvmField var fReturnConnected: Int = 0

    @JvmField var fIssueInquiry: Int = 0

    @JvmField var cTimeoutMultiplier: Byte = 0

    @JvmField var hRadio: WinNT.HANDLE? = null
}

@Structure.FieldOrder("wYear", "wMonth", "wDayOfWeek", "wDay", "wHour", "wMinute", "wSecond", "wMilliseconds")
internal class WinSystemTime : Structure() {
    @JvmField var wYear: Short = 0

    @JvmField var wMonth: Short = 0

    @JvmField var wDayOfWeek: Short = 0

    @JvmField var wDay: Short = 0

    @JvmField var wHour: Short = 0

    @JvmField var wMinute: Short = 0

    @JvmField var wSecond: Short = 0

    @JvmField var wMilliseconds: Short = 0
}

@Structure.FieldOrder(
    "dwSize",
    "address",
    "ulClassofDevice",
    "fConnected",
    "fRemembered",
    "fAuthenticated",
    "stLastSeen",
    "stLastUsed",
    "szName",
)
internal class BluetoothDeviceInfo : Structure() {
    @JvmField var dwSize: Int = 0

    @JvmField var address: Long = 0L

    @JvmField var ulClassofDevice: Int = 0

    @JvmField var fConnected: Int = 0

    @JvmField var fRemembered: Int = 0

    @JvmField var fAuthenticated: Int = 0

    @JvmField var stLastSeen: WinSystemTime = WinSystemTime()

    @JvmField var stLastUsed: WinSystemTime = WinSystemTime()

    @JvmField var szName: CharArray = CharArray(248)
}

private const val AF_BTH = 32
private const val SOCK_STREAM = 1
private const val BTHPROTO_RFCOMM = 3
private const val BT_PORT_ANY = -1
private const val NS_BTH = 16
private const val RNRSERVICE_REGISTER = 0
private const val RNRSERVICE_DELETE = 2
private const val SD_BOTH = 2

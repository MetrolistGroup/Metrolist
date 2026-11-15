/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Off-router Bluetooth RFCOMM + BLE discovery fallback transport for Android.
 * Only instantiated and started when the user enables isolated-Wi-Fi fallback
 * or explicitly initiates a bt:// connection.
 */

package com.metrolist.shared.deviceconnect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

private const val BT_TAG = "DeviceConnectBt"

// 16-bit Service UUID used strictly for compact BLE proximity advertisements (31-byte limit).
private val BLE_DISCOVERY_SERVICE_UUID: ParcelUuid =
    ParcelUuid.fromString("0000FD87-0000-1000-8000-00805F9B34FB")

private val RFCOMM_SERVICE_UUID: UUID = UUID.fromString(DEVICE_CONNECT_BT_SERVICE_UUID)

internal actual fun createBluetoothFallbackTransport(
    context: Any?,
    onError: (message: String, throwable: Throwable?) -> Unit,
): LocalDeviceTransport? {
    val ctx = (context as? Context)?.applicationContext ?: return null
    val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
    val adapter = manager.adapter ?: return null
    return AndroidBluetoothFallbackTransport(ctx, adapter, onError)
}

private class AndroidBluetoothFallbackTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onError: (String, Throwable?) -> Unit,
) : LocalDeviceTransport {
    override val kind: DeviceTransportKind = DeviceTransportKind.BLUETOOTH_RFCOMM

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()

    @Volatile
    private var rfcommServerSocket: BluetoothServerSocket? = null

    @Volatile
    private var l2capServerSocket: BluetoothServerSocket? = null

    @Volatile
    private var l2capPsm: Int = 0

    @Volatile
    private var rfcommAcceptJob: Job? = null

    @Volatile
    private var l2capAcceptJob: Job? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanCallback: ScanCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var discoveryReceiver: BroadcastReceiver? = null
    private val discoveredByKey = ConcurrentHashMap<String, DiscoveredDevice>()
    private var localDeviceId: String = ""

    @get:SuppressLint("HardwareIds")
    override val localAddress: String?
        get() {
            if (!hasConnectPermission()) return null
            val raw = runCatching { adapter.address }.getOrNull().orEmpty()
            val normalized = normalizeBluetoothMac(raw) ?: return null
            // Android 6+ returns 02:00:00:00:00:00 to unprivileged apps; hide placeholder MACs.
            return normalized.takeUnless { it == ANDROID_MASKED_BLUETOOTH_MAC }
        }

    @SuppressLint("MissingPermission")
    override fun startServer(
        localInfo: DeviceInfoPayload,
        onChannel: suspend (DeviceChannel) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        if (!hasConnectPermission()) {
            onError("Bluetooth connect permission is required for isolated-Wi-Fi fallback.", null)
            return false
        }
        if (!adapter.isEnabled) {
            onError("Bluetooth adapter is disabled.", null)
            return false
        }
        stopServer()
        localDeviceId = localInfo.deviceId

        val serviceName = "Metrolist-${localInfo.deviceId}"
        val rfcommSocket =
            runCatching {
                adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, RFCOMM_SERVICE_UUID)
            }.recoverCatching {
                adapter.listenUsingRfcommWithServiceRecord(serviceName, RFCOMM_SERVICE_UUID)
            }.getOrElse { error ->
                onError("Could not start Bluetooth RFCOMM server: ${error.message}", error)
                return false
            }

        var optionalL2cap: BluetoothServerSocket? = null
        var psm = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            optionalL2cap =
                runCatching {
                    adapter.listenUsingInsecureL2capChannel().also { sock ->
                        psm = sock.psm
                    }
                }.onFailure {
                    Logger.d(BT_TAG, "BLE L2CAP CoC server unavailable; RFCOMM remains active: ${it.message}")
                }.getOrNull()
        }

        synchronized(stateLock) {
            rfcommServerSocket = rfcommSocket
            l2capServerSocket = optionalL2cap
            l2capPsm = psm
            rfcommAcceptJob = launchAcceptLoop(rfcommSocket, onChannel, onFailure, fatal = true)
            if (optionalL2cap != null) {
                l2capAcceptJob = launchAcceptLoop(optionalL2cap, onChannel, onFailure, fatal = false)
            }
        }
        Logger.i(BT_TAG, "Bluetooth fallback server started (l2capPsm=$psm).")
        return true
    }

    private fun launchAcceptLoop(
        serverSocket: BluetoothServerSocket,
        onChannel: suspend (DeviceChannel) -> Unit,
        onFailure: (Throwable) -> Unit,
        fatal: Boolean,
    ): Job =
        scope.launch {
            while (isActive) {
                val socket =
                    try {
                        serverSocket.accept()
                    } catch (error: IOException) {
                        val expected =
                            rfcommServerSocket !== serverSocket && l2capServerSocket !== serverSocket
                        if (!expected && fatal) {
                            onFailure(error)
                        }
                        break
                    }
                val mac = normalizeBluetoothMac(runCatching { socket.remoteDevice?.address }.getOrNull().orEmpty())
                val remoteHost = mac?.let { "$DEVICE_CONNECT_BT_PREFIX$it" }
                val channel =
                    createJvmStreamDeviceChannel(
                        remoteHost = remoteHost,
                        input = socket.inputStream,
                        output = socket.outputStream,
                        onClose = { runCatching { socket.close() } },
                    )
                launch {
                    onChannel(channel)
                }
            }
        }

    override fun stopServer() {
        val (rfcomm, l2cap, rJob, lJob) =
            synchronized(stateLock) {
                val tuple =
                    Quad(
                        rfcommServerSocket.also { rfcommServerSocket = null },
                        l2capServerSocket.also { l2capServerSocket = null },
                        rfcommAcceptJob.also { rfcommAcceptJob = null },
                        l2capAcceptJob.also { l2capAcceptJob = null },
                    )
                l2capPsm = 0
                tuple
            }
        rJob?.cancel()
        lJob?.cancel()
        runCatching { rfcomm?.close() }
        runCatching { l2cap?.close() }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(
        localInfo: DeviceInfoPayload,
        onFound: (DiscoveredDevice) -> Unit,
        onLost: (DiscoveredDevice) -> Unit,
    ) {
        if (!adapter.isEnabled) {
            onError("Bluetooth is off; Bluetooth discovery skipped.", null)
            return
        }
        localDeviceId = localInfo.deviceId
        discoveredByKey.clear()

        if (hasConnectPermission()) {
            emitBondedCandidatePeers(localInfo.deviceId, onFound)
            registerClassicDiscoveryReceiver(localInfo.deviceId, onFound)
        }
        if (hasAdvertisePermission() && adapter.isMultipleAdvertisementSupported) {
            startBleAdvertising(localInfo)
        }
        if (hasScanPermission()) {
            startBleScanning(localInfo.deviceId, onFound)
        } else {
            Logger.w(BT_TAG, "Bluetooth scan permission missing; using bonded devices only.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun emitBondedCandidatePeers(
        localId: String,
        onFound: (DiscoveredDevice) -> Unit,
    ) {
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        for (device in bonded) {
            val mac = normalizeBluetoothMac(device.address.orEmpty()) ?: continue
            val uuids = runCatching { device.uuids }.getOrNull()
            val hasMetrolistUuid = uuids?.any { it.uuid == RFCOMM_SERVICE_UUID } == true
            if (hasMetrolistUuid) {
                val deviceName = sanitizeDeviceName(runCatching { device.name }.getOrNull().orEmpty()).ifBlank { mac }
                val synthesizedId = "bt-${mac.replace(":", "")}"
                if (synthesizedId == localId) continue
                val discovered =
                    DiscoveredDevice(
                        serviceName = "bt-$mac",
                        deviceId = synthesizedId,
                        deviceName = deviceName,
                        host = "$DEVICE_CONNECT_BT_PREFIX$mac",
                        port = DEVICE_CONNECT_BT_PORT,
                    )
                discoveredByKey["bonded:$mac"] = discovered
                onFound(discovered)
            } else {
                runCatching { device.fetchUuidsWithSdp() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerClassicDiscoveryReceiver(
        localId: String,
        onFound: (DiscoveredDevice) -> Unit,
    ) {
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != BluetoothDevice.ACTION_UUID) return
                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                    val uuids =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                        }
                    val hasService = uuids?.any { (it as? ParcelUuid)?.uuid == RFCOMM_SERVICE_UUID } == true
                    if (!hasService) return
                    val mac = normalizeBluetoothMac(device.address.orEmpty()) ?: return
                    val deviceName =
                        if (hasConnectPermission()) {
                            sanitizeDeviceName(runCatching { device.name }.getOrNull().orEmpty()).ifBlank { mac }
                        } else {
                            mac
                        }
                    val synthesizedId = "bt-${mac.replace(":", "")}"
                    if (synthesizedId == localId) return
                    val discovered =
                        DiscoveredDevice(
                            serviceName = "bt-$mac",
                            deviceId = synthesizedId,
                            deviceName = deviceName,
                            host = "$DEVICE_CONNECT_BT_PREFIX$mac",
                            port = DEVICE_CONNECT_BT_PORT,
                        )
                    discoveredByKey["sdp:$mac"] = discovered
                    onFound(discovered)
                }
            }
        synchronized(stateLock) {
            discoveryReceiver?.let { existing -> runCatching { context.unregisterReceiver(existing) } }
            discoveryReceiver = receiver
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure {
            Logger.w(BT_TAG, "Could not register Bluetooth SDP receiver: ${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising(localInfo: DeviceInfoPayload) {
        val bleAdvertiser = adapter.bluetoothLeAdvertiser ?: return
        val idBytes = localInfo.deviceId.encodeToByteArray()
        if (idBytes.size > MAX_BLE_DEVICE_ID_BYTES) return
        val currentPsm = l2capPsm.takeIf { it in 2..65535 } ?: 0
        // Format: [1 byte deviceType ordinal][2 bytes L2CAP PSM big-endian][UTF-8 deviceId bytes]
        val serviceData = ByteArray(3 + idBytes.size)
        val typeOrdinal = DeviceConnectDeviceType.fromWireName(localInfo.deviceType).ordinal
        serviceData[0] = typeOrdinal.toByte()
        serviceData[1] = ((currentPsm ushr 8) and 0xff).toByte()
        serviceData[2] = (currentPsm and 0xff).toByte()
        idBytes.copyInto(serviceData, destinationOffset = 3)

        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(currentPsm > 0)
                .build()
        val data =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(BLE_DISCOVERY_SERVICE_UUID)
                .addServiceData(BLE_DISCOVERY_SERVICE_UUID, serviceData)
                .build()
        val scanResponse =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(true)
                .build()

        val callback =
            object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    Logger.w(BT_TAG, "BLE advertising failed with code=$errorCode")
                }

                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Logger.d(BT_TAG, "BLE proximity advertising active.")
                }
            }
        synchronized(stateLock) {
            advertiser = bleAdvertiser
            advertiseCallback = callback
        }
        runCatching { bleAdvertiser.startAdvertising(settings, data, scanResponse, callback) }
            .onFailure { Logger.w(BT_TAG, "BLE advertise start failed: ${it.message}", it) }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanning(
        localId: String,
        onFound: (DiscoveredDevice) -> Unit,
    ) {
        val bleScanner = adapter.bluetoothLeScanner ?: return
        val filter =
            ScanFilter
                .Builder()
                .setServiceUuid(BLE_DISCOVERY_SERVICE_UUID)
                .build()
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    val record = result.scanRecord ?: return
                    val data = record.getServiceData(BLE_DISCOVERY_SERVICE_UUID) ?: return
                    if (data.size < 4) return
                    val typeOrdinal = data[0].toInt() and 0xff
                    val deviceType = DeviceConnectDeviceType.entries.getOrElse(typeOrdinal) { DeviceConnectDeviceType.UNKNOWN }
                    val psm = ((data[1].toInt() and 0xff) shl 8) or (data[2].toInt() and 0xff)
                    val peerId = data.copyOfRange(3, data.size).decodeToString()
                    if (!isValidDeviceId(peerId) || peerId == localId) return
                    val mac = normalizeBluetoothMac(result.device.address.orEmpty()) ?: return
                    val advertisedName =
                        record.deviceName
                            ?: if (hasConnectPermission()) runCatching { result.device.name }.getOrNull() else null
                    val safeName = sanitizeDeviceName(advertisedName.orEmpty()).ifBlank { mac }
                    val port = if (psm in 2..65535) psm else DEVICE_CONNECT_BT_PORT
                    val discovered =
                        DiscoveredDevice(
                            serviceName = "ble-$mac",
                            deviceId = peerId,
                            deviceName = safeName,
                            host = "$DEVICE_CONNECT_BT_PREFIX$mac",
                            port = port,
                            deviceType = deviceType,
                        )
                    val previous = discoveredByKey.put("ble:$peerId", discovered)
                    if (previous != discovered) {
                        onFound(discovered)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Logger.w(BT_TAG, "BLE discovery failed with code=$errorCode")
                }
            }
        synchronized(stateLock) {
            scanner = bleScanner
            scanCallback = callback
        }
        runCatching { bleScanner.startScan(listOf(filter), settings, callback) }
            .onFailure { Logger.w(BT_TAG, "BLE scan start failed: ${it.message}", it) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        val (bleAdv, advCb, bleScan, scanCb, receiver) =
            synchronized(stateLock) {
                Quintuple(
                    advertiser.also { advertiser = null },
                    advertiseCallback.also { advertiseCallback = null },
                    scanner.also { scanner = null },
                    scanCallback.also { scanCallback = null },
                    discoveryReceiver.also { discoveryReceiver = null },
                )
            }
        discoveredByKey.clear()
        if (bleAdv != null && advCb != null && hasAdvertisePermission()) {
            runCatching { bleAdv.stopAdvertising(advCb) }
        }
        if (bleScan != null && scanCb != null && hasScanPermission()) {
            runCatching { bleScan.stopScan(scanCb) }
        }
        if (receiver != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(
        host: String,
        port: Int,
    ): DeviceChannel =
        withContext(Dispatchers.IO) {
            check(hasConnectPermission()) { "Bluetooth connect permission is missing" }
            check(adapter.isEnabled) { "Bluetooth adapter is disabled" }
            val mac =
                normalizeBluetoothMac(host.removePrefix(DEVICE_CONNECT_BT_PREFIX))
                    ?: throw IllegalArgumentException("Invalid Bluetooth endpoint host: $host")
            val remote = adapter.getRemoteDevice(mac)
            if (hasScanPermission()) {
                runCatching {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                }
            }
            val socket: BluetoothSocket =
                if (port > DEVICE_CONNECT_BT_PORT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    remote.createInsecureL2capChannel(port)
                } else {
                    runCatching { remote.createInsecureRfcommSocketToServiceRecord(RFCOMM_SERVICE_UUID) }
                        .recoverCatching { remote.createRfcommSocketToServiceRecord(RFCOMM_SERVICE_UUID) }
                        .getOrThrow()
                }
            val cancellationHandle =
                coroutineContext.job.invokeOnCompletion { cause ->
                    if (cause != null) {
                        runCatching { socket.close() }
                    }
                }
            try {
                socket.connect()
                coroutineContext.ensureActive()
                val channel =
                    createJvmStreamDeviceChannel(
                        remoteHost = "$DEVICE_CONNECT_BT_PREFIX$mac",
                        input = socket.inputStream,
                        output = socket.outputStream,
                        onClose = { runCatching { socket.close() } },
                    )
                if (!isActive) {
                    withContext(NonCancellable) { runCatching { channel.close() } }
                    coroutineContext.ensureActive()
                }
                channel
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            } finally {
                cancellationHandle.dispose()
            }
        }

    override suspend fun close() {
        stopDiscovery()
        stopServer()
        scope.cancel()
    }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }

    private fun hasAdvertisePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
    )

    private companion object {
        const val ANDROID_MASKED_BLUETOOTH_MAC = "02:00:00:00:00:00"
        const val MAX_BLE_DEVICE_ID_BYTES = 18
    }
}

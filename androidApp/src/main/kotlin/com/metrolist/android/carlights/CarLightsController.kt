package com.metrolist.android.carlights

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

data class CarLightDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)

enum class CarLightsLink {
    // Feature disabled, or no saved device to look for.
    IDLE,

    // Scanning in the background for the saved device.
    WAITING,
    CONNECTING,
    CONNECTED,
}

// BLE session with one car light controller. Every state change runs on the main thread.
@SuppressLint("MissingPermission")
class CarLightsController(
    context: Context,
    private val onConnected: (address: String, name: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _link = MutableStateFlow(CarLightsLink.IDLE)
    val link: StateFlow<CarLightsLink> = _link.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _discovered = MutableStateFlow<List<CarLightDevice>>(emptyList())
    val discovered: StateFlow<List<CarLightDevice>> = _discovered.asStateFlow()

    private val _bluetoothOn = MutableStateFlow(false)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()

    // Android 11 and older hide BLE scan results while the system Location switch is off.
    private val _locationOn = MutableStateFlow(true)
    val locationOn: StateFlow<Boolean> = _locationOn.asStateFlow()

    // False for plain-only lights, which take one color instead of three zones.
    private val _hasZones = MutableStateFlow(true)
    val hasZones: StateFlow<Boolean> = _hasZones.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private var enabled = false
    private var autoConnect = false
    private var savedAddress = ""
    private var savedName = ""

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var family = CarLightProtocol.Family.ZONED
    private var connectedAddress: String? = null

    private var userScan: ScanCallback? = null
    private var autoScan: ScanCallback? = null

    private val writeQueue = ArrayDeque<ByteArray>()
    private var inFlight: ByteArray? = null
    private var inFlightNoResponse = false
    private var batch = 0
    private var inFlightBatch = 0
    private var failedWrites = 0

    // Uptime before which the next frame may not start, so new colors keep the delays between writes.
    private var nextWriteAt = 0L

    // Zone mask to 0xRRGGBB wanted on the lights, replayed on every connect.
    private var desired: Map<Int, Int> = emptyMap()

    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val bluetoothStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == LocationManager.MODE_CHANGED_ACTION) {
                    _locationOn.value = locationEnabled()
                    reevaluate()
                    return
                }
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        _bluetoothOn.value = true
                        reevaluate()
                    }

                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        _bluetoothOn.value = false
                        teardown()
                    }
                }
            }
        }

    init {
        _bluetoothOn.value = hasPermissions() && adapter?.isEnabled == true
        _locationOn.value = locationEnabled()
        ContextCompat.registerReceiver(
            appContext,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(LocationManager.MODE_CHANGED_ACTION) },
            // Both actions are protected broadcasts, and the Bluetooth process sends the adapter state.
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    // Android 12 and newer scan without location, older versions need the system Location switch on.
    private fun locationEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            appContext.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } == true

    fun hasPermissions(): Boolean =
        requiredPermissions().all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    // Safe to call repeatedly. An empty address means no saved lights.
    fun configure(
        enabled: Boolean,
        autoConnect: Boolean,
        savedAddress: String,
        savedName: String,
    ) {
        main.post {
            val addressChanged = this.savedAddress != savedAddress
            this.enabled = enabled
            this.autoConnect = autoConnect
            this.savedAddress = savedAddress
            this.savedName = savedName
            if (!enabled) {
                stopDiscovery()
                teardown()
                return@post
            }
            if (addressChanged && connectedAddress != null && connectedAddress != savedAddress) {
                teardown()
            }
            reevaluate()
        }
    }

    // zoneColors maps a zone mask to a 0xRRGGBB color. The colors are sent again after every reconnect.
    fun setZoneColors(zoneColors: Map<Int, Int>) {
        main.post {
            desired = zoneColors
            if (_link.value == CarLightsLink.CONNECTED) sendDesired()
        }
    }

    // Rechecks permissions and Bluetooth state, for example after the user granted access.
    fun refresh() {
        main.post {
            _bluetoothOn.value = hasPermissions() && adapter?.isEnabled == true
            _locationOn.value = locationEnabled()
            reevaluate()
        }
    }

    // Starts a timed scan for supported lights so the user can pick one.
    fun startDiscovery() {
        main.post {
            if (!hasPermissions() || adapter?.isEnabled != true || !locationEnabled()) return@post
            val scanner = adapter?.bluetoothLeScanner ?: return@post
            stopDiscovery()
            _discovered.value = emptyList()
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        main.post { if (userScan === this) handleDiscovery(result) }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        main.post { if (userScan === this) results.forEach(::handleDiscovery) }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Timber.tag(TAG).w("Discovery scan failed: %d", errorCode)
                        main.post { if (userScan === this) stopDiscovery() }
                    }
                }
            userScan = callback
            _discovering.value = true
            runCatching {
                scanner.startScan(
                    emptyList(),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
            }.onFailure {
                Timber.tag(TAG).w(it, "Unable to start discovery")
                stopDiscovery()
            }
            main.postDelayed(discoveryTimeout, DISCOVERY_MS)
        }
    }

    fun connect(device: CarLightDevice) {
        main.post {
            if (!hasPermissions()) return@post
            val remote = runCatching { adapter?.getRemoteDevice(device.address) }.getOrNull() ?: return@post
            stopDiscovery()
            connectInternal(remote, device.name)
        }
    }

    // Keeps the saved device.
    fun disconnect() {
        main.post { teardown() }
    }

    // Runs onForgotten on the main thread after the teardown, so a service discovery that was already queued
    // cannot save the device again after the caller cleared it.
    fun forget(onForgotten: () -> Unit) {
        main.post {
            teardown()
            _hasZones.value = true
            onForgotten()
        }
    }

    private val discoveryTimeout = Runnable { stopDiscovery() }
    private val connectTimeout =
        Runnable {
            if (_link.value == CarLightsLink.CONNECTING) {
                Timber.tag(TAG).w("Connect timed out")
                handleLost()
            }
        }
    private val autoRetry = Runnable { reevaluate() }
    private val writeTimeout =
        Runnable {
            if (inFlight != null) {
                if (inFlightNoResponse) finishWrite(true) else handleLost()
            }
        }
    private val pumpNext = Runnable { pump() }

    private fun handleDiscovery(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
        if (CarLightProtocol.familyFor(name) == null) return
        val found = CarLightDevice(result.device.address, name.orEmpty(), result.rssi)
        _discovered.value =
            (_discovered.value.filterNot { it.address == found.address } + found).sortedByDescending { it.rssi }
    }

    private fun stopDiscovery() {
        main.removeCallbacks(discoveryTimeout)
        userScan?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        userScan = null
        _discovering.value = false
    }

    // Idles, scans in the background for the saved device, or leaves the live connection alone.
    private fun reevaluate() {
        main.removeCallbacks(autoRetry)
        if (!enabled || !hasPermissions() || adapter?.isEnabled != true || !locationEnabled()) {
            stopAutoScan()
            if (_link.value == CarLightsLink.WAITING) _link.value = CarLightsLink.IDLE
            return
        }
        if (_link.value == CarLightsLink.CONNECTED || _link.value == CarLightsLink.CONNECTING) return
        if (autoConnect && savedAddress.isNotEmpty()) {
            startAutoScan()
        } else {
            stopAutoScan()
            _link.value = CarLightsLink.IDLE
        }
    }

    // Low-power scan filtered to the saved address, connects when it shows up.
    private fun startAutoScan() {
        if (autoScan != null) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val target = savedAddress
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    main.post {
                        if (autoScan !== this || !result.device.address.equals(target, ignoreCase = true)) return@post
                        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: savedName
                        connectInternal(result.device, name)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Timber.tag(TAG).w("Auto-connect scan failed: %d", errorCode)
                    main.post {
                        if (autoScan === this) {
                            autoScan = null
                            main.postDelayed(autoRetry, AUTO_RETRY_MS)
                        }
                    }
                }
            }
        autoScan = callback
        val started =
            runCatching {
                scanner.startScan(
                    listOf(ScanFilter.Builder().setDeviceAddress(target).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                    callback,
                )
            }.isSuccess
        if (started) {
            _link.value = CarLightsLink.WAITING
        } else {
            autoScan = null
            main.postDelayed(autoRetry, AUTO_RETRY_MS)
        }
    }

    private fun stopAutoScan() {
        autoScan?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        autoScan = null
    }

    // The advertised name decides the frame family.
    private fun connectInternal(
        device: BluetoothDevice,
        name: String,
    ) {
        val resolved = CarLightProtocol.familyFor(name) ?: CarLightProtocol.familyFor(savedName) ?: CarLightProtocol.Family.ZONED
        teardown()
        family = resolved
        _hasZones.value = resolved != CarLightProtocol.Family.PLAIN
        connectedAddress = device.address
        _connectedName.value = name.ifEmpty { device.address }
        _link.value = CarLightsLink.CONNECTING
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // Connection failed or dropped. Retries later if auto-connect is on.
    private fun handleLost() {
        teardown()
        if (enabled && autoConnect) main.postDelayed(autoRetry, AUTO_RETRY_MS)
    }

    // Closes the GATT, stops the background scan, clears the write queue and leaves the link idle.
    private fun teardown() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(writeTimeout)
        main.removeCallbacks(pumpNext)
        writeQueue.clear()
        inFlight = null
        failedWrites = 0
        writeCharacteristic = null
        val g = gatt
        gatt = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        connectedAddress = null
        _connectedName.value = null
        _link.value = CarLightsLink.IDLE
        stopAutoScan()
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                main.post {
                    if (g !== gatt) {
                        runCatching { g.close() }
                        return@post
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        main.postDelayed({ if (g === gatt) runCatching { g.discoverServices() } }, SERVICE_DISCOVERY_DELAY_MS)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag(TAG).d("GATT closed status=%d state=%d", status, newState)
                        handleLost()
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                main.post {
                    if (g !== gatt) return@post
                    val characteristic =
                        g.getService(CarLightProtocol.SERVICE_UUID)?.getCharacteristic(CarLightProtocol.WRITE_UUID)
                            ?: g.services.firstNotNullOfOrNull { it.getCharacteristic(CarLightProtocol.WRITE_UUID) }
                    if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                        Timber.tag(TAG).w("No FFF3 characteristic (status=%d)", status)
                        handleLost()
                        return@post
                    }
                    writeCharacteristic = characteristic
                    main.removeCallbacks(connectTimeout)
                    _link.value = CarLightsLink.CONNECTED
                    onConnected(g.device.address, _connectedName.value.orEmpty())
                    nextWriteAt = SystemClock.uptimeMillis() + FIRST_WRITE_DELAY_MS
                    sendDesired()
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                main.post {
                    if (g !== gatt) return@post
                    if (inFlightNoResponse) {
                        // No-response writes advance on the timer. A late callback cannot identify its frame.
                        if (status != BluetoothGatt.GATT_SUCCESS) handleLost()
                    } else {
                        finishWrite(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }
            }
        }

    // Replaces the queue with the frames for the wanted colors. A new batch also stops retries of the old one.
    private fun sendDesired() {
        batch++
        failedWrites = 0
        writeQueue.clear()
        CarLightProtocol.colorFrames(family, desired).forEach(writeQueue::addLast)
        pumpAt(nextWriteAt)
    }

    // Keeps one pending pump, so a later schedule replaces an earlier one instead of skipping its delay.
    private fun pumpAt(uptimeMs: Long) {
        nextWriteAt = uptimeMs
        main.removeCallbacks(pumpNext)
        main.postAtTime(pumpNext, uptimeMs)
    }

    // Writes the next queued frame, one write in flight at a time.
    private fun pump() {
        if (inFlight != null) return
        val g = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val frame = writeQueue.removeFirstOrNull() ?: return
        inFlight = frame
        inFlightBatch = batch
        val noResponseOnly =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType =
            if (noResponseOnly) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val accepted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, frame, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = frame
                    g.writeCharacteristic(characteristic)
                }
            }
        inFlightNoResponse = noResponseOnly
        if (accepted) {
            // A response timeout closes this GATT so a late callback cannot complete a later write.
            main.removeCallbacks(writeTimeout)
            main.postDelayed(writeTimeout, if (noResponseOnly) WRITE_RETRY_MS else WRITE_TIMEOUT_MS)
        } else {
            Timber.tag(TAG).w("Write rejected")
            finishWrite(false)
        }
    }

    // Ends the write in flight. A failed frame from the current batch goes back to the front of the queue.
    private fun finishWrite(success: Boolean) {
        main.removeCallbacks(writeTimeout)
        val frame = inFlight ?: return
        inFlight = null
        if (success) {
            failedWrites = 0
            pumpAt(SystemClock.uptimeMillis() + WRITE_GAP_MS)
            return
        }
        if (inFlightBatch == batch) {
            if (++failedWrites > MAX_WRITE_RETRIES) {
                // Give up on this batch. The next color update or manual resend sends it again.
                writeQueue.clear()
                failedWrites = 0
                return
            }
            writeQueue.addFirst(frame)
        }
        pumpAt(SystemClock.uptimeMillis() + WRITE_RETRY_MS)
    }

    companion object {
        private const val TAG = "CarLights"
        private const val DISCOVERY_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val AUTO_RETRY_MS = 10_000L
        private const val SERVICE_DISCOVERY_DELAY_MS = 400L
        private const val FIRST_WRITE_DELAY_MS = 150L
        private const val WRITE_GAP_MS = 60L
        private const val WRITE_RETRY_MS = 120L
        private const val WRITE_TIMEOUT_MS = 600L
        private const val MAX_WRITE_RETRIES = 3

        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}

package com.smartcard.signer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import org.satochip.io.CardChannel
import org.satochip.io.CardListener

class UsbCcidCardManager(
    context: Context,
    private val loopSleepMs: Int = 750,
    private val onError: (String) -> Unit = {}
) : Thread("UsbCcidCardManager") {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val permissionAction = "${appContext.packageName}.USB_PERMISSION"
    private val permissionIntent: PendingIntent by lazy {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(appContext, 0, Intent(permissionAction), flags)
    }

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var running = false

    @Volatile
    private var channel: UsbCcidCardChannel? = null

    @Volatile
    private var connectedDeviceId: Int? = null

    private var listener: CardListener? = null
    private var lastOpenAttemptMs: Long = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device = intent.usbDeviceOrNull()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) {
                        probeNow(forceOpenAttempt = true)
                    } else if (device != null) {
                        onError("USB 读卡器权限被拒绝")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDeviceOrNull() ?: return
                    if (!isSupportedDevice(device)) return
                    if (usbManager.hasPermission(device)) {
                        probeNow(forceOpenAttempt = true)
                    } else {
                        requestPermission(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDeviceOrNull() ?: return
                    val current = channel
                    if (current != null && current.belongsTo(detached.deviceId)) {
                        disconnectCurrent(notify = true)
                    }
                }
            }
        }
    }

    fun setCardListener(cardListener: CardListener) {
        listener = cardListener
    }

    fun startManager() {
        if (running) return
        running = true
        registerReceiverIfNeeded()
        requestPermissionForConnectedReaders()
        if (!isAlive) {
            start()
        }
        probeNow(forceOpenAttempt = true)
    }

    fun stopManager() {
        running = false
        if (isAlive) {
            interrupt()
        }
        unregisterReceiverIfNeeded()
        disconnectCurrent(notify = true)
    }

    fun withConnectedChannel(action: (CardChannel) -> Unit): Boolean {
        val current = channel ?: return false
        if (!current.isConnected() || !current.isCardPresent()) {
            disconnectCurrent(notify = true)
            return false
        }
        action(current)
        return true
    }

    fun probeNow(forceOpenAttempt: Boolean = false) {
        runCatching {
            pollInternal(forceOpenAttempt)
        }.onFailure { error ->
            onError("USB 读卡器探测失败: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun run() {
        while (!isInterrupted) {
            if (running) {
                runCatching { pollInternal(forceOpenAttempt = false) }
            }
            SystemClock.sleep(loopSleepMs.toLong())
        }
    }

    private fun pollInternal(forceOpenAttempt: Boolean) {
        val current = channel
        if (current != null) {
            val id = connectedDeviceId
            val deviceStillPresent = id != null && isDeviceStillPresent(id)
            if (!deviceStillPresent || !current.isConnected() || !current.isCardPresent()) {
                disconnectCurrent(notify = true)
            }
            return
        }

        val device = findPreferredSupportedDevice() ?: return
        if (!usbManager.hasPermission(device)) return

        val now = SystemClock.elapsedRealtime()
        if (!forceOpenAttempt && now - lastOpenAttemptMs < MIN_OPEN_ATTEMPT_INTERVAL_MS) {
            return
        }
        lastOpenAttemptMs = now
        openChannel(device)
    }

    private fun openChannel(device: UsbDevice) {
        val transport = findCcidTransport(device) ?: return
        val connection = usbManager.openDevice(device) ?: run {
            onError("无法打开 USB 读卡器，请检查 OTG 或连接线")
            return
        }

        val newChannel = runCatching {
            UsbCcidCardChannel(
                device = device,
                connection = connection,
                ccidInterface = transport.ccidInterface,
                bulkIn = transport.bulkIn,
                bulkOut = transport.bulkOut
            )
        }.getOrElse { _ ->
            runCatching { connection.close() }
            return
        }

        disconnectCurrent(notify = false)
        channel = newChannel
        connectedDeviceId = device.deviceId
        listener?.onConnected(newChannel)
    }

    private fun disconnectCurrent(notify: Boolean) {
        val old = channel
        channel = null
        connectedDeviceId = null
        if (old != null) {
            runCatching { old.close() }
            if (notify) {
                listener?.onDisconnected()
            }
        }
    }

    private fun requestPermissionForConnectedReaders() {
        usbManager.deviceList.values
            .filter { isSupportedDevice(it) }
            .forEach { device ->
                if (!usbManager.hasPermission(device)) {
                    requestPermission(device)
                }
            }
    }

    private fun requestPermission(device: UsbDevice) {
        runCatching {
            usbManager.requestPermission(device, permissionIntent)
        }.onFailure { error ->
            onError("申请 USB 权限失败: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun isDeviceStillPresent(deviceId: Int): Boolean {
        return usbManager.deviceList.values.any { it.deviceId == deviceId }
    }

    private fun findPreferredSupportedDevice(): UsbDevice? {
        return usbManager.deviceList.values
            .filter { isSupportedDevice(it) }
            .sortedWith(
                compareByDescending<UsbDevice> { it.vendorId == ACS_VENDOR_ID }
                    .thenBy { it.deviceId }
            )
            .firstOrNull()
    }

    private fun isSupportedDevice(device: UsbDevice): Boolean {
        return findCcidTransport(device) != null
    }

    private fun findCcidTransport(device: UsbDevice): CcidTransport? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            val looksLikeCcid = usbInterface.interfaceClass == USB_CLASS_CCID
            val hasBulk = usbInterface.endpointCount >= 2
            if (!looksLikeCcid && !(device.vendorId == ACS_VENDOR_ID && hasBulk)) {
                continue
            }

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (epIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(epIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> if (bulkIn == null) bulkIn = endpoint
                    UsbConstants.USB_DIR_OUT -> if (bulkOut == null) bulkOut = endpoint
                }
            }
            if (bulkIn != null && bulkOut != null) {
                return CcidTransport(usbInterface, bulkIn, bulkOut)
            }
        }
        return null
    }

    private fun Intent.usbDeviceOrNull(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private data class CcidTransport(
        val ccidInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint
    )

    private companion object {
        const val USB_CLASS_CCID = 0x0B
        const val ACS_VENDOR_ID = 0x072F
        const val MIN_OPEN_ATTEMPT_INTERVAL_MS = 1200L
    }
}

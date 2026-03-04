package com.smartcard.signer

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.satochip.io.APDUCommand
import org.satochip.io.APDUResponse
import org.satochip.io.CardChannel

/**
 * Minimal CCID transport for USB smart-card readers (tested for ACS ACR39U family).
 */
class UsbCcidCardChannel(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val ccidInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val timeoutMs: Int = 120_000
) : CardChannel {
    @Volatile
    private var closed = false

    private var seq: Int = 0
    private val ioLock = Any()
    private var pendingReadBuffer: ByteArray = ByteArray(0)

    init {
        if (!connection.claimInterface(ccidInterface, true)) {
            throw IOException("无法占用 USB 读卡器接口")
        }
        powerOn()
    }

    fun belongsTo(deviceId: Int): Boolean {
        return device.deviceId == deviceId
    }

    fun isCardPresent(): Boolean {
        return synchronized(ioLock) {
            if (closed) return@synchronized false
            runCatching { queryCardPresent() }.getOrDefault(false)
        }
    }

    fun close() {
        synchronized(ioLock) {
            if (closed) return
            runCatching { powerOff() }
            runCatching { connection.releaseInterface(ccidInterface) }
            runCatching { connection.close() }
            closed = true
        }
    }

    override fun send(cmd: APDUCommand): APDUResponse {
        val response = synchronized(ioLock) {
            ensureOpen()
            val apdu = cmd.serialize()
            executeWithRecovery(apdu)
        }
        return APDUResponse(response)
    }

    private fun executeWithRecovery(apdu: ByteArray): ByteArray {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching { transceiveApdu(apdu) }
            if (result.isSuccess) {
                return result.getOrThrow()
            }
            val error = result.exceptionOrNull() ?: return@repeat
            lastError = error
            if (attempt >= 2 || !isRecoverableExchangeError(error)) {
                return@repeat
            }
            recoverCardSession()
        }
        throw lastError ?: IOException("USB APDU 发送失败")
    }

    override fun isConnected(): Boolean {
        return !closed
    }

    private fun ensureOpen() {
        if (closed) {
            throw IOException("USB 通道已关闭")
        }
    }

    private fun powerOn() {
        sendCommand(messageType = CCID_PC_TO_RDR_ICC_POWER_ON, payload = ByteArray(0))
        val response = readResponse()
        val (cmdStatus, iccStatus) = decodeStatuses(response.status)
        if (cmdStatus != CCID_CMD_STATUS_PROCESSED || iccStatus == CCID_ICC_STATUS_NOT_PRESENT) {
            throw IOException("读卡器已连接，但未检测到卡片")
        }
    }

    private fun powerOff() {
        if (closed) return
        sendCommand(messageType = CCID_PC_TO_RDR_ICC_POWER_OFF, payload = ByteArray(0))
        runCatching { readResponse() }
    }

    private fun queryCardPresent(): Boolean {
        sendCommand(messageType = CCID_PC_TO_RDR_GET_SLOT_STATUS, payload = ByteArray(0))
        while (true) {
            val response = readResponse()
            if (response.messageType != CCID_RDR_TO_PC_SLOT_STATUS &&
                response.messageType != CCID_RDR_TO_PC_DATA_BLOCK
            ) {
                continue
            }
            val (cmdStatus, iccStatus) = decodeStatuses(response.status)
            if (cmdStatus == CCID_CMD_STATUS_TIME_EXTENSION) {
                continue
            }
            if (response.error != 0) {
                return false
            }
            if (cmdStatus != CCID_CMD_STATUS_PROCESSED) {
                return false
            }
            return iccStatus != CCID_ICC_STATUS_NOT_PRESENT
        }
    }

    private fun transceiveApdu(apdu: ByteArray): ByteArray {
        sendCommand(
            messageType = CCID_PC_TO_RDR_XFR_BLOCK,
            payload = apdu,
            parameter0 = 0x00, // bBWI
            parameter1 = 0x00, // wLevelParameter low
            parameter2 = 0x00  // wLevelParameter high
        )

        val responsePayload = ByteArrayOutputStream()
        while (true) {
            val response = readResponse()
            when (response.messageType) {
                CCID_RDR_TO_PC_DATA_BLOCK -> {
                    val (cmdStatus, iccStatus) = decodeStatuses(response.status)
                    if (cmdStatus == CCID_CMD_STATUS_TIME_EXTENSION) {
                        continue
                    }
                    if (response.error != 0) {
                        throw IOException(
                            "CCID reader error in data block: status=0x${response.status.toString(16)} bError=0x${response.error.toString(16)}"
                        )
                    }
                    if (cmdStatus != CCID_CMD_STATUS_PROCESSED) {
                        throw IOException(
                            "CCID APDU 执行失败: status=0x${response.status.toString(16)} error=0x${response.error.toString(16)}"
                        )
                    }
                    if (iccStatus == CCID_ICC_STATUS_NOT_PRESENT) {
                        throw IOException("智能卡已移除")
                    }
                    if (response.payload.isNotEmpty()) {
                        responsePayload.write(response.payload)
                    }

                    // Some readers may chain a long R-APDU across multiple CCID blocks.
                    if (!response.hasMoreData()) {
                        return responsePayload.toByteArray()
                    }
                    continue
                }

                CCID_RDR_TO_PC_SLOT_STATUS -> {
                    val (cmdStatus, iccStatus) = decodeStatuses(response.status)
                    if (cmdStatus == CCID_CMD_STATUS_TIME_EXTENSION) {
                        continue
                    }
                    if (response.error != 0) {
                        throw IOException(
                            "CCID slot status error: status=0x${response.status.toString(16)} bError=0x${response.error.toString(16)}"
                        )
                    }
                    if (cmdStatus != CCID_CMD_STATUS_PROCESSED) {
                        throw IOException(
                            "CCID SlotStatus 失败: status=0x${response.status.toString(16)} error=0x${response.error.toString(16)}"
                        )
                    }
                    if (iccStatus == CCID_ICC_STATUS_NOT_PRESENT) {
                        throw IOException("智能卡已移除")
                    }
                    return ByteArray(0)
                }

                else -> {
                    throw IOException("不支持的 CCID 响应类型: 0x${response.messageType.toString(16)}")
                }
            }
        }
    }

    private fun isRecoverableExchangeError(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: return false
        return message.contains("status=0x40") ||
            message.contains("error=0xfe") ||
            message.contains("berror=0x82") ||
            message.contains("berror=0xfe") ||
            message.contains("berror=0xfb") ||
            message.contains("apdu response must be at least 2 bytes") ||
            message.contains("usb 读取超时")
    }

    private fun recoverCardSession() {
        runCatching { powerOff() }
        pendingReadBuffer = ByteArray(0)
        powerOn()
    }

    private fun sendCommand(
        messageType: Int,
        payload: ByteArray,
        parameter0: Int = 0x00,
        parameter1: Int = 0x00,
        parameter2: Int = 0x00
    ) {
        val request = ByteArray(10 + payload.size)
        request[0] = messageType.toByte()
        writeLe32(request, 1, payload.size)
        request[5] = 0x00 // slot
        request[6] = nextSeq().toByte()
        request[7] = parameter0.toByte()
        request[8] = parameter1.toByte()
        request[9] = parameter2.toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, request, 10, payload.size)
        }

        val written = connection.bulkTransfer(bulkOut, request, request.size, timeoutMs)
        if (written != request.size) {
            throw IOException("USB 写入失败: expected=${request.size}, actual=$written")
        }
    }

    private fun readResponse(): CcidMessage {
        val raw = readRawMessage()
        if (raw.size < 10) {
            throw IOException("CCID 响应头长度不足: ${raw.size}")
        }

        val messageType = raw[0].toInt() and 0xFF
        val payloadLength = readLe32(raw, 1)
        val expected = 10 + payloadLength
        if (raw.size < expected) {
            throw IOException("CCID 响应长度异常: expected=$expected, actual=${raw.size}")
        }

        val payload = if (payloadLength > 0) {
            raw.copyOfRange(10, 10 + payloadLength)
        } else {
            ByteArray(0)
        }

        return CcidMessage(
            messageType = messageType,
            status = raw[7].toInt() and 0xFF,
            error = raw[8].toInt() and 0xFF,
            chain = raw[9].toInt() and 0xFF,
            payload = payload
        )
    }

    private fun readRawMessage(): ByteArray {
        while (true) {
            if (pendingReadBuffer.size >= 10) {
                val expectedLength = 10 + readLe32(pendingReadBuffer, 1)
                if (pendingReadBuffer.size >= expectedLength) {
                    val message = pendingReadBuffer.copyOfRange(0, expectedLength)
                    pendingReadBuffer = pendingReadBuffer.copyOfRange(expectedLength, pendingReadBuffer.size)
                    return message
                }
            }

            val chunk = ByteArray(maxOf(512, bulkIn.maxPacketSize.coerceAtLeast(64)))
            val read = connection.bulkTransfer(bulkIn, chunk, chunk.size, timeoutMs)
            if (read <= 0) {
                throw IOException("USB 读取超时或失败: $read")
            }
            pendingReadBuffer = appendBytes(pendingReadBuffer, chunk.copyOf(read))
        }
    }

    private fun appendBytes(head: ByteArray, tail: ByteArray): ByteArray {
        if (head.isEmpty()) return tail
        if (tail.isEmpty()) return head
        val out = ByteArray(head.size + tail.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(tail, 0, out, head.size, tail.size)
        return out
    }

    private fun nextSeq(): Int {
        val current = seq and 0xFF
        seq = (seq + 1) and 0xFF
        return current
    }

    private fun decodeStatuses(status: Int): Pair<Int, Int> {
        val cmdStatus = (status ushr 6) and 0x03
        val iccStatus = status and 0x03
        return cmdStatus to iccStatus
    }

    private fun writeLe32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readLe32(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    private data class CcidMessage(
        val messageType: Int,
        val status: Int,
        val error: Int,
        val chain: Int,
        val payload: ByteArray
    ) {
        fun hasMoreData(): Boolean {
            return (chain and 0x01) != 0
        }
    }

    private companion object {
        const val CCID_PC_TO_RDR_ICC_POWER_ON = 0x62
        const val CCID_PC_TO_RDR_ICC_POWER_OFF = 0x63
        const val CCID_PC_TO_RDR_GET_SLOT_STATUS = 0x65
        const val CCID_PC_TO_RDR_XFR_BLOCK = 0x6F

        const val CCID_RDR_TO_PC_DATA_BLOCK = 0x80
        const val CCID_RDR_TO_PC_SLOT_STATUS = 0x81

        const val CCID_CMD_STATUS_PROCESSED = 0
        const val CCID_CMD_STATUS_TIME_EXTENSION = 2

        const val CCID_ICC_STATUS_NOT_PRESENT = 2
    }
}

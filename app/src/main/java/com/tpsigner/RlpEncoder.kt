package com.smartcard.signer

import java.math.BigInteger

object RlpEncoder {
    fun encodeBytes(bytes: ByteArray): ByteArray {
        if (bytes.size == 1 && (bytes[0].toInt() and 0xFF) < 0x80) {
            return bytes
        }
        return encodeLength(bytes.size, 0x80) + bytes
    }

    fun encodeBigInt(value: BigInteger): ByteArray {
        return encodeBytes(bigIntegerToMinimalBytes(value))
    }

    fun encodeString(value: String): ByteArray {
        return encodeBytes(value.toByteArray(Charsets.UTF_8))
    }

    fun encodeList(elements: List<ByteArray>): ByteArray {
        val payload = concat(elements)
        return encodeLength(payload.size, 0xC0) + payload
    }

    private fun encodeLength(length: Int, offset: Int): ByteArray {
        if (length < 56) {
            return byteArrayOf((offset + length).toByte())
        }
        val lenBytes = toMinimalByteArray(length)
        return byteArrayOf((offset + 55 + lenBytes.size).toByte()) + lenBytes
    }

    private fun toMinimalByteArray(value: Int): ByteArray {
        var tmp = value
        val out = ArrayList<Byte>()
        while (tmp > 0) {
            out.add(0, (tmp and 0xFF).toByte())
            tmp = tmp ushr 8
        }
        return out.toByteArray()
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        var total = 0
        for (part in parts) {
            total += part.size
        }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }
}

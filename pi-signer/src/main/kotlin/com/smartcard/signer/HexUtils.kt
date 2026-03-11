package com.smartcard.signer

import java.math.BigInteger

private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

fun cleanHexPrefix(value: String): String {
    return if (value.startsWith("0x") || value.startsWith("0X")) value.substring(2) else value
}

fun ensureHexPrefix(value: String): String {
    return if (value.startsWith("0x") || value.startsWith("0X")) value else "0x$value"
}

fun hexToBytes(hexValue: String?): ByteArray {
    if (hexValue == null) return ByteArray(0)
    var hex = cleanHexPrefix(hexValue.trim())
    if (hex.isEmpty()) return ByteArray(0)
    if (!HEX_REGEX.matches(hex)) {
        throw IllegalArgumentException("Invalid hex string")
    }
    if (hex.length % 2 != 0) {
        hex = "0$hex"
    }
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val index = i * 2
        out[i] = hex.substring(index, index + 2).toInt(16).toByte()
    }
    return out
}

fun bytesToHex(data: ByteArray): String {
    val sb = StringBuilder(data.size * 2)
    for (b in data) {
        sb.append(String.format("%02x", b))
    }
    return sb.toString()
}

fun parseQuantity(value: String?): BigInteger {
    if (value == null || value.isBlank()) return BigInteger.ZERO
    val trimmed = value.trim()
    return if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
        val hex = cleanHexPrefix(trimmed)
        if (hex.isBlank()) BigInteger.ZERO else BigInteger(hex, 16)
    } else {
        BigInteger(trimmed)
    }
}

fun bigIntegerToMinimalBytes(value: BigInteger): ByteArray {
    if (value == BigInteger.ZERO) return ByteArray(0)
    val bytes = value.toByteArray()
    return if (bytes[0].toInt() == 0) bytes.copyOfRange(1, bytes.size) else bytes
}

fun bigIntegerToFixed(value: BigInteger, size: Int): ByteArray {
    val bytes = value.toByteArray()
    val stripped = if (bytes.size > size && bytes[0].toInt() == 0) {
        bytes.copyOfRange(1, bytes.size)
    } else {
        bytes
    }
    require(stripped.size <= size) { "Integer does not fit in $size bytes" }

    val out = ByteArray(size)
    System.arraycopy(stripped, 0, out, size - stripped.size, stripped.size)
    return out
}

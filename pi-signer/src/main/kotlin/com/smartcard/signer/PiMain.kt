package com.smartcard.signer

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val DEFAULT_PATH = "m/44'/60'/0'/0/0"
private const val DEFAULT_TIMEOUT_SEC = 25

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help")) {
        printUsage()
        return
    }

    val command = args[0].lowercase()
    val options = parseOptions(args.drop(1))

    when (command) {
        "unlock" -> runUnlock(options)
        "sign" -> runSign(options)
        else -> throw IllegalArgumentException("未知命令: $command")
    }
}

private fun runUnlock(options: CliOptions) {
    val pin = options.required("pin")
    val path = options.value("path", DEFAULT_PATH)
    val reader = options.valueOrNull("reader")
    val timeout = options.intValue("timeout-sec", DEFAULT_TIMEOUT_SEC)

    val connected = PcscConnector.connect(reader, timeout)
    try {
        println("已连接读卡器: ${connected.readerName}")
        println("卡 ATR: ${connected.atrHex}")

        val signer = SatochipSigner()
        val unlocked = signer.unlockCard(
            channel = connected.channel,
            derivationPath = path,
            pin = pin
        )
        println("解锁成功")
        println("地址: ${unlocked.address.lowercase()}")
    } finally {
        connected.channel.disconnect(false)
    }
}

private fun runSign(options: CliOptions) {
    val pin = options.required("pin")
    val path = options.value("path", DEFAULT_PATH)
    val reader = options.valueOrNull("reader")
    val timeout = options.intValue("timeout-sec", DEFAULT_TIMEOUT_SEC)
    val payloadInput = loadPayloadInput(options)
    val payload = normalizePayload(payloadInput)

    val request = TpQrCodec.parseSignRequest(payload)

    val connected = PcscConnector.connect(reader, timeout)
    try {
        println("已连接读卡器: ${connected.readerName}")
        println("卡 ATR: ${connected.atrHex}")

        val signer = SatochipSigner()
        val outcome = signer.signWithCard(
            channel = connected.channel,
            request = request,
            derivationPath = path,
            pin = pin
        )

        val responsePath = options.pathValue("out", Paths.get("response.txt"))
        writeTextFile(responsePath, outcome.responsePayload + "\n")

        val qrPath = options.pathValue("qr", Paths.get("response.png"))
        writeQrPng(outcome.responsePayload, qrPath)

        println("签名成功")
        println("签名地址: ${outcome.signerAddress.lowercase()}")
        println("${outcome.digestLabel}: ${outcome.digestHex}")
        println("${outcome.resultLabel}: ${outcome.resultHex}")
        println("回传字符串已写入: ${responsePath.toAbsolutePath()}")
        println("回传二维码已写入: ${qrPath.toAbsolutePath()}")
        println("\n----- TP Response Begin -----")
        println(outcome.responsePayload)
        println("----- TP Response End -----")
    } finally {
        connected.channel.disconnect(false)
    }
}

private fun loadPayloadInput(options: CliOptions): String {
    val payload = options.valueOrNull("payload")
    if (!payload.isNullOrBlank()) {
        return payload.trim()
    }

    val payloadFile = options.valueOrNull("payload-file")
    if (!payloadFile.isNullOrBlank()) {
        val path = Paths.get(payloadFile)
        require(Files.isRegularFile(path)) { "payload-file 不存在: $payloadFile" }
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    val stdinText = generateSequence { readLine() }
        .joinToString("\n")
        .trim()
    require(stdinText.isNotBlank()) {
        "请提供 --payload 或 --payload-file，或者从 stdin 输入 TP 请求字符串"
    }
    return stdinText
}

private fun normalizePayload(input: String): String {
    val lines = input.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    require(lines.isNotEmpty()) { "TP 请求内容为空" }

    if (lines.size == 1 && !lines[0].startsWith("tp:multiFragment-", ignoreCase = true)) {
        return lines[0]
    }

    if (!lines.all { it.startsWith("tp:multiFragment-", ignoreCase = true) }) {
        return lines.joinToString(separator = "")
    }

    val assembler = MultiFragmentAssembler()
    for (line in lines) {
        val parsed = TpQrCodec.parseInput(line)
        val fragment = (parsed as? ParseResult.Fragment)?.fragment
            ?: throw IllegalArgumentException("分片输入中包含非 multiFragment 字符串")

        when (val result = assembler.accept(fragment)) {
            is AssemblyResult.Progress -> Unit
            is AssemblyResult.Complete -> return result.payload
            is AssemblyResult.Error -> throw IllegalArgumentException(result.reason)
        }
    }

    throw IllegalArgumentException("分片未收齐，请补齐所有 tp:multiFragment 二维码内容")
}

private fun writeTextFile(path: Path, content: String) {
    val parent = path.toAbsolutePath().parent
    if (parent != null) {
        Files.createDirectories(parent)
    }
    Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
}

private fun writeQrPng(content: String, path: Path, size: Int = 600) {
    val parent = path.toAbsolutePath().parent
    if (parent != null) {
        Files.createDirectories(parent)
    }

    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to "M"
    )

    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    MatrixToImageWriter.writeToPath(matrix, "PNG", path)
}

private data class CliOptions(
    val values: Map<String, String>,
    val flags: Set<String>
) {
    fun required(name: String): String {
        return valueOrNull(name)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少参数 --$name")
    }

    fun value(name: String, defaultValue: String): String {
        return valueOrNull(name)?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    fun valueOrNull(name: String): String? {
        return values[name]
    }

    fun intValue(name: String, defaultValue: Int): Int {
        return valueOrNull(name)?.toIntOrNull() ?: defaultValue
    }

    fun pathValue(name: String, defaultValue: Path): Path {
        val raw = valueOrNull(name) ?: return defaultValue
        return Paths.get(raw)
    }

    @Suppress("unused")
    fun hasFlag(name: String): Boolean {
        return flags.contains(name)
    }
}

private fun parseOptions(tokens: List<String>): CliOptions {
    val values = linkedMapOf<String, String>()
    val flags = linkedSetOf<String>()

    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        require(token.startsWith("--")) { "参数格式错误: $token" }

        val keyValue = token.removePrefix("--")
        if (keyValue.contains("=")) {
            val idx = keyValue.indexOf('=')
            val key = keyValue.substring(0, idx)
            val value = keyValue.substring(idx + 1)
            values[key] = value
            i += 1
            continue
        }

        val key = keyValue
        val next = tokens.getOrNull(i + 1)
        if (next != null && !next.startsWith("--")) {
            values[key] = next
            i += 2
        } else {
            flags += key
            i += 1
        }
    }

    return CliOptions(values = values, flags = flags)
}

private fun printUsage() {
    println(
        """
        TP Satochip Pi Signer

        用法:
          pi-signer unlock --pin <PIN> [--path <BIP32>] [--reader <关键字>] [--timeout-sec <秒>]
          pi-signer sign --pin <PIN> [--path <BIP32>] (--payload <TP字符串> | --payload-file <文件>)
                         [--reader <关键字>] [--timeout-sec <秒>] [--out response.txt] [--qr response.png]

        例子:
          pi-signer unlock --pin 123456
          pi-signer sign --pin 123456 --payload-file request.txt --out response.txt --qr response.png
        """.trimIndent()
    )
}

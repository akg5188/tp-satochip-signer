package com.smartcard.signer

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream

data class RelayQrBundle(
    val payloads: List<String>,
    val isFragmented: Boolean
)

object RelayQrCodec {
    private const val PREFIX = "tpr1:"
    private const val CHUNK_CHARS = 56

    fun buildRelayPayloads(request: TpSignRequest): RelayQrBundle {
        val payload = TpQrCodec.buildRelayRequest(request).trim()
        require(payload.isNotBlank()) { "TP 请求内容为空" }

        val compressed = deflateUtf8(payload)
        val encoded = Base64.encodeToString(
            compressed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val crc = TpQrCodec.crc32Decimal(encoded)
        val chunks = encoded.chunked(CHUNK_CHARS)
        val pages = chunks.mapIndexed { index, chunk ->
            "$PREFIX${index + 1}/${chunks.size}.$crc.$chunk"
        }
        return RelayQrBundle(payloads = pages, isFragmented = pages.size > 1)
    }

    private fun deflateUtf8(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { deflater ->
            deflater.write(value.toByteArray(StandardCharsets.UTF_8))
        }
        return out.toByteArray()
    }
}

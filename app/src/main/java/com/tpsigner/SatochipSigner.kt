package com.smartcard.signer

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import org.satochip.client.SatochipCommandSet
import org.satochip.client.SatochipParser
import org.satochip.globalplatform.Crypto
import org.satochip.io.CardChannel
import org.web3j.crypto.StructuredDataEncoder

data class SigningOutcome(
    val responsePayload: String,
    val signerAddress: String,
    val digestHex: String,
    val digestLabel: String,
    val resultHex: String,
    val resultLabel: String
)

data class UnlockOutcome(
    val address: String
)

class SatochipSigner {
    fun unlockCard(
        channel: CardChannel,
        derivationPath: String,
        pin: String
    ): UnlockOutcome {
        runCatching { Crypto.addBouncyCastleProvider() }
        val commandSet = SatochipCommandSet(channel)
        commandSet.cardSelect("satochip").checkOK()
        commandSet.cardVerifyPIN(pin.toByteArray(StandardCharsets.UTF_8))

        val keyData = commandSet.cardBip32GetExtendedKey(derivationPath, null, null)
        val pubkey = keyData[0]
        return UnlockOutcome(address = ethereumAddressFromPubkey(pubkey))
    }

    fun signWithCard(
        channel: CardChannel,
        request: TpSignRequest,
        derivationPath: String,
        pin: String
    ): SigningOutcome {
        runCatching { Crypto.addBouncyCastleProvider() }

        val commandSet = SatochipCommandSet(channel)
        commandSet.cardSelect("satochip").checkOK()
        commandSet.cardVerifyPIN(pin.toByteArray(StandardCharsets.UTF_8))

        val keyData = commandSet.cardBip32GetExtendedKey(derivationPath, null, null)
        val pubkey = keyData[0]
        val signerAddress = ethereumAddressFromPubkey(pubkey)

        return when (request) {
            is TpSignTransactionRequest -> signTransaction(commandSet, request, pubkey, signerAddress)
            is TpSignPersonalMessageRequest -> signPersonalMessage(commandSet, request, pubkey, signerAddress)
            is TpSignTypedDataRequest -> signTypedData(commandSet, request, pubkey, signerAddress)
        }
    }

    private fun signTransaction(
        commandSet: SatochipCommandSet,
        request: TpSignTransactionRequest,
        pubkey: ByteArray,
        signerAddress: String
    ): SigningOutcome {
        val tx = EvmTxEncoder.fromTpRequest(request)
        val txHash = EvmTxEncoder.transactionHash(tx)
        val signature = signDigest(commandSet, txHash, pubkey)

        val signedPayload = EvmTxEncoder.signedPayload(tx, signature.recId, signature.r, signature.s)
        val rawTransaction = ensureHexPrefix(bytesToHex(signedPayload))
        val responsePayload = TpQrCodec.buildSignResponse(
            request = request,
            signatureHex = rawTransaction,
            signerAddress = signerAddress
        )

        return SigningOutcome(
            responsePayload = responsePayload,
            signerAddress = signerAddress,
            digestHex = ensureHexPrefix(bytesToHex(txHash)),
            digestLabel = "交易哈希",
            resultHex = rawTransaction,
            resultLabel = "RawTx"
        )
    }

    private fun signPersonalMessage(
        commandSet: SatochipCommandSet,
        request: TpSignPersonalMessageRequest,
        pubkey: ByteArray,
        signerAddress: String
    ): SigningOutcome {
        val digest = personalSignHash(request.message)
        val signature = signDigest(commandSet, digest, pubkey)
        val signatureHex = buildEthMessageSignature(signature.recId, signature.r, signature.s)
        val responsePayload = TpQrCodec.buildSignResponse(
            request = request,
            signatureHex = signatureHex,
            signerAddress = signerAddress
        )

        return SigningOutcome(
            responsePayload = responsePayload,
            signerAddress = signerAddress,
            digestHex = ensureHexPrefix(bytesToHex(digest)),
            digestLabel = "消息哈希",
            resultHex = signatureHex,
            resultLabel = "签名Hex"
        )
    }

    private fun signTypedData(
        commandSet: SatochipCommandSet,
        request: TpSignTypedDataRequest,
        pubkey: ByteArray,
        signerAddress: String
    ): SigningOutcome {
        if (request.isLegacy || request.typedDataJson.trim().startsWith("[")) {
            throw IllegalArgumentException("暂不支持 signTypedDataLegacy 数组格式，请在 TP 端改用 signTypedData/signTypeDataV4")
        }

        val digest = runCatching {
            StructuredDataEncoder(request.typedDataJson).hashStructuredData()
        }.getOrElse { error ->
            throw IllegalArgumentException("typedData 解析失败: ${error.message}")
        }

        val signature = signDigest(commandSet, digest, pubkey)
        val signatureHex = buildEthMessageSignature(signature.recId, signature.r, signature.s)
        val responsePayload = TpQrCodec.buildSignResponse(
            request = request,
            signatureHex = signatureHex,
            signerAddress = signerAddress
        )

        return SigningOutcome(
            responsePayload = responsePayload,
            signerAddress = signerAddress,
            digestHex = ensureHexPrefix(bytesToHex(digest)),
            digestLabel = "TypedData哈希",
            resultHex = signatureHex,
            resultLabel = "签名Hex"
        )
    }

    private fun signDigest(
        commandSet: SatochipCommandSet,
        digest: ByteArray,
        expectedPubkey: ByteArray
    ): SignatureParts {
        require(digest.size == 32) { "摘要长度必须为 32 字节" }

        val signResponse = commandSet.cardSignTransactionHash(0xFF.toByte(), digest, null)
        signResponse.checkOK()
        val derSignature = signResponse.getData()

        val parser = SatochipParser()
        val rs = parser.decodeFromDER(derSignature)
        val recId = recoverRecoveryId(parser, digest, rs, expectedPubkey)
        return SignatureParts(recId = recId, r = rs[0], s = rs[1])
    }

    private fun personalSignHash(message: String): ByteArray {
        val messageBytes = decodePersonalMessage(message)
        val prefix = "\u0019Ethereum Signed Message:\n${messageBytes.size}"
            .toByteArray(StandardCharsets.UTF_8)
        return keccak256(prefix + messageBytes)
    }

    private fun decodePersonalMessage(message: String): ByteArray {
        if (message.startsWith("0x") || message.startsWith("0X")) {
            return runCatching { hexToBytes(message) }
                .getOrElse { message.toByteArray(StandardCharsets.UTF_8) }
        }
        return message.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildEthMessageSignature(recId: Int, r: BigInteger, s: BigInteger): String {
        val normalizedRecId = normalizeRecoveryId(recId)
        val v = (27 + normalizedRecId).toByte()
        val signature = ByteArray(65)
        System.arraycopy(bigIntegerToFixed(r, 32), 0, signature, 0, 32)
        System.arraycopy(bigIntegerToFixed(s, 32), 0, signature, 32, 32)
        signature[64] = v
        return ensureHexPrefix(bytesToHex(signature))
    }

    private fun normalizeRecoveryId(recoveryId: Int): Int {
        return when {
            recoveryId in 0..1 -> recoveryId
            recoveryId in 2..3 -> recoveryId % 2
            else -> throw IllegalArgumentException("非法 recovery id: $recoveryId")
        }
    }

    private fun recoverRecoveryId(
        parser: SatochipParser,
        digest: ByteArray,
        rs: Array<BigInteger>,
        expectedPubkey: ByteArray
    ): Int {
        for (candidate in 0..3) {
            val point = runCatching {
                parser.Recover(digest, rs, candidate, false)
            }.getOrNull() ?: continue

            val pubkey = point.getEncoded(false)
            if (pubkey.contentEquals(expectedPubkey)) {
                return candidate
            }
        }

        if (expectedPubkey.size >= 33) {
            val coordx = expectedPubkey.copyOfRange(1, 33)
            val fallback = parser.recoverRecId(digest, rs, coordx)
            if (fallback >= 0) {
                return fallback
            }
        }

        throw IllegalStateException("无法恢复 recovery id")
    }
}

private data class SignatureParts(
    val recId: Int,
    val r: BigInteger,
    val s: BigInteger
)

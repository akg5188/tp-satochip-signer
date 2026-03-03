package com.smartcard.signer

import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AccessListEntry(
    val address: String,
    val storageKeys: List<String>
)

data class EvmUnsignedTx(
    val type: Int,
    val chainId: Long,
    val nonce: BigInteger,
    val gasLimit: BigInteger,
    val to: String?,
    val value: BigInteger,
    val data: ByteArray,
    val gasPrice: BigInteger?,
    val maxPriorityFeePerGas: BigInteger?,
    val maxFeePerGas: BigInteger?,
    val accessList: List<AccessListEntry>
)

object EvmTxEncoder {
    fun fromTpRequest(request: TpSignTransactionRequest): EvmUnsignedTx {
        val tx = request.txData
        val txType = parseTxType(tx["type"]?.jsonPrimitive?.contentOrNull)

        val nonce = parseQuantity(tx["nonce"]?.jsonPrimitive?.contentOrNull)
        val gasLimit = parseQuantity(
            tx["gas"]?.jsonPrimitive?.contentOrNull
                ?: tx["gasLimit"]?.jsonPrimitive?.contentOrNull
        )
        val to = tx["to"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val value = parseQuantity(tx["value"]?.jsonPrimitive?.contentOrNull)
        val dataHex = tx["data"]?.jsonPrimitive?.contentOrNull
            ?: tx["input"]?.jsonPrimitive?.contentOrNull
            ?: "0x"

        val gasPrice = tx["gasPrice"]?.jsonPrimitive?.contentOrNull?.let(::parseQuantity)
        val maxPriorityFeePerGas = tx["maxPriorityFeePerGas"]?.jsonPrimitive?.contentOrNull?.let(::parseQuantity)
        val maxFeePerGas = tx["maxFeePerGas"]?.jsonPrimitive?.contentOrNull?.let(::parseQuantity)

        val accessList = parseAccessList(tx["accessList"]?.jsonArray)

        return when (txType) {
            0 -> {
                require(gasPrice != null) { "Legacy 交易缺少 gasPrice" }
                EvmUnsignedTx(
                    type = 0,
                    chainId = request.chainId,
                    nonce = nonce,
                    gasLimit = gasLimit,
                    to = to,
                    value = value,
                    data = hexToBytes(dataHex),
                    gasPrice = gasPrice,
                    maxPriorityFeePerGas = null,
                    maxFeePerGas = null,
                    accessList = emptyList()
                )
            }

            2 -> {
                require(maxPriorityFeePerGas != null) { "EIP-1559 交易缺少 maxPriorityFeePerGas" }
                require(maxFeePerGas != null) { "EIP-1559 交易缺少 maxFeePerGas" }
                EvmUnsignedTx(
                    type = 2,
                    chainId = request.chainId,
                    nonce = nonce,
                    gasLimit = gasLimit,
                    to = to,
                    value = value,
                    data = hexToBytes(dataHex),
                    gasPrice = null,
                    maxPriorityFeePerGas = maxPriorityFeePerGas,
                    maxFeePerGas = maxFeePerGas,
                    accessList = accessList
                )
            }

            else -> throw IllegalArgumentException("暂不支持的交易类型: $txType")
        }
    }

    fun unsignedPayload(tx: EvmUnsignedTx): ByteArray {
        return when (tx.type) {
            0 -> encodeLegacyUnsigned(tx)
            2 -> encode1559Unsigned(tx)
            else -> throw IllegalArgumentException("Unsupported tx type")
        }
    }

    fun transactionHash(tx: EvmUnsignedTx): ByteArray {
        return keccak256(unsignedPayload(tx))
    }

    fun signedPayload(tx: EvmUnsignedTx, recId: Int, r: BigInteger, s: BigInteger): ByteArray {
        return when (tx.type) {
            0 -> encodeLegacySigned(tx, recId, r, s)
            2 -> encode1559Signed(tx, recId, r, s)
            else -> throw IllegalArgumentException("Unsupported tx type")
        }
    }

    private fun encodeLegacyUnsigned(tx: EvmUnsignedTx): ByteArray {
        val fields = listOf(
            RlpEncoder.encodeBigInt(tx.nonce),
            RlpEncoder.encodeBigInt(tx.gasPrice ?: BigInteger.ZERO),
            RlpEncoder.encodeBigInt(tx.gasLimit),
            encodeAddress(tx.to),
            RlpEncoder.encodeBigInt(tx.value),
            RlpEncoder.encodeBytes(tx.data),
            RlpEncoder.encodeBigInt(BigInteger.valueOf(tx.chainId)),
            RlpEncoder.encodeBigInt(BigInteger.ZERO),
            RlpEncoder.encodeBigInt(BigInteger.ZERO)
        )
        return RlpEncoder.encodeList(fields)
    }

    private fun encodeLegacySigned(
        tx: EvmUnsignedTx,
        recId: Int,
        r: BigInteger,
        s: BigInteger
    ): ByteArray {
        val normalizedRecId = normalizeRecoveryId(recId)
        val v = BigInteger.valueOf(tx.chainId)
            .multiply(BigInteger.TWO)
            .add(BigInteger.valueOf(35 + normalizedRecId.toLong()))

        val fields = listOf(
            RlpEncoder.encodeBigInt(tx.nonce),
            RlpEncoder.encodeBigInt(tx.gasPrice ?: BigInteger.ZERO),
            RlpEncoder.encodeBigInt(tx.gasLimit),
            encodeAddress(tx.to),
            RlpEncoder.encodeBigInt(tx.value),
            RlpEncoder.encodeBytes(tx.data),
            RlpEncoder.encodeBigInt(v),
            RlpEncoder.encodeBytes(bigIntegerToMinimalBytes(r)),
            RlpEncoder.encodeBytes(bigIntegerToMinimalBytes(s))
        )
        return RlpEncoder.encodeList(fields)
    }

    private fun encode1559Unsigned(tx: EvmUnsignedTx): ByteArray {
        val fields = listOf(
            RlpEncoder.encodeBigInt(BigInteger.valueOf(tx.chainId)),
            RlpEncoder.encodeBigInt(tx.nonce),
            RlpEncoder.encodeBigInt(tx.maxPriorityFeePerGas ?: BigInteger.ZERO),
            RlpEncoder.encodeBigInt(tx.maxFeePerGas ?: BigInteger.ZERO),
            RlpEncoder.encodeBigInt(tx.gasLimit),
            encodeAddress(tx.to),
            RlpEncoder.encodeBigInt(tx.value),
            RlpEncoder.encodeBytes(tx.data),
            encodeAccessList(tx.accessList)
        )
        return byteArrayOf(0x02) + RlpEncoder.encodeList(fields)
    }

    private fun encode1559Signed(
        tx: EvmUnsignedTx,
        recId: Int,
        r: BigInteger,
        s: BigInteger
    ): ByteArray {
        val yParity = normalizeRecoveryId(recId)
        val fields = listOf(
            RlpEncoder.encodeBigInt(BigInteger.valueOf(tx.chainId)),
            RlpEncoder.encodeBigInt(tx.nonce),
            RlpEncoder.encodeBigInt(tx.maxPriorityFeePerGas ?: BigInteger.ZERO),
            RlpEncoder.encodeBigInt(tx.maxFeePerGas ?: BigInteger.ZERO),
            RlpEncoder.encodeBigInt(tx.gasLimit),
            encodeAddress(tx.to),
            RlpEncoder.encodeBigInt(tx.value),
            RlpEncoder.encodeBytes(tx.data),
            encodeAccessList(tx.accessList),
            RlpEncoder.encodeBigInt(BigInteger.valueOf(yParity.toLong())),
            RlpEncoder.encodeBytes(bigIntegerToMinimalBytes(r)),
            RlpEncoder.encodeBytes(bigIntegerToMinimalBytes(s))
        )
        return byteArrayOf(0x02) + RlpEncoder.encodeList(fields)
    }

    private fun encodeAddress(address: String?): ByteArray {
        if (address.isNullOrBlank()) {
            return RlpEncoder.encodeBytes(ByteArray(0))
        }
        return RlpEncoder.encodeBytes(hexToBytes(address))
    }

    private fun encodeAccessList(accessList: List<AccessListEntry>): ByteArray {
        val entries = accessList.map { entry ->
            val storageKeyRlp = entry.storageKeys.map { key ->
                RlpEncoder.encodeBytes(hexToBytes(key))
            }
            RlpEncoder.encodeList(
                listOf(
                    RlpEncoder.encodeBytes(hexToBytes(entry.address)),
                    RlpEncoder.encodeList(storageKeyRlp)
                )
            )
        }
        return RlpEncoder.encodeList(entries)
    }

    private fun parseAccessList(jsonArray: JsonArray?): List<AccessListEntry> {
        if (jsonArray == null) return emptyList()
        return jsonArray.map { element ->
            val obj = element.jsonObject
            val address = obj["address"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("accessList.address 缺失")
            val storageKeys = obj["storageKeys"]?.jsonArray?.map {
                it.jsonPrimitive.content
            } ?: emptyList()
            AccessListEntry(address, storageKeys)
        }
    }

    private fun parseTxType(typeValue: String?): Int {
        if (typeValue.isNullOrBlank()) return 0
        val value = typeValue.trim()
        return if (value.startsWith("0x") || value.startsWith("0X")) {
            value.substring(2).toInt(16)
        } else {
            value.toInt()
        }
    }

    private fun normalizeRecoveryId(recoveryId: Int): Int {
        return when {
            recoveryId in 0..1 -> recoveryId
            recoveryId in 2..3 -> recoveryId % 2
            else -> throw IllegalArgumentException("非法 recovery id: $recoveryId")
        }
    }
}

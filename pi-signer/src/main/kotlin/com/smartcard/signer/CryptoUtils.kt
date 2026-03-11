package com.smartcard.signer

import org.bouncycastle.crypto.digests.KeccakDigest

fun keccak256(input: ByteArray): ByteArray {
    val digest = KeccakDigest(256)
    digest.update(input, 0, input.size)
    val out = ByteArray(32)
    digest.doFinal(out, 0)
    return out
}

fun ethereumAddressFromPubkey(pubKeyUncompressed: ByteArray): String {
    require(pubKeyUncompressed.size == 65 && pubKeyUncompressed[0] == 0x04.toByte()) {
        "Expected uncompressed 65-byte public key"
    }
    val hash = keccak256(pubKeyUncompressed.copyOfRange(1, pubKeyUncompressed.size))
    val address = hash.copyOfRange(12, 32)
    return ensureHexPrefix(bytesToHex(address))
}

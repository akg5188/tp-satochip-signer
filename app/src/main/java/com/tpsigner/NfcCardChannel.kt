package com.smartcard.signer

import android.nfc.tech.IsoDep
import org.satochip.io.APDUCommand
import org.satochip.io.APDUResponse
import org.satochip.io.CardChannel

class NfcCardChannel(private val isoDep: IsoDep) : CardChannel {
    override fun send(cmd: APDUCommand): APDUResponse {
        val response = isoDep.transceive(cmd.serialize())
        return APDUResponse(response)
    }

    override fun isConnected(): Boolean {
        return isoDep.isConnected
    }
}

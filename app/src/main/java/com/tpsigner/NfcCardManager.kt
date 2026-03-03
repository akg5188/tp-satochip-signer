package com.smartcard.signer

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.SystemClock
import org.satochip.io.CardChannel
import org.satochip.io.CardListener

class NfcCardManager(private val loopSleepMs: Int = 50) : Thread(), NfcAdapter.ReaderCallback {
    @Volatile
    private var isoDep: IsoDep? = null

    @Volatile
    private var isRunningCallback = false

    private var listener: CardListener? = null

    fun setCardListener(cardListener: CardListener) {
        listener = cardListener
    }

    fun withConnectedChannel(action: (CardChannel) -> Unit): Boolean {
        val currentIsoDep = isoDep ?: return false
        val connected = runCatching { currentIsoDep.isConnected }.getOrDefault(false)
        if (!connected) {
            return false
        }
        action(NfcCardChannel(currentIsoDep))
        return true
    }

    override fun onTagDiscovered(tag: Tag) {
        val tagIso = IsoDep.get(tag) ?: return
        runCatching {
            tagIso.connect()
            tagIso.timeout = 120000
            isoDep = tagIso
        }
    }

    override fun run() {
        var connected = isConnected()
        while (!isInterrupted) {
            val nowConnected = isConnected()
            if (nowConnected != connected) {
                connected = nowConnected
                if (nowConnected && !isRunningCallback) {
                    onConnected()
                } else if (!nowConnected) {
                    onDisconnected()
                }
            }
            SystemClock.sleep(loopSleepMs.toLong())
        }
    }

    private fun isConnected(): Boolean {
        return try {
            isoDep?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun onConnected() {
        val currentIsoDep = isoDep ?: return
        isRunningCallback = true
        try {
            listener?.onConnected(NfcCardChannel(currentIsoDep))
        } finally {
            isRunningCallback = false
        }
    }

    private fun onDisconnected() {
        isoDep = null
        listener?.onDisconnected()
    }
}

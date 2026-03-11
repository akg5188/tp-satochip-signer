package com.smartcard.signer;

import java.io.IOException;
import javax.smartcardio.Card;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import org.satochip.io.APDUCommand;
import org.satochip.io.APDUResponse;
import org.satochip.io.CardChannel;

public final class PcscCardChannel implements CardChannel {
  private final Card card;
  private final javax.smartcardio.CardChannel channel;

  public PcscCardChannel(Card card, javax.smartcardio.CardChannel channel) {
    this.card = card;
    this.channel = channel;
  }

  @Override
  public APDUResponse send(APDUCommand cmd) throws IOException {
    final CommandAPDU command;
    try {
      command = new CommandAPDU(cmd.serialize());
    } catch (Exception error) {
      throw new IOException("APDU 序列化失败: " + error.getMessage(), error);
    }

    final ResponseAPDU response;
    try {
      response = channel.transmit(command);
    } catch (Exception error) {
      throw new IOException("PC/SC APDU 发送失败: " + error.getMessage(), error);
    }

    return new APDUResponse(response.getBytes());
  }

  @Override
  public boolean isConnected() {
    try {
      return card.getProtocol() != null && !card.getProtocol().isEmpty();
    } catch (Exception ignored) {
      return false;
    }
  }

  public void disconnect(boolean reset) {
    try {
      card.disconnect(reset);
    } catch (Exception ignored) {
      // no-op
    }
  }
}

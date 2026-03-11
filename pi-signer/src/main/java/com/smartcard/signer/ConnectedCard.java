package com.smartcard.signer;

public final class ConnectedCard {
  private final String readerName;
  private final PcscCardChannel channel;
  private final String atrHex;

  public ConnectedCard(String readerName, PcscCardChannel channel, String atrHex) {
    this.readerName = readerName;
    this.channel = channel;
    this.atrHex = atrHex;
  }

  public String getReaderName() {
    return readerName;
  }

  public PcscCardChannel getChannel() {
    return channel;
  }

  public String getAtrHex() {
    return atrHex;
  }
}

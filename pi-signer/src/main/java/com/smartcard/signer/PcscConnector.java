package com.smartcard.signer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.smartcardio.Card;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;

public final class PcscConnector {
  private PcscConnector() {
  }

  public static ConnectedCard connect(String readerHint, int timeoutSeconds) {
    if (timeoutSeconds <= 0) {
      throw new IllegalArgumentException("timeoutSeconds 必须大于 0");
    }

    final List<CardTerminal> terminals;
    try {
      terminals = TerminalFactory.getDefault().terminals().list();
    } catch (Exception error) {
      throw new IllegalStateException("读取 PC/SC 读卡器失败: " + error.getMessage(), error);
    }

    if (terminals.isEmpty()) {
      throw new IllegalStateException("未检测到读卡器。请确认 ACR39U 已连接且 pcscd 正常");
    }

    final List<CardTerminal> selected = orderTerminals(terminals, readerHint);
    final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
    Throwable lastError = null;

    while (System.currentTimeMillis() <= deadline) {
      for (CardTerminal terminal : selected) {
        final boolean present;
        try {
          present = terminal.isCardPresent();
        } catch (Exception ignored) {
          continue;
        }

        if (!present) {
          continue;
        }

        final Card card;
        try {
          card = terminal.connect("*");
        } catch (Exception error) {
          lastError = error;
          continue;
        }

        final PcscCardChannel channel;
        try {
          channel = new PcscCardChannel(card, card.getBasicChannel());
        } catch (Exception error) {
          lastError = error;
          try {
            card.disconnect(false);
          } catch (Exception ignored) {
            // no-op
          }
          continue;
        }

        String atrHex;
        try {
          atrHex = toHex(card.getATR().getBytes());
        } catch (Exception ignored) {
          atrHex = "unknown";
        }

        return new ConnectedCard(terminal.getName(), channel, atrHex);
      }

      try {
        Thread.sleep(250L);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }

    StringBuilder readers = new StringBuilder();
    for (int i = 0; i < selected.size(); i++) {
      if (i > 0) {
        readers.append(", ");
      }
      readers.append(selected.get(i).getName());
    }

    String detail = lastError == null || lastError.getMessage() == null
        ? ""
        : ": " + lastError.getMessage();

    throw new IllegalStateException("超时未检测到插卡。读卡器=[" + readers + "]" + detail);
  }

  private static List<CardTerminal> orderTerminals(List<CardTerminal> terminals, String readerHint) {
    List<CardTerminal> copy = new ArrayList<>(terminals);
    if (readerHint == null || readerHint.trim().isEmpty()) {
      copy.sort(Comparator.comparing(CardTerminal::getName));
      return copy;
    }

    String lowered = readerHint.toLowerCase();
    copy.sort(
        Comparator
            .comparing((CardTerminal t) -> !t.getName().toLowerCase().contains(lowered))
            .thenComparing(CardTerminal::getName)
    );
    return copy;
  }

  private static String toHex(byte[] data) {
    StringBuilder sb = new StringBuilder(data.length * 2);
    for (byte b : data) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}

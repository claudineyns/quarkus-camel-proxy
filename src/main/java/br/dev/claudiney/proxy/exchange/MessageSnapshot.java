package br.dev.claudiney.proxy.exchange;

import java.util.Map;

public record MessageSnapshot(Map<String, String> headers, String payload) {

    public static MessageSnapshot of(Map<String, String> headers, String payload) {
        return new MessageSnapshot(Map.copyOf(headers), payload != null ? payload : "");
    }

    public static MessageSnapshot headersOnly(Map<String, String> headers) {
        return new MessageSnapshot(Map.copyOf(headers), null);
    }
}

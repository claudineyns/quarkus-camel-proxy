package br.dev.claudiney.proxy.exchange;

public record MotorConfig(String url, long connectTimeoutMs, long readTimeoutMs) {}

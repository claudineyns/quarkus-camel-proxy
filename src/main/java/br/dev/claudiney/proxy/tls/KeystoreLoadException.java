package br.dev.claudiney.proxy.tls;

public class KeystoreLoadException extends RuntimeException {
    public KeystoreLoadException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

package br.dev.claudiney.proxy.tls;

import java.io.InputStream;

public interface KeystoreProvider {
    InputStream getKeystore(String host);
}

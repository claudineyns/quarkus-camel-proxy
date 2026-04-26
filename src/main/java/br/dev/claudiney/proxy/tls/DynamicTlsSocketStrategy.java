package br.dev.claudiney.proxy.tls;

import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;

public class DynamicTlsSocketStrategy implements TlsSocketStrategy {

    private final KeystoreProvider keystoreProvider;
    private final String keystorePassword;

    public DynamicTlsSocketStrategy(final KeystoreProvider keystoreProvider, final String keystorePassword) {
        this.keystoreProvider = keystoreProvider;
        this.keystorePassword = keystorePassword;
    }

    @Override
    public SSLSocket upgrade(final Socket socket, final String target, final int port,
                             final Object attachment, final HttpContext context) throws IOException {
        try {
            final SSLContext sslContext = buildSslContext(target);
            final SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory()
                    .createSocket(socket, target, port, true);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Falha no upgrade TLS para host: " + target, e);
        }
    }

    private SSLContext buildSslContext(final String host) throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (final InputStream ks = keystoreProvider.getKeystore(host)) {
            keyStore.load(ks, keystorePassword.toCharArray());
        }
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword.toCharArray());

        final SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }
}

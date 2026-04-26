package br.dev.claudiney.proxy.tls;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class FileKeystoreProvider implements KeystoreProvider {

    private static final String DEFAULT_KEYSTORE = "keystore.p12";

    @ConfigProperty(name = "proxy.tls.keystore-dir")
    String keystoreDir;

    @Override
    public InputStream getKeystore(final String host) {
        final Path hostPath    = Path.of(keystoreDir, host + ".p12");
        final Path defaultPath = Path.of(keystoreDir, DEFAULT_KEYSTORE);

        final Path resolved = Files.exists(hostPath) ? hostPath : defaultPath;

        try {
            return new FileInputStream(resolved.toFile());
        } catch (Exception e) {
            throw new KeystoreLoadException(
                    "Keystore não encontrado para host '" + host + "' nem default em: " + resolved, e);
        }
    }
}

package br.dev.claudiney.proxy.config;

import br.dev.claudiney.proxy.tls.DynamicTlsSocketStrategy;
import br.dev.claudiney.proxy.tls.KeystoreProvider;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.CamelContextCustomizer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpClientConfig implements CamelContextCustomizer {

    @Inject
    KeystoreProvider keystoreProvider;

    @ConfigProperty(name = "proxy.tls.keystore-password", defaultValue = "changeit")
    String keystorePassword;

    @ConfigProperty(name = "proxy.http.pool.max-total", defaultValue = "200")
    int maxTotal;

    @ConfigProperty(name = "proxy.http.pool.max-per-route", defaultValue = "20")
    int maxPerRoute;

    private HttpClientConnectionManager connectionManager;

    @PostConstruct
    void init() {
        final DynamicTlsSocketStrategy tlsStrategy =
                new DynamicTlsSocketStrategy(keystoreProvider, keystorePassword);

        connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tlsStrategy)
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .build();
    }

    @Produces
    @ApplicationScoped
    @Named("proxyHttpClient")
    public CloseableHttpClient proxyHttpClient() {
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(true)
                .build();
    }

    @Override
    public void configure(final CamelContext camelContext) {
        for (final String scheme : new String[]{"http", "https"}) {
            final HttpComponent component = camelContext.getComponent(scheme, HttpComponent.class);
            if (component != null) {
                component.setHttpClientConfigurer(
                        builder -> builder.setConnectionManager(connectionManager)
                                          .setConnectionManagerShared(true)
                );
            }
        }
    }
}

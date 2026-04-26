package br.dev.claudiney.proxy.route;

import br.dev.claudiney.proxy.converter.FileConverter;
import br.dev.claudiney.proxy.exchange.ExchangeKeys;
import br.dev.claudiney.proxy.kafka.ObservabilityCompletionProcessor;
import br.dev.claudiney.proxy.processor.BackendProcessor;
import br.dev.claudiney.proxy.processor.ConfigProcessor;
import br.dev.claudiney.proxy.processor.PostTransformProcessor;
import br.dev.claudiney.proxy.processor.PreTransformProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ProxyRoutes extends RouteBuilder {

    @Inject ConfigProcessor configProcessor;
    @Inject PreTransformProcessor preTransformProcessor;
    @Inject BackendProcessor backendProcessor;
    @Inject PostTransformProcessor postTransformProcessor;
    @Inject ObservabilityCompletionProcessor observabilityProcessor;

    @ConfigProperty(name = "proxy.port.http", defaultValue = "8080")
    int httpPort;

    @ConfigProperty(name = "proxy.port.https", defaultValue = "8443")
    int httpsPort;

    @ConfigProperty(name = "proxy.tls.server-keystore", defaultValue = "keystore.p12")
    String serverKeystore;

    @ConfigProperty(name = "proxy.tls.server-keystore-password", defaultValue = "changeit")
    String serverKeystorePassword;

    @Override
    public void configure() {
        getCamelContext().getTypeConverterRegistry().addTypeConverters(new FileConverter());

        // --- Entradas Netty ---

        from("netty-http:proxy://0.0.0.0:" + httpPort + "?ssl=false&matchOnUriPrefix=true")
                .routeId("netty-http")
                .setProperty(ExchangeKeys.INCOMING_SCHEME, constant("http"))
                .onCompletion()
                    .process(observabilityProcessor)
                .end()
                .to("direct:pipeline");

        from("netty-http:proxy://0.0.0.0:" + httpsPort
                + "?ssl=true"
                + "&trustStoreFile=" + serverKeystore
                + "&keyStoreFile=" + serverKeystore
                + "&passphrase=" + serverKeystorePassword
                + "&matchOnUriPrefix=true")
                .routeId("netty-https")
                .setProperty(ExchangeKeys.INCOMING_SCHEME, constant("https"))
                .onCompletion()
                    .process(observabilityProcessor)
                .end()
                .to("direct:pipeline");

        // --- Pipeline principal (DSL linear) ---

        from("direct:pipeline")
                .routeId("pipeline")
                .to("direct:config")
                .to("direct:pre-transform")
                .to("direct:backend")
                .to("direct:post-transform");

        from("direct:config")
                .routeId("config")
                .process(configProcessor);

        from("direct:pre-transform")
                .routeId("pre-transform")
                .process(preTransformProcessor);

        from("direct:backend")
                .routeId("backend")
                .process(backendProcessor);

        from("direct:post-transform")
                .routeId("post-transform")
                .process(postTransformProcessor);
    }
}

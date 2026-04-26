package br.dev.claudiney.proxy.processor;

import br.dev.claudiney.proxy.exchange.ExchangeKeys;
import br.dev.claudiney.proxy.exchange.MessageSnapshot;
import br.dev.claudiney.proxy.exchange.RecursionState;
import br.dev.claudiney.proxy.exchange.ResourceConfig;
import br.dev.claudiney.proxy.tls.KeystoreLoadException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BackendProcessor implements Processor {

    private static final Logger log = Logger.getLogger(BackendProcessor.class);

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    @Named("proxyHttpClient")
    CloseableHttpClient httpClient;

    @Override
    public void process(final Exchange exchange) throws Exception {
        final ResourceConfig config = exchange.getProperty(ExchangeKeys.RESOURCE_CONFIG, ResourceConfig.class);
        final String backendUrl = resolveBackendUrl(exchange);

        // Refatorado para final: usa intermediate local para evitar reassignment condicional
        final MessageSnapshot refinada = exchange.getProperty(ExchangeKeys.REQUISICAO_REFINADA, MessageSnapshot.class);
        final MessageSnapshot reqToSend = refinada != null
                ? refinada
                : exchange.getProperty(ExchangeKeys.REQUISICAO_BRUTA, MessageSnapshot.class);

        try {
            if (config.binaryResponse()) {
                invokeBackendBinary(exchange, backendUrl, reqToSend, config);
            } else {
                invokeBackendNonBinary(exchange, backendUrl, reqToSend, config);
            }
        } catch (KeystoreLoadException e) {
            log.errorf("KeystoreProvider falhou para %s: %s", backendUrl, e.getMessage());
            abort502(exchange);
        }
    }

    // --- Backend não-binário via camel-http (bufferizado em memória) ---

    private void invokeBackendNonBinary(final Exchange exchange, final String backendUrl,
                                        final MessageSnapshot req,
                                        final ResourceConfig config) throws Exception {
        final String httpMethod = exchange.getMessage().getHeader(Exchange.HTTP_METHOD, "GET", String.class);
        final String endpointUri = backendEndpointUri(backendUrl, config);
        final Map<String, Object> headers = buildForwardHeaders(exchange.getMessage());
        headers.put(Exchange.HTTP_METHOD, httpMethod);

        final RecursionState state = new RecursionState(List.of("backend"));

        while (true) {
            final String body = req.payload();
            try {
                final Exchange result = producerTemplate.request(endpointUri, e -> {
                    e.getMessage().setBody(body);
                    e.getMessage().setHeaders(headers);
                });

                // request() não relança exceções — devolver ao catch para tratamento de retry
                final Exception resultException = result.getException();
                if (resultException != null) throw resultException;

                final int status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                final String responseBody = result.getMessage().getBody(String.class);
                final Map<String, String> responseHeaders = extractHttpHeaders(result.getMessage());

                exchange.setProperty(ExchangeKeys.HTTP_STATUS_BACKEND, status);
                exchange.setProperty(ExchangeKeys.RESPOSTA_BRUTA, MessageSnapshot.of(responseHeaders, responseBody));
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
                exchange.getMessage().setBody(responseBody);
                copyResponseHeaders(result.getMessage(), exchange.getMessage());
                return;

            } catch (Exception e) {
                if (isTcpError(e)) {
                    if (state.currentAttempt() < config.retryCount()) {
                        state.incrementAttempt();
                        log.debugf("Retry %d/%d backend %s", state.currentAttempt(), config.retryCount(), backendUrl);
                        Thread.sleep(config.retryIntervalMs());
                    } else {
                        log.errorf("Tentativas esgotadas para backend %s", backendUrl);
                        abort502(exchange);
                        return;
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    // --- Backend binário via Apache HttpClient ---

    private void invokeBackendBinary(final Exchange exchange, final String backendUrl,
                                     final MessageSnapshot req,
                                     final ResourceConfig config) throws Exception {
        final RecursionState state = new RecursionState(List.of("backend"));
        final String httpMethod = exchange.getMessage().getHeader(Exchange.HTTP_METHOD, "GET", String.class);

        while (true) {
            final HttpUriRequestBase request = new HttpUriRequestBase(httpMethod, URI.create(backendUrl));

            try {
                httpClient.execute(request, response -> {
                    final int status = response.getCode();
                    final Map<String, String> responseHeaders = new HashMap<>();
                    for (final var h : response.getHeaders()) {
                        responseHeaders.put(h.getName(), h.getValue());
                    }

                    exchange.setProperty(ExchangeKeys.HTTP_STATUS_BACKEND, status);
                    exchange.setProperty(ExchangeKeys.RESPOSTA_BRUTA,
                            MessageSnapshot.headersOnly(responseHeaders));
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
                    responseHeaders.forEach((k, v) -> exchange.getMessage().setHeader(k, v));

                    final byte[] body = response.getEntity() != null
                            ? EntityUtils.toByteArray(response.getEntity())
                            : new byte[0];
                    exchange.getMessage().setBody(body);
                    return null;
                });
                return;

            } catch (IOException e) {
                if (isTcpError(e)) {
                    if (state.currentAttempt() < config.retryCount()) {
                        state.incrementAttempt();
                        log.debugf("Retry %d/%d backend binário %s", state.currentAttempt(), config.retryCount(), backendUrl);
                        Thread.sleep(config.retryIntervalMs());
                    } else {
                        log.errorf("Tentativas esgotadas para backend binário %s", backendUrl);
                        abort502(exchange);
                        return;
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    private String resolveBackendUrl(final Exchange exchange) {
        final Message msg = exchange.getMessage();
        final String altHost = exchange.getProperty(ExchangeKeys.PROXY_BACKEND_HOST, String.class);

        final String scheme;
        final String host;

        if (altHost != null && !altHost.isBlank()) {
            // Formato esperado: scheme://host[:port] — lido da propriedade da Exchange
            final URI parsed = URI.create(altHost);
            scheme = parsed.getScheme();
            host   = parsed.getPort() != -1
                    ? parsed.getHost() + ":" + parsed.getPort()
                    : parsed.getHost();
        } else {
            // Sem header alternativo: herda scheme da entrada do proxy e usa Host padrão
            scheme = exchange.getProperty(ExchangeKeys.INCOMING_SCHEME, "http", String.class);
            host   = msg.getHeader("Host", String.class);
        }

        final String path  = msg.getHeader(Exchange.HTTP_URI, "/", String.class);
        final String query = msg.getHeader(Exchange.HTTP_QUERY, String.class);

        if (host == null) return path;

        final String base = scheme + "://" + host + path;
        return (query != null && !query.isBlank()) ? base + "?" + query : base;
    }

    private String backendEndpointUri(final String url, final ResourceConfig config) {
        final String sep = url.contains("?") ? "&" : "?";
        return url + sep
                + "bridgeEndpoint=true"
                + "&throwExceptionOnFailure=false"
                + "&httpClient.connectionRequestTimeout=" + config.connectTimeoutMs()
                + "&httpClient.responseTimeout=" + config.readTimeoutMs();
    }

    private Map<String, Object> buildForwardHeaders(final Message msg) {
        final Map<String, Object> headers = new HashMap<>();
        msg.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel") && v != null) headers.put(k, v);
        });
        return headers;
    }

    private Map<String, String> extractHttpHeaders(final Message msg) {
        final Map<String, String> headers = new HashMap<>();
        msg.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel") && v != null) headers.put(k, v.toString());
        });
        return headers;
    }

    private void copyResponseHeaders(final Message source, final Message target) {
        source.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel") && v != null) target.setHeader(k, v);
        });
    }

    private boolean isTcpError(final Throwable e) {
        Throwable cause = e; // travessia da cadeia — não pode ser final
        while (cause != null) {
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static final String BODY_502 =
            "{\"error\":[{\"code\":\"502\",\"message\":\"Não foi possível completar a operação\"}]}";

    private void abort502(final Exchange exchange) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 502);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(BODY_502);
        exchange.setRouteStop(true);
    }
}

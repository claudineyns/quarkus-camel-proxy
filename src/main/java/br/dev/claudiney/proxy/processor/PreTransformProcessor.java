package br.dev.claudiney.proxy.processor;

import br.dev.claudiney.proxy.exchange.ExchangeKeys;
import br.dev.claudiney.proxy.exchange.MessageSnapshot;
import br.dev.claudiney.proxy.exchange.MotorConfig;
import br.dev.claudiney.proxy.exchange.RecursionState;
import br.dev.claudiney.proxy.exchange.ResourceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class PreTransformProcessor implements Processor {

    private static final Logger log = Logger.getLogger(PreTransformProcessor.class);

    @Inject
    ProducerTemplate producerTemplate;

    @Override
    public void process(final Exchange exchange) throws Exception {
        final ResourceConfig config = exchange.getProperty(ExchangeKeys.RESOURCE_CONFIG, ResourceConfig.class);

        if (config.motoresRequisicao().isEmpty()) return;

        final RecursionState state = new RecursionState(config.motoresRequisicao());
        exchange.setProperty(ExchangeKeys.PRE_TRANSFORM_STATE, state);

        final MessageSnapshot bruta = exchange.getProperty(ExchangeKeys.REQUISICAO_BRUTA, MessageSnapshot.class);
        String currentBody = bruta.payload(); // acumulador do loop — não pode ser final

        while (!state.isEmpty()) {
            final String motorId = state.currentServiceId();
            final MotorConfig motor = config.motorConfig(motorId);

            if (motor == null) {
                log.warnf("Motor %s não encontrado no Redis, pulando", motorId);
                state.advanceToNext();
                continue;
            }

            final boolean success = invokeMotor(exchange, motor, config, state, currentBody);
            if (!success) return;

            // Headers e body da resposta do motor já atualizados em exchange.getMessage()
            currentBody = exchange.getMessage().getBody(String.class);
            state.advanceToNext();
        }

        final Map<String, String> refinedHeaders = extractHttpHeaders(exchange.getMessage());
        exchange.setProperty(ExchangeKeys.REQUISICAO_REFINADA,
                MessageSnapshot.of(refinedHeaders, currentBody));
    }

    private boolean invokeMotor(final Exchange exchange, final MotorConfig motor,
                                final ResourceConfig config, final RecursionState state,
                                final String body) throws Exception {
        final String endpointUri = motorEndpointUri(motor);
        final Map<String, Object> headers = buildMotorHeaders(body, exchange.getMessage());

        while (true) {
            try {
                final Exchange result = producerTemplate.request(endpointUri, e -> {
                    e.getMessage().setBody(body);
                    e.getMessage().setHeaders(headers);
                });

                // request() não relança exceções — devolver ao catch para tratamento de retry
                final Exception resultException = result.getException();
                if (resultException != null) throw resultException;

                final int status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);

                if (status >= 200 && status < 300) {
                    exchange.getMessage().setBody(result.getMessage().getBody(String.class));
                    // Propagar headers da resposta para o próximo motor encadeado
                    propagateResponseHeaders(result.getMessage(), exchange.getMessage());
                    return true;
                }

                log.debugf("Motor %s retornou HTTP %d", motor.url(), status);
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
                exchange.getMessage().setBody(result.getMessage().getBody());
                exchange.setProperty(ExchangeKeys.RESPOSTA_BRUTA,
                        MessageSnapshot.of(extractHttpHeaders(result.getMessage()), result.getMessage().getBody(String.class)));
                exchange.setRouteStop(true);
                return false;

            } catch (Exception e) {
                if (isTcpError(e)) {
                    if (state.currentAttempt() < config.retryCount()) {
                        state.incrementAttempt();
                        log.debugf("Retry %d/%d motor %s", state.currentAttempt(), config.retryCount(), motor.url());
                        Thread.sleep(config.retryIntervalMs());
                    } else {
                        log.errorf("Tentativas esgotadas para motor %s", motor.url());
                        abort502(exchange);
                        return false;
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    private String motorEndpointUri(final MotorConfig motor) {
        final String url = motor.url();
        final String sep = url.contains("?") ? "&" : "?";
        return url + sep
                + "bridgeEndpoint=true"
                + "&throwExceptionOnFailure=false"
                + "&httpClient.connectionRequestTimeout=" + motor.connectTimeoutMs()
                + "&httpClient.responseTimeout=" + motor.readTimeoutMs();
    }

    private Map<String, Object> buildMotorHeaders(final String body, final Message msg) {
        final Map<String, Object> headers = new HashMap<>();
        msg.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel") && !k.equalsIgnoreCase("Content-Length") && v != null) {
                headers.put(k, v);
            }
        });

        // Motores de transformação sempre acionados via POST
        headers.put(Exchange.HTTP_METHOD, "POST");

        // Corpo vazio sem Content-Type: POST sem corpo com defaults para serviço JSON
        final boolean emptyBody = body == null || body.isBlank();
        final boolean hasContentType = headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Content-Type"));
        if (emptyBody && !hasContentType) {
            headers.put("Content-Type", "application/json");
            headers.put("Content-Length", "0");
        }

        return headers;
    }

    private void propagateResponseHeaders(final Message source, final Message target) {
        source.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel") && !k.equalsIgnoreCase("Content-Length") && v != null) {
                target.setHeader(k, v);
            }
        });
    }

    private Map<String, String> extractHttpHeaders(final Message msg) {
        final Map<String, String> headers = new HashMap<>();
        msg.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel") && v != null) headers.put(k, v.toString());
        });
        return headers;
    }

    private boolean isTcpError(final Throwable e) {
        Throwable cause = e; // travessia da cadeia — não pode ser final
        while (cause != null) {
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || (cause instanceof IOException && !(cause instanceof java.net.http.HttpTimeoutException))) {
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

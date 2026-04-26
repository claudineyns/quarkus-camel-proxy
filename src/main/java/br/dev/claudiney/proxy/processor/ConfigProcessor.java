package br.dev.claudiney.proxy.processor;

import br.dev.claudiney.proxy.exchange.ExchangeKeys;
import br.dev.claudiney.proxy.exchange.MessageSnapshot;
import br.dev.claudiney.proxy.exchange.MotorConfig;
import br.dev.claudiney.proxy.exchange.ResourceConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ConfigProcessor implements Processor {

    private static final Logger log = Logger.getLogger(ConfigProcessor.class);

    @Inject
    RedisDataSource redis;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void process(final Exchange exchange) throws Exception {
        final Message msg = exchange.getMessage();

        final String rawBody = msg.getBody(String.class);
        final Map<String, String> rawHeaders = extractHttpHeaders(msg);
        exchange.setProperty(ExchangeKeys.REQUISICAO_BRUTA, MessageSnapshot.of(rawHeaders, rawBody));

        // Leitura case-insensitive, remoção do header e armazenamento como propriedade da Exchange
        final String resourceId = readAndRemoveHeader(msg, "X-Resource-Id");
        exchange.setProperty(ExchangeKeys.RESOURCE_ID, resourceId);

        final String proxyBackendHost = readAndRemoveHeader(msg, "x-proxy-backend-host");
        exchange.setProperty(ExchangeKeys.PROXY_BACKEND_HOST, proxyBackendHost);

        if (resourceId == null || resourceId.isBlank()) {
            exchange.setProperty(ExchangeKeys.RESOURCE_CONFIG, ResourceConfig.EMPTY);
            return;
        }

        try {
            final HashCommands<String, String, String> hash = redis.hash(String.class);

            final Map<String, String> resourceData = hash.hgetall("integracao:recurso:" + resourceId);
            if (resourceData == null || resourceData.isEmpty()) {
                exchange.setProperty(ExchangeKeys.RESOURCE_CONFIG, ResourceConfig.EMPTY);
                return;
            }

            final List<String> motoresRequisicao = parseUuidList(resourceData.get("motores_requisicao"));
            final List<String> motoresResposta   = parseUuidList(resourceData.get("motores_resposta"));

            final Map<String, MotorConfig> motores = new LinkedHashMap<>();
            final List<String> allMotorIds = new ArrayList<>(motoresRequisicao);
            allMotorIds.addAll(motoresResposta);

            for (final String motorId : allMotorIds) {
                if (motores.containsKey(motorId)) continue;
                final Map<String, String> motorData = hash.hgetall("integracao:motor:" + motorId);
                if (motorData != null && !motorData.isEmpty()) {
                    motores.put(motorId, new MotorConfig(
                            motorData.get("url"),
                            parseLong(motorData.get("connect_timeout_ms"), 5000L),
                            parseLong(motorData.get("read_timeout_ms"), 30000L)
                    ));
                }
            }

            final ResourceConfig config = new ResourceConfig(
                    parseInt(resourceData.get("retry_count"), 0),
                    parseLong(resourceData.get("retry_interval_ms"), 0L),
                    "true".equalsIgnoreCase(resourceData.get("binary_response")),
                    parseLong(resourceData.get("connect_timeout_ms"), 5000L),
                    parseLong(resourceData.get("read_timeout_ms"), 30000L),
                    List.copyOf(motoresRequisicao),
                    List.copyOf(motoresResposta),
                    Map.copyOf(motores)
            );

            exchange.setProperty(ExchangeKeys.RESOURCE_CONFIG, config);

        } catch (Exception e) {
            log.errorf("Redis indisponível para recurso %s: %s", resourceId, e.getMessage());
            abort502(exchange);
        }
    }

    private String readAndRemoveHeader(final Message msg, final String headerName) {
        final String actualKey = msg.getHeaders().keySet().stream()
                .filter(k -> k.equalsIgnoreCase(headerName))
                .findFirst()
                .orElse(null);
        if (actualKey == null) return null;
        final String value = msg.getHeader(actualKey, String.class);
        msg.removeHeader(actualKey);
        return value;
    }

    private Map<String, String> extractHttpHeaders(final Message msg) {
        final Map<String, String> headers = new HashMap<>();
        msg.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel") && v != null) {
                headers.put(k, v.toString());
            }
        });
        return headers;
    }

    private List<String> parseUuidList(final String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warnf("Falha ao parsear lista de UUIDs: %s", json);
            return List.of();
        }
    }

    private int parseInt(final String value, final int defaultValue) {
        try { return value != null ? Integer.parseInt(value) : defaultValue; }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private long parseLong(final String value, final long defaultValue) {
        try { return value != null ? Long.parseLong(value) : defaultValue; }
        catch (NumberFormatException e) { return defaultValue; }
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

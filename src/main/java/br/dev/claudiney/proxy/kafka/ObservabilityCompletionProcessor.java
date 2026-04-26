package br.dev.claudiney.proxy.kafka;

import br.dev.claudiney.proxy.exchange.ExchangeKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ObservabilityCompletionProcessor implements Processor {

    private static final Logger log = Logger.getLogger(ObservabilityCompletionProcessor.class);

    @Inject
    ObservabilityPayloadBuilder builder;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ProducerTemplate producerTemplate;

    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @Override
    public void process(final Exchange exchange) {
        try {
            final ObservabilityPayload payload = builder.build(exchange);
            final String json = objectMapper.writeValueAsString(payload);

            // Fire-and-forget: envio assíncrono sem bloquear o pipeline
            producerTemplate.asyncSendBody(kafkaEndpoint(), json);

        } catch (Exception e) {
            final String resourceId = exchange.getProperty(ExchangeKeys.RESOURCE_ID, String.class);
            log.errorf("Falha ao publicar observabilidade (recurso=%s): %s", resourceId, e.getMessage());
        }
    }

    private String kafkaEndpoint() {
        return "kafka:proxy-events"
                + "?brokers=" + bootstrapServers;
    }
}

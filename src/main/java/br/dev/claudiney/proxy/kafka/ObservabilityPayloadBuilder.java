package br.dev.claudiney.proxy.kafka;

import br.dev.claudiney.proxy.exchange.ExchangeKeys;
import br.dev.claudiney.proxy.exchange.MessageSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;

@ApplicationScoped
public class ObservabilityPayloadBuilder {

    public ObservabilityPayload build(final Exchange exchange) {
        final int httpStatusDefinitivo = exchange.getMessage()
                .getHeader(Exchange.HTTP_RESPONSE_CODE, 500, Integer.class);

        final Integer httpStatusBackend = exchange.getProperty(ExchangeKeys.HTTP_STATUS_BACKEND, Integer.class);

        final MessageSnapshot reqBruta    = exchange.getProperty(ExchangeKeys.REQUISICAO_BRUTA,    MessageSnapshot.class);
        final MessageSnapshot reqRefinada = exchange.getProperty(ExchangeKeys.REQUISICAO_REFINADA,  MessageSnapshot.class);
        final MessageSnapshot resBruta    = exchange.getProperty(ExchangeKeys.RESPOSTA_BRUTA,       MessageSnapshot.class);
        final MessageSnapshot resRefinada = exchange.getProperty(ExchangeKeys.RESPOSTA_REFINADA,    MessageSnapshot.class);

        return new ObservabilityPayload(
                httpStatusBackend,
                httpStatusDefinitivo,
                toDto(reqBruta),
                toDto(reqRefinada),
                toDto(resBruta),
                toDto(resRefinada)
        );
    }

    private ObservabilityPayload.MensagemDto toDto(final MessageSnapshot snapshot) {
        if (snapshot == null) return null;
        return new ObservabilityPayload.MensagemDto(snapshot.headers(), snapshot.payload());
    }
}

package br.dev.claudiney.proxy.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservabilityPayload(

        @JsonProperty("http_status_backend")
        Integer httpStatusBackend,

        @JsonProperty("http_status_definitivo")
        int httpStatusDefinitivo,

        @JsonProperty("requisicao_bruta")
        MensagemDto requisicaoBruta,

        @JsonProperty("requisicao_refinada")
        MensagemDto requisicaoRefinada,

        @JsonProperty("resposta_bruta")
        MensagemDto respostaBruta,

        @JsonProperty("resposta_refinada")
        MensagemDto respostaRefinada
) {
    public record MensagemDto(
            Map<String, String> headers,
            String payload
    ) {}
}

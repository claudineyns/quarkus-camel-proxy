package br.dev.claudiney.proxy.exchange;

import java.util.List;
import java.util.Map;

public record ResourceConfig(
        int retryCount,
        long retryIntervalMs,
        boolean binaryResponse,
        long connectTimeoutMs,
        long readTimeoutMs,
        List<String> motoresRequisicao,
        List<String> motoresResposta,
        Map<String, MotorConfig> motores
) {
    public static final ResourceConfig EMPTY = new ResourceConfig(
            0, 0, false, 5000, 30000,
            List.of(), List.of(), Map.of()
    );

    public MotorConfig motorConfig(String motorId) {
        return motores.get(motorId);
    }
}

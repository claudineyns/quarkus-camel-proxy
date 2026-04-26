#!/usr/bin/env bash
# Recurso com motor beeceptor referenciado duas vezes em cada lista de transformação.
# O próprio beeceptor é usado também como backend.
# Pipeline:
#   config
#   → pré-transform 1 (beeceptor)
#   → pré-transform 2 (beeceptor, recebe resultado do anterior)
#   → backend (beeceptor, recebe resultado da pré-transform 2)
#   → pós-transform 1 (beeceptor, recebe resposta do backend)
#   → pós-transform 2 (beeceptor, recebe resultado do anterior)
#   → resposta (JSON aninhado em 3 níveis)
#
# Recurso: 9fc922d7-e19e-48ed-bd3c-1e073e58da98
#   motores_requisicao: ["34ff6b11-...","34ff6b11-..."] → beeceptor × 2
#   motores_resposta:   ["34ff6b11-...","34ff6b11-..."] → beeceptor × 2
#   binary_response:    false

curl -v \
  --header "X-Resource-Id: 9fc922d7-e19e-48ed-bd3c-1e073e58da98" \
  --header "x-proxy-backend-host: https://echo.free.beeceptor.com" \
  http://localhost:8181/

#!/usr/bin/env bash
# Recurso com motor de transformação na requisição e na resposta.
# O próprio beeceptor é usado também como backend.
# Pipeline: config → pré-transform (beeceptor) → backend (beeceptor) → pós-transform (beeceptor) → resposta
#
# Recurso: c2c831a9-01de-4a0e-a344-c4733cde1d7d
#   motores_requisicao: ["34ff6b11-..."] → beeceptor echo
#   motores_resposta:   ["34ff6b11-..."] → beeceptor echo
#   binary_response:    false

curl -v \
  --header "X-Resource-Id: c2c831a9-01de-4a0e-a344-c4733cde1d7d" \
  --header "x-proxy-backend-host: https://echo.free.beeceptor.com" \
  http://localhost:8181/

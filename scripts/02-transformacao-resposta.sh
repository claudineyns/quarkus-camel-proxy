#!/usr/bin/env bash
# Recurso com motor de transformação apenas na resposta (beeceptor echo).
# Pipeline: config → backend (www.example.com) → pós-transform (beeceptor) → resposta
#
# Recurso: 3ef4adec-dd91-4d4d-8db4-ee9153f308c4
#   motores_requisicao: []
#   motores_resposta:   ["34ff6b11-1dd5-41e3-a244-3b7187d4560e"] → beeceptor echo
#   binary_response:    false

curl -v \
  --header "X-Resource-Id: 3ef4adec-dd91-4d4d-8db4-ee9153f308c4" \
  --header "x-proxy-backend-host: https://www.example.com" \
  http://localhost:8181/

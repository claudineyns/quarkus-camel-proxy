#!/usr/bin/env bash
# Recurso sem motores de transformação.
# Pipeline: config → backend (www.example.com) → resposta
#
# Recurso: 5a909ead-03ee-41aa-8e24-902855365ce6
#   motores_requisicao: []
#   motores_resposta:   []
#   binary_response:    false

curl -v \
  --header "X-Resource-Id: 5a909ead-03ee-41aa-8e24-902855365ce6" \
  --header "x-proxy-backend-host: https://www.example.com" \
  http://localhost:8181/

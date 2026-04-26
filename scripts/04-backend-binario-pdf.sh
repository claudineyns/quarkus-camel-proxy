#!/usr/bin/env bash
# Recurso com sinalização de backend binário.
# Pré-transform e pós-transform são ignoradas. Body retornado diretamente ao cliente.
# Pipeline: config → backend (sample-files.com, PDF) → resposta binária
#
# Recurso: 916f81be-266c-4f27-9a7e-917c2d3b38ae
#   motores_requisicao: []
#   motores_resposta:   []
#   binary_response:    true

curl -v \
  --header "X-Resource-Id: 916f81be-266c-4f27-9a7e-917c2d3b38ae" \
  --header "x-proxy-backend-host: https://sample-files.com" \
  --output /tmp/response.pdf \
  http://localhost:8181/downloads/documents/pdf/sample-10-page-pdf-a4-size.pdf

echo ""
echo "Arquivo recebido:"
file /tmp/response.pdf

#!/usr/bin/env bash
# Recria todas as chaves Redis de desenvolvimento no container redis-dev.
# Uso: bash dev/redis-seeds.sh

set -euo pipefail

REDIS="podman exec redis-dev redis-cli"

echo "=== Motor de transformação ==="

# Motor: beeceptor echo
$REDIS HSET "integracao:motor:34ff6b11-1dd5-41e3-a244-3b7187d4560e" \
  url              "https://echo.free.beeceptor.com" \
  connect_timeout_ms 5000 \
  read_timeout_ms  10000

echo "=== Recursos ==="

# Recurso 1: sem transformações, não binário
$REDIS HSET "integracao:recurso:5a909ead-03ee-41aa-8e24-902855365ce6" \
  retry_count       3 \
  retry_interval_ms 500 \
  binary_response   false \
  connect_timeout_ms 5000 \
  read_timeout_ms   30000 \
  motores_requisicao '[]' \
  motores_resposta   '[]'

# Recurso 2: motor beeceptor apenas na resposta
$REDIS HSET "integracao:recurso:3ef4adec-dd91-4d4d-8db4-ee9153f308c4" \
  retry_count       3 \
  retry_interval_ms 500 \
  binary_response   false \
  connect_timeout_ms 5000 \
  read_timeout_ms   30000 \
  motores_requisicao '[]' \
  motores_resposta   '["34ff6b11-1dd5-41e3-a244-3b7187d4560e"]'

# Recurso 3: motor beeceptor na requisição e na resposta
$REDIS HSET "integracao:recurso:c2c831a9-01de-4a0e-a344-c4733cde1d7d" \
  retry_count       3 \
  retry_interval_ms 500 \
  binary_response   false \
  connect_timeout_ms 5000 \
  read_timeout_ms   30000 \
  motores_requisicao '["34ff6b11-1dd5-41e3-a244-3b7187d4560e"]' \
  motores_resposta   '["34ff6b11-1dd5-41e3-a244-3b7187d4560e"]'

# Recurso 4: backend binário, sem transformações
$REDIS HSET "integracao:recurso:916f81be-266c-4f27-9a7e-917c2d3b38ae" \
  retry_count       3 \
  retry_interval_ms 500 \
  binary_response   true \
  connect_timeout_ms 5000 \
  read_timeout_ms   30000 \
  motores_requisicao '[]' \
  motores_resposta   '[]'

# Recurso 5: motor beeceptor duas vezes na requisição e duas vezes na resposta
$REDIS HSET "integracao:recurso:9fc922d7-e19e-48ed-bd3c-1e073e58da98" \
  retry_count       3 \
  retry_interval_ms 500 \
  binary_response   false \
  connect_timeout_ms 5000 \
  read_timeout_ms   30000 \
  motores_requisicao '["34ff6b11-1dd5-41e3-a244-3b7187d4560e","34ff6b11-1dd5-41e3-a244-3b7187d4560e"]' \
  motores_resposta   '["34ff6b11-1dd5-41e3-a244-3b7187d4560e","34ff6b11-1dd5-41e3-a244-3b7187d4560e"]'

# Recurso 6: backend binário, sem transformações (variante)
$REDIS HSET "integracao:recurso:71e247ae-61bd-4213-b20e-a7cfae422fae" \
  retry_count       3 \
  retry_interval_ms 500 \
  binary_response   true \
  connect_timeout_ms 5000 \
  read_timeout_ms   30000 \
  motores_requisicao '[]' \
  motores_resposta   '[]'

echo "Seeds aplicados com sucesso."

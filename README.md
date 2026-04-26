# quarkus-camel-proxy

Proxy HTTP/HTTPS para APIs REST implementado com Java 21 e Apache Camel sobre Quarkus (Red Hat). Atua como intermediário entre um gateway de API e backends dinâmicos, com suporte a transformações configuráveis por recurso, mTLS, roteamento dinâmico via Redis e observabilidade via Kafka.

---

## Pré-requisitos

| Ferramenta | Versão mínima |
|---|---|
| Java | 21 |
| Maven | 3.9+ |
| Podman ou Docker | qualquer versão recente |
| Redis | 7+ |
| Kafka | 3.x (KRaft) |

O repositório Red Hat (`https://maven.repository.redhat.com/ga/`) deve estar configurado no `settings.xml` do Maven.

---

## Stack

| Componente | Versão |
|---|---|
| Quarkus | Red Hat `3.27.3.redhat-00003` |
| Apache Camel | via `com.redhat.quarkus.platform:quarkus-camel-bom` |
| Java | 21 |

---

## Execução local

### 1. Subir serviços de infraestrutura

```bash
cd dev
python -m podman_compose up -d
```

Isso inicia Redis na porta `6379` e Kafka na porta `9092`.

### 2. Iniciar o proxy em modo desenvolvimento

```bash
mvn quarkus:dev
```

O perfil `dev` é ativado automaticamente, carregando `application-dev.properties`:

| Porta | Protocolo |
|---|---|
| `8181` | HTTP |
| `8282` | HTTPS (TLS bridging) |

---

## Configuração

As propriedades são definidas em `src/main/resources/application.properties` e sobrescritas por `application-dev.properties` no perfil `dev`.

| Propriedade | Descrição | Padrão |
|---|---|---|
| `proxy.port.http` | Porta de entrada HTTP | `8080` |
| `proxy.port.https` | Porta de entrada HTTPS | `8443` |
| `proxy.tls.server-keystore` | Caminho da keystore do proxy (PKCS12) | `/mnt/certs/keystore.p12` |
| `proxy.tls.server-keystore-password` | Senha da keystore do servidor | `changeit` |
| `proxy.tls.keystore-dir` | Diretório com keystores de cliente por host | `/mnt/client-certs` |
| `proxy.tls.keystore-password` | Senha das keystores de cliente | `changeit` |
| `proxy.http.pool.max-total` | Máximo de conexões no pool HTTP | `200` |
| `proxy.http.pool.max-per-route` | Máximo de conexões por host | `20` |
| `quarkus.redis.hosts` | Endereço Redis | `redis://localhost:6379` |
| `kafka.bootstrap.servers` | Bootstrap servers do Kafka | `localhost:9092` |

---

## Como usar

### Headers da requisição

| Header | Obrigatório | Descrição |
|---|---|---|
| `X-Resource-Id` | Sim | UUID do recurso configurado no Redis |
| `x-proxy-backend-host` | Não | URL base do backend: `scheme://host[:port]`. Tem prioridade sobre `Host`. Permite acionar um backend HTTPS a partir de uma entrada HTTP. |

Se `x-proxy-backend-host` estiver ausente, o proxy usa o header `Host` da requisição e herda o scheme da própria entrada (HTTP ou HTTPS).

### Exemplos de chamada

```bash
# Proxy HTTP → backend HTTPS via x-proxy-backend-host
curl --header "X-Resource-Id: <uuid-do-recurso>" \
     --header "x-proxy-backend-host: https://api.backend.com" \
     http://localhost:8181/v1/endpoint

# Proxy HTTP → backend HTTP (scheme herdado, host via header Host)
curl --header "X-Resource-Id: <uuid-do-recurso>" \
     --header "Host: api.backend.com" \
     http://localhost:8181/v1/endpoint
```

---

## Configuração Redis

### Recurso

**Chave:** `integracao:recurso:{uuid}` — tipo Hash

```bash
redis-cli HSET "integracao:recurso:{uuid}" \
  retry_count 3 \
  retry_interval_ms 500 \
  binary_response false \
  connect_timeout_ms 5000 \
  read_timeout_ms 30000 \
  motores_requisicao '["uuid-motor-1","uuid-motor-2"]' \
  motores_resposta   '["uuid-motor-3"]'
```

| Campo | Tipo | Descrição |
|---|---|---|
| `retry_count` | inteiro | Tentativas máximas por serviço em caso de falha TCP |
| `retry_interval_ms` | inteiro | Intervalo entre tentativas (ms) |
| `binary_response` | booleano | `true` desabilita transformações e retorna o body do backend como stream |
| `connect_timeout_ms` | inteiro | Timeout de conexão ao backend (ms) |
| `read_timeout_ms` | inteiro | Timeout de leitura do backend (ms) |
| `motores_requisicao` | JSON array de UUIDs | Serviços de pré-transformação da requisição (em ordem) |
| `motores_resposta` | JSON array de UUIDs | Serviços de pós-transformação da resposta (em ordem) |

Se a chave não existir, o pipeline executa sem transformações e sem retry.

### Motor de transformação

**Chave:** `integracao:motor:{uuid}` — tipo Hash

```bash
redis-cli HSET "integracao:motor:{uuid}" \
  url "https://servico-de-transformacao.com/endpoint" \
  connect_timeout_ms 3000 \
  read_timeout_ms 10000
```

| Campo | Tipo | Descrição |
|---|---|---|
| `url` | string | Endpoint do serviço de transformação |
| `connect_timeout_ms` | inteiro | Timeout de conexão ao serviço (ms) |
| `read_timeout_ms` | inteiro | Timeout de leitura do serviço (ms) |

---

## Pipeline de processamento

```
Entrada (HTTP/HTTPS)
  → config          lê Redis, captura requisicao_bruta
  → pré-transform   encadeia motores de requisição (POST, encadeamento progressivo)
  → backend         invoca o backend configurado
  → pós-transform   encadeia motores de resposta (POST, encadeamento progressivo)
  → resposta ao cliente
  → onCompletion    publica evento de observabilidade no Kafka (fire-and-forget)
```

### Comportamento dos motores de transformação

- Sempre invocados via **POST**
- Se o corpo estiver vazio e não houver `Content-Type`: o motor recebe `Content-Type: application/json` e `Content-Length: 0`
- Em motores encadeados, o resultado (body + headers) de cada motor alimenta o próximo

### Backend binário (`binary_response: true`)

- Pré-transform e pós-transform são ignoradas
- O body do backend é retornado diretamente ao cliente sem bufferização desnecessária
- O tópico Kafka recebe apenas os headers do backend (sem payload)

---

## Observabilidade — Kafka

**Tópico:** `proxy-events`

Evento publicado ao final de cada requisição (independente do resultado):

```json
{
  "http_status_backend": 200,
  "http_status_definitivo": 200,
  "requisicao_bruta":    { "headers": {}, "payload": "..." },
  "requisicao_refinada": { "headers": {}, "payload": "..." },
  "resposta_bruta":      { "headers": {}, "payload": "..." },
  "resposta_refinada":   { "headers": {}, "payload": "..." }
}
```

| Campo | Ausente quando |
|---|---|
| `http_status_backend` | Falha antes de atingir o backend |
| `requisicao_refinada` | Pré-transform não executou |
| `resposta_refinada` | Backend binário ou falha na pré-transform |

---

## mTLS para backends HTTPS

O proxy suporta mTLS dinâmico por host. As keystores de cliente devem estar no diretório configurado em `proxy.tls.keystore-dir`, no formato PKCS12, nomeadas como `{hostname}.p12`.

Se não existir keystore específica para o host, o arquivo `keystore.p12` do mesmo diretório é usado como fallback.

---

## Resposta de erro padrão (502)

```json
{"error":[{"code":"502","message":"Não foi possível completar a operação"}]}
```

`Content-Type: application/json`

Retornado quando: Redis indisponível, falha no `KeystoreProvider`, ou retries TCP esgotados.

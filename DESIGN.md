# Design — HTTP/HTTPS Proxy

## Visão Geral

Proxy HTTP/HTTPS para APIs REST, implementado como microsserviço containerizado no OpenShift, usando Java 21 e Apache Camel. Atua como intermediário entre um gateway de API distribuído e backends dinâmicos, com suporte a transformações configuráveis, mTLS, observabilidade via Kafka e roteamento dinâmico por configuração Redis.

---

## Stack e Componentes

| Componente | Papel |
|---|---|
| `camel-netty-http` | Consumer (servidor HTTP e HTTPS, TLS bridging) |
| `camel-http` | Producer para serviços de transformação e backend não binário |
| `camel-direct` | Encadeamento interno entre etapas do pipeline |
| `quarkus-redis-client` | Busca de configuração do pipeline |
| `camel-kafka` | Publicação de observabilidade (fire-and-forget) |
| Java 21 | Runtime |
| OpenShift | Plataforma de deploy (containerizado) |

---

## Rotas de Entrada Netty

Duas rotas distintas usando o protocolo `proxy` do componente `camel-netty-http`, ambas convergindo para o mesmo pipeline interno:

- **Rota HTTP** — `netty-http:proxy://0.0.0.0:{porta}?ssl=false`
- **Rota HTTPS** — `netty-http:proxy://0.0.0.0:{porta}?ssl=true&keyStoreFile=...`

O uso do protocolo `proxy` ativa o modo `isHttpProxy()` no Netty, que:
- Mantém o body da requisição como `ByteBuf` (sem cópia antecipada para `byte[]`)
- Não explode query parameters como headers individuais
- Não interpreta POST com `application/x-www-form-urlencoded`

O TLS é controlado exclusivamente pelo parâmetro `ssl=true|false`, independente do scheme da URI.

O gateway abre uma nova sessão TLS com o proxy (TLS bridging / re-encryption), garantindo confidencialidade dos dados em trânsito dentro do gateway. Não há tunneling (método CONNECT).

---

## Pipeline Principal

```
Netty HTTP  ─┐
              ├→ DIRECT: config
Netty HTTPS ─┘   → DIRECT: pré-transform  (recursiva)
                  → DIRECT: backend
                  → DIRECT: pós-transform  (recursiva, condicional)
                  → Netty (resposta)
                  → onCompletion → Kafka (fire-and-forget)
```

O DSL principal é **linear e sem condicionais**. Cada etapa decide internamente se há trabalho a fazer com base no estado da Exchange. O pipeline é executado no pool de threads do Camel, desacoplado das threads de I/O do Netty, evitando bloqueio do consumer durante `Thread.sleep` nos intervalos de retry.

---

## Headers de Entrada

| Header | Obrigatório | Conteúdo |
|---|---|---|
| `X-Resource-Id` | Sim | UUID que identifica o recurso no Redis |
| `x-proxy-backend-host` | Não | URL base do backend no formato `scheme://host[:port]`; tem prioridade sobre `Host` quando presente |
| `Host` | Sim (fallback) | Domínio e porta opcional do backend; usado quando `x-proxy-backend-host` está ausente |

### Determinação do scheme e host do backend

| Situação | Scheme | Host |
|---|---|---|
| `x-proxy-backend-host` presente | Extraído do header (`scheme://host[:port]`) | Extraído do header |
| `x-proxy-backend-host` ausente | Herdado da entrada do proxy (HTTP ou HTTPS) | Header `Host` |

O path e a query string são sempre extraídos do request line da requisição recebida pelo Netty.

---

## Modelo Redis

### Chave do recurso
**Formato:** `integracao:recurso:{uuid}`
**Tipo:** Hash

| Atributo | Tipo | Descrição |
|---|---|---|
| `retry_count` | inteiro | Número máximo de tentativas por serviço |
| `retry_interval_ms` | inteiro | Intervalo entre tentativas em milissegundos |
| `binary_response` | booleano | Indica se a resposta do backend é binária/stream |
| `connect_timeout_ms` | inteiro | Timeout de conexão ao backend |
| `read_timeout_ms` | inteiro | Timeout de leitura do backend |
| `motores_requisicao` | lista de UUIDs | UUIDs ordenados dos serviços de pré-transformação |
| `motores_resposta` | lista de UUIDs | UUIDs ordenados dos serviços de pós-transformação |

### Chave de cada serviço de transformação
**Formato:** `integracao:motor:{uuid}`
**Tipo:** Hash

| Atributo | Tipo | Descrição |
|---|---|---|
| `url` | string | URL do serviço de transformação |
| `connect_timeout_ms` | inteiro | Timeout de conexão ao serviço |
| `read_timeout_ms` | inteiro | Timeout de leitura do serviço |

### Formato das listas de motores

Os campos `motores_requisicao` e `motores_resposta` são armazenados como JSON array dentro do Hash:
```
HSET integracao:recurso:{uuid} motores_requisicao '["uuid1","uuid2"]'
```

---

## Origem dos Dados na Exchange

| Dado | Origem |
|---|---|
| URL do backend | `x-proxy-backend-host` (prioritário) ou `Host` + request line |
| Scheme do backend | `x-proxy-backend-host` (se presente) ou scheme da entrada do proxy |
| `X-Resource-Id` | Header da requisição |
| `retry_count`, `retry_interval_ms` | Redis — chave do recurso |
| `binary_response` | Redis — chave do recurso |
| `connect_timeout_ms`, `read_timeout_ms` (backend) | Redis — chave do recurso |
| `motores_requisicao`, `motores_resposta` | Redis — chave do recurso |
| `url`, `connect_timeout_ms`, `read_timeout_ms` (serviço) | Redis — chave do serviço |

---

## Comportamento por Etapa

### DIRECT: config
- Extrai UUID do header `X-Resource-Id`
- Captura e preserva na Exchange o payload bruto da requisição recebida pelo Netty (`requisicao_bruta`)
- Consome integralmente a chave do recurso no Redis e popula a Exchange
- Para cada UUID em `motores_requisicao` e `motores_resposta`, busca a chave `integracao:motor:{uuid}` e carrega os dados na Exchange
- **Redis indisponível** → 502
- **Chave ausente** → Exchange sem listas de transformação e sem retry; pipeline segue normalmente para o backend

### DIRECT: pré-transform (recursiva)
- Se lista `motores_requisicao` vazia, encerra imediatamente
- Lê o estado atual da Exchange via objeto `RecursionState` (ver seção dedicada)
- Invoca o serviço corrente via `camel-http` **sempre com POST**
- Regras de corpo e headers na invocação:
  - Corpo vazio **e** ausência de `Content-Type` → invoca com `Content-Type: application/json` e `Content-Length: 0`
  - Caso contrário → encaminha corpo e headers como estão
- Encadeamento: após sucesso de cada motor, os headers da resposta são propagados para o motor seguinte
- Se integralmente bem-sucedida, o resultado final é preservado como `requisicao_refinada`
- Transições de estado:

| Situação | Próxima transição |
|---|---|
| Sucesso 2xx | Atualiza `Message` (body + headers), avança para próximo serviço, zera contador |
| Falha TCP (tentativas restantes) | Incrementa contador, aguarda `retry_interval_ms`, re-invoca |
| Falha TCP (tentativas esgotadas) | Encerra; erro vira `resposta_bruta`, retorna 502 ao Netty |
| HTTP 4xx/5xx | Encerra; resposta original vira `resposta_bruta`, repassada ao Netty |

### DIRECT: backend
- Resolve URL do backend a partir de `x-proxy-backend-host` ou `Host` + request line
- Monta `SSLContext` via `KeystoreProvider` para qualquer conexão HTTPS
- Transições de retry idênticas à pré-transform (retry sobre si mesmo)
- **`binary_response: false`** → `camel-http`; consome resposta em memória; resultado preservado como `resposta_bruta`
- **`binary_response: true`** → Apache HttpClient direto (bufferizado); captura apenas os headers da resposta como `resposta_bruta` (sem payload); body binário retornado diretamente ao Netty

### DIRECT: pós-transform (recursiva)
- **Ignorada** se `binary_response: true`
- Comportamento e regras de invocação idênticos à pré-transform, iterando sobre `motores_resposta`
- Se integralmente bem-sucedida, resultado final preservado como `resposta_refinada`
- Se falhar: `resposta_bruta` do backend é preservada; erro vira `resposta_refinada`

---

## Máquina de Estados da Recursão

Cada etapa recursiva carrega na Exchange um objeto dedicado `RecursionState`, instanciado separadamente para pré-transform e pós-transform:

```
RecursionState {
    List<String> pendingServices   // UUIDs ainda não processados (ordenados)
    String currentServiceId        // UUID do serviço corrente
    int currentAttempt             // tentativa atual do serviço corrente
}
```

**Regras de transição:**
- **Sucesso:** remove `currentServiceId` de `pendingServices`, zera `currentAttempt`, avança para o próximo
- **Falha TCP (tentativas restantes):** incrementa `currentAttempt`, aguarda `retry_interval_ms` via `Thread.sleep`, re-invoca a mesma rota DIRECT
- **Falha TCP (tentativas esgotadas):** encerra o fluxo com 502
- **HTTP 4xx/5xx:** encerra o fluxo repassando a resposta ao Netty

O retry é uma **transição da própria recursão**, não um mecanismo separado. O contador reseta ao avançar para o próximo serviço.

---

## KeystoreProvider

```java
public interface KeystoreProvider {
    InputStream getKeystore(String host);
}
```

| Atributo | Valor |
|---|---|
| Formato da keystore | PKCS12 |
| Cache | Sem cache (por enquanto) |
| Falha | 502 Bad Gateway |
| Implementação disponível | `FileKeystoreProvider` — arquivo em volume |
| Truststore | Montado via volume, visível ao JVM default |

### FileKeystoreProvider — lógica de resolução

```
{proxy.tls.keystore-dir}/{host}.p12   → existe?  usa
{proxy.tls.keystore-dir}/keystore.p12 → fallback sempre
```

Se nenhum dos dois existir, lança `KeystoreLoadException` → 502.

---

## TLS Dinâmico por Host (camel-http)

O `camel-http` usa Apache HttpClient 5.x internamente. Para suportar mTLS dinâmico por host com pool de conexões único, é implementada uma classe customizada `DynamicTlsSocketStrategy` que implementa a interface `TlsSocketStrategy` do HttpClient 5.x.

O método `upgrade(Socket socket, String target, int port, Object attachment, HttpContext context)` recebe o host de destino via parâmetro `target`, consultando o `KeystoreProvider` neste momento para montar o `SSLContext` adequado antes do handshake TLS.

A configuração do pool fica:

```
PoolingHttpClientConnectionManagerBuilder
    .create()
    .setTlsSocketStrategy(DynamicTlsSocketStrategy)
    .build()
```

---

## Pool de Conexões

| Producer | Estratégia | Configuração |
|---|---|---|
| `camel-http` (transformações e backend não binário) | `PoolingHttpClientConnectionManager` por host | `maxTotal` e `defaultMaxPerRoute` via properties da aplicação |
| Apache HttpClient direto (backend binário) | Pool compartilhado com camel-http | Mesmo `PoolingHttpClientConnectionManager` |

---

## TLS — Resumo

| Trecho | Comportamento |
|---|---|
| Gateway → Proxy (HTTP) | Sem TLS |
| Gateway → Proxy (HTTPS) | TLS bridging; keystore do proxy via Secret OpenShift mapeado como volume |
| Proxy → Backend (HTTP) | Sem TLS |
| Proxy → Backend (HTTPS) | TLS com suporte a mTLS via `KeystoreProvider` + `DynamicTlsSocketStrategy` |

---

## Comportamento de Erro — Resumo

| Tipo de falha | Resposta ao Netty |
|---|---|
| Redis indisponível | 502 — JSON de erro |
| `KeystoreProvider` falha | 502 — JSON de erro |
| Falha TCP / handshake (retries esgotados) | 502 — JSON de erro |
| HTTP 4xx/5xx de qualquer serviço | Repassa a resposta original integralmente |

### Corpo da resposta 502

```json
{"error":[{"code":"502","message":"Não foi possível completar a operação"}]}
```

`Content-Type: application/json`

---

## Observabilidade — Kafka

**Disparo:** `onCompletion` do Camel, independente do resultado final.
**Modo:** fire-and-forget assíncrono (`ProducerTemplate.asyncSendBody`), tópico único: `proxy-events`.
**Falha no envio:** registrada em log `ERROR` com metadados da requisição; sem retry; sem impacto no pipeline.

### Payload JSON

```json
{
  "http_status_backend": 200,
  "http_status_definitivo": 200,
  "requisicao_bruta": {
    "headers": { "Header-Nome": "valor" },
    "payload": "texto plano"
  },
  "requisicao_refinada": {
    "headers": { "Header-Nome": "valor" },
    "payload": "texto plano"
  },
  "resposta_bruta": {
    "headers": { "Header-Nome": "valor" },
    "payload": "texto plano"
  },
  "resposta_refinada": {
    "headers": { "Header-Nome": "valor" },
    "payload": "texto plano"
  }
}
```

### Regras de presença dos campos

| Campo | Ausente quando |
|---|---|
| `http_status_backend` | Erro antes de atingir o backend (Redis, KeystoreProvider, pré-transform) |
| `http_status_definitivo` | Nunca |
| `requisicao_bruta` | Nunca |
| `requisicao_refinada` | Pré-transform falhou ou chave Redis ausente |
| `resposta_bruta` | Nunca (`binary_response: true` → somente headers, sem payload) |
| `resposta_refinada` | `binary_response: true` ou falha na pré-transform |

### Acumulação progressiva

Os campos são preenchidos na Exchange à medida que o pipeline avança:

| Momento | Campos disponíveis |
|---|---|
| Entrada no Netty | `requisicao_bruta` |
| Após pré-transform bem-sucedida | + `requisicao_refinada` |
| Após backend | + `http_status_backend`, + `resposta_bruta` |
| Após pós-transform bem-sucedida | + `resposta_refinada` |
| onCompletion | `http_status_definitivo` + todos os campos acumulados |

### Modelo de resposta em caso de erro

| Etapa onde o fluxo para | `resposta_bruta` | `resposta_refinada` |
|---|---|---|
| config, pré-transform ou backend | Erro (HTTP ou 502) | Ausente |
| pós-transform | Resposta do backend preservada | Erro (HTTP ou 502) |
| Sucesso (não binário) | Resposta do backend | Resposta transformada |
| Sucesso (binário) | Somente headers do backend | Ausente |

### Encoding
Todos os payloads tratados como texto plano. Payload binário do backend nunca incluído no JSON.

---

## Decisões Pendentes / Extensões Futuras

| Tema | Situação |
|---|---|
| Cache do `KeystoreProvider` | Fora do escopo inicial; interface já preparada para implementação futura |
| Implementação de `KeystoreProvider` via serviço HTTPS externo | A implementar |
| Autenticação/autorização do consumidor | Será tratada como serviço de transformação na pré-transform |
| Limite máximo do `retry_interval_ms` | Pode ser adicionado via properties como proteção adicional |
| Streaming real para backend binário | Implementação atual bufferiza o body; streaming sem buffer é melhoria futura |
| Timeouts por motor via URI | Parâmetros `httpClient.connectionRequestTimeout` e `httpClient.responseTimeout` — validação em runtime pendente |

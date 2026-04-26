# Convenções de Codificação — quarkus-camel-proxy

## Stack

| Componente | Versão |
|---|---|
| Java | 21 |
| Quarkus | Red Hat `3.27.3.redhat-00003` |
| BOM principal | `com.redhat.quarkus.platform:quarkus-bom` |
| BOM Camel | `com.redhat.quarkus.platform:quarkus-camel-bom` |
| Pacote raiz | `br.dev.claudiney.proxy` |

## Qualificador `final`

Usar `final` em **todos** os parâmetros de métodos e em **todas** as declarações de variáveis locais para objetos, salvo quando a semântica exige reassignment.

```java
// Correto
public void process(final Exchange exchange) throws Exception {
    final ResourceConfig config = exchange.getProperty(ExchangeKeys.RESOURCE_CONFIG, ResourceConfig.class);
    final String backendUrl = resolveBackendUrl(exchange);
}

// Exceções aceitas — documentar com comentário inline
String currentBody = bruta.payload();      // acumulador de loop — não pode ser final
Throwable cause = e;                       // travessia da cadeia — não pode ser final
```

Exceções recorrentes no projeto:

| Variável | Motivo |
|---|---|
| `currentBody` nos processors de transform | Acumulador reatribuído a cada iteração do loop de motores |
| `cause` em `isTcpError()` | Travessia da cadeia de causas via `cause = cause.getCause()` |

Quando um bloco `if/else` forçaria reassignment, refatorar com variável intermediária:

```java
// Evitar
MessageSnapshot reqToSend = exchange.getProperty(REFINADA, MessageSnapshot.class);
if (reqToSend == null) reqToSend = exchange.getProperty(BRUTA, MessageSnapshot.class);

// Preferir
final MessageSnapshot refinada = exchange.getProperty(REFINADA, MessageSnapshot.class);
final MessageSnapshot reqToSend = refinada != null ? refinada
        : exchange.getProperty(BRUTA, MessageSnapshot.class);
```

## `getMessage()` em vez de `getIn()`

Usar sempre `exchange.getMessage()` para acessar a mensagem corrente da Exchange. Nunca usar `exchange.getIn()`.

```java
// Correto
final Message msg = exchange.getMessage();
exchange.getMessage().setBody(responseBody);
exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);

// Evitar
exchange.getIn().setBody(...);   // getIn() está sendo descontinuado no Camel 4.x
```

`getMessage()` retorna a mensagem corrente (`getOut()` se existir, caso contrário `getIn()`), garantindo comportamento correto independente do padrão de troca InOut/InOnly. No projeto, como nenhum processor usa `setOut()`, `getMessage()` e `getIn()` são equivalentes — mas `getMessage()` é o padrão recomendado pelo Camel 4.x.

## Comentários

Não adicionar comentários que descrevem **o que** o código faz — nomes bem escolhidos já fazem isso.
Comentar apenas **por que**: restrições ocultas, invariantes sutis, workarounds para bugs específicos ou exceções ao padrão `final` (como exemplificado acima).

## Redis — formato das listas

Os campos `motores_requisicao` e `motores_resposta` são armazenados como JSON array dentro do Hash Redis:
```
HSET integracao:recurso:{uuid} motores_requisicao '["uuid1","uuid2"]'
```

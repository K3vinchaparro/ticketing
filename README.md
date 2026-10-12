# Plataforma de Procesamiento de Eventos de Ticketing

## 1. Descripción de la solución

Backend reactivo para vender entradas de eventos con alta concurrencia. La API recibe la compra, la encola y responde de inmediato; un consumidor asíncrono reserva las entradas con escrituras condicionales en DynamoDB, de modo que **nunca se venden más entradas de las que existen**, aunque miles de personas compren a la vez.

- **Stack:** Java 25, Spring Boot 4.1 (WebFlux), DynamoDB, SQS, Docker.
- **Arquitectura:** Clean Architecture (scaffold de Bancolombia), dirigida por eventos.
- **Calidad:** 292 tests y más del 98 % de cobertura de líneas; el build falla si un módulo baja del 90 %.

Flujo de una compra:

1. `POST /orders` guarda la orden como `PENDING`, la envía a la cola y responde `202` con el identificador. Si el evento ya no tiene entradas suficientes, responde `409` de una vez, sin encolar.
2. El consumidor toma el mensaje y, en una sola transacción, reserva las entradas si hay disponibles (`RESERVED`) o rechaza la orden (`REJECTED`).
3. El cliente consulta el estado con `GET /orders/{id}` y confirma el pago con `POST /orders/{id}/confirm` (`SOLD`).
4. Si no confirma en 10 minutos, un proceso periódico libera la reserva y las entradas vuelven al inventario.

## 2. Instalación y configuración

**Requisito único:** Docker con Docker Compose. No hace falta tener Java ni cuenta en ningún servicio.

Para ejecutar los tests o correr la aplicación fuera de Docker se necesita además JDK 25.

**Configuración:** no hay que configurar nada. Todos los valores vienen por defecto en `docker-compose.yml`.

### Tests

```bash
./gradlew build                 # compila, corre los tests y verifica la cobertura
./gradlew jacocoMergedReport    # informe unificado en build/reports/jacocoMergedReport/html
```

### Ejecutarlo sin Docker (desarrollo)

Levanta solo la infraestructura y corre la app desde Gradle:

```bash
docker compose up dynamodb dynamodb-init elasticmq
ADMIN_API_KEY=local-admin-key AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local ./gradlew bootRun
```

## 3. Levantar la aplicación con Docker

```bash
docker compose up --build
```

Esto construye la aplicación y levanta todo lo necesario:

| Servicio | Puerto | Qué es |
|---|---|---|
| `app` | 8080 | La aplicación (API + consumidor + tareas programadas) |
| `dynamodb` | 8000 | DynamoDB Local (imagen oficial de AWS) |
| `elasticmq` | 9324 | Cola compatible con la API de SQS |
| `dynamodb-init` | — | Crea las tablas y termina (queda en estado `Exited (0)`, es lo esperado) |

Cuando el log muestre `Started MainApplication`, la API está en `http://localhost:8080`.

**Demostración completa en un comando** (crea un evento de 10 entradas y lanza 100 compras simultáneas):

```bash
./scripts/demo.sh
```

**Para ver expirar las reservas sin esperar 10 minutos:**

```bash
RESERVATION_TTL=30s docker compose up --build
```

**Para detener todo** (agregar `-v` borra también los datos):

```bash
docker compose down
```

## 4. Decisiones arquitectónicas

### Capas (Clean Architecture)

```
domain/
  model/      Entidades, máquina de estados, excepciones y contratos (gateways). Java puro.
  usecase/    Lógica de negocio. Solo depende de model.
infrastructure/
  entry-points/
    reactive-web/   Rutas, handlers, DTOs, validación, filtros y manejo de errores
    sqs-listener/   Consumidor de la cola
    scheduler/      Tareas periódicas
  driven-adapters/
    dynamo-db/          Entidades de cada tabla, repositorios con transacciones y escrituras condicionales
    sqs-sender/         Publicador de órdenes
    payment-simulator/  Pasarela de pagos simulada
applications/app-service/   Arranque, configuración y unión de todas las piezas
```

El dominio no conoce Spring, AWS ni JSON. La infraestructura depende del dominio, nunca al revés; el build falla si alguien lo viola.

### Decisiones

| Decisión | Por qué | Costo asumido |
|---|---|---|
| **Escritura condicional dentro de una transacción de DynamoDB** | La condición `available >= cantidad` la evalúa la base de datos junto con la escritura. Dos compras simultáneas por la última entrada no pueden ganar las dos | Todas las compras de un evento escriben el mismo registro |
| **Una transacción por transición** (orden + inventario + auditoría) | O se aplican los tres cambios o ninguno. La auditoría nunca contradice a los datos | Las transacciones cuestan el doble de capacidad de escritura |
| **Procesamiento asíncrono con cola** | La API responde en milisegundos y la cola amortigua el pico de demanda | Consistencia eventual: el cliente consulta el estado de su orden |
| **Entrega al menos una vez + consumidor idempotente** | El mensaje solo se borra tras procesarlo bien; si falla, reaparece, y al tercer fallo pasa a una cola de errores. Si llega repetido, la condición `status = PENDING` hace que el segundo no tenga efecto | Hay que diseñar todo para tolerar duplicados |
| **Rechazo rápido cuando no hay entradas** | Si al recibir la compra el evento ya no tiene suficientes disponibles, la API responde `409` de inmediato y no encola nada. Es un atajo para no gastar cola ni base de datos en un evento agotado; la garantía sigue siendo la escritura condicional del consumidor | Una compra puede pasar el atajo y ser rechazada después por el consumidor |
| **El mensaje solo lleva el `orderId`** | El consumidor lee el estado actual en la base de datos, así que un mensaje viejo o repetido no puede sobrescribir nada | Una lectura extra por mensaje |
| **`Idempotency-Key` por cliente** | Un reintento devuelve la orden original en lugar de crear otra. Reutilizar la llave con datos distintos responde `422` | Una tabla adicional, con borrado automático a las 24 horas |
| **Liberación de reservas por proceso periódico** | Cada 30 segundos consulta un índice que solo contiene órdenes con plazo. Si una ejecución falla, la siguiente recoge lo pendiente | Una reserva puede tardar hasta 30 segundos más en liberarse |
| **Reintentos con espera creciente y aleatoria** | Los errores pasajeros de la base de datos se resuelven solos; los de negocio (sin entradas) no se reintentan | Algo más de latencia bajo contención |
| **Un solo sondeo de disponibilidad por evento** | Todos los clientes conectados al *stream* de un evento comparten la misma consulta: el costo no crece con los espectadores | El sondeo es por instancia de la aplicación |
| **Secretos fuera del código** | Ninguna llave en el repositorio. La llave administrativa llega por variable de entorno y la aplicación no arranca sin ella | Hay que configurarla en cada entorno |

## 5. Ejemplos de uso

Base: `http://localhost:8080/api/v1`.

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `POST` | `/events` 🔒 | Crear evento | `201`, `400`, `401` |
| `GET` | `/events` | Listar eventos | `200` |
| `GET` | `/events/{id}` | Detalle del evento | `200`, `404` |
| `GET` | `/events/{id}/availability` | Disponibilidad en tiempo real | `200`, `404` |
| `GET` | `/events/{id}/availability/stream` | Disponibilidad en vivo (Server-Sent Events) | `200`, `404` |
| `POST` | `/events/{id}/complimentary` 🔒 | Asignar cortesías | `200`, `400`, `401`, `404`, `409` |
| `POST` | `/orders` | Crear orden (requiere `Idempotency-Key`) | `202`, `200`, `400`, `404`, `409`, `422`, `429` |
| `GET` | `/orders/{id}` | Estado de la orden | `200`, `404` |
| `POST` | `/orders/{id}/confirm` | Confirmar y pagar | `200`, `402`, `404`, `409`, `410` |

🔒 Requiere el encabezado `X-Api-Key`.

```bash
BASE=http://localhost:8080/api/v1

# Crear un evento
curl -X POST $BASE/events \
  -H 'Content-Type: application/json' -H 'X-Api-Key: local-admin-key' \
  -d '{"name":"Rock Fest","date":"2099-01-01T20:00:00Z","venue":"Movistar Arena","capacity":100}'

# Comprar 2 entradas → 202 con el orderId
curl -i -X POST $BASE/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: compra-0001' \
  -d '{"eventId":"<eventId>","customerId":"cliente-1","quantity":2}'

# Consultar la orden
curl $BASE/orders/<orderId>

# Confirmar la compra
curl -X POST $BASE/orders/<orderId>/confirm

# Disponibilidad, y en vivo
curl $BASE/events/<eventId>/availability
curl -N $BASE/events/<eventId>/availability/stream
```

Respuesta de una orden:

```json
{
  "orderId": "4a9a2888-7595-4245-8898-022dbbe19e00",
  "eventId": "95f54fbf-5e16-473c-95d2-a97227015489",
  "customerId": "cliente-1",
  "quantity": 2,
  "orderStatus": "RESERVED",
  "ticketStatus": "RESERVED",
  "expiresAt": "2026-10-09T16:21:08Z",
  "createdAt": "2026-10-09T16:11:08.592Z",
  "updatedAt": "2026-10-09T16:11:08.773Z"
}
```

Respuesta de error:

```json
{
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Idempotency key compra-0001 was already used with a different request",
  "instance": "/api/v1/orders"
}
```

### Colección de Postman

`postman/Ticketing Platform.postman_collection.json` contiene 9 peticiones, una por cada endpoint, en dos carpetas:

- **Events:** crear, listar y consultar un evento, ver su disponibilidad (normal y en vivo) y asignar cortesías.
- **Purchase flow:** crear una orden, consultar su estado y confirmarla.

Guardan solas el `eventId` y el `orderId` en variables, y generan un `Idempotency-Key` nuevo en cada compra, así que basta con ejecutarlas en orden. La llave administrativa ya viene configurada con el valor local.

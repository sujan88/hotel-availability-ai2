# Hotel availability — Spring AI 2.0.1 native tool limits

Java 17+, Maven 3.9+, Spring Boot 4.1.1, Spring AI 2.0.1 and Resilience4j 2.4.0 (Boot 4 starter).

This project uses Spring AI's native tool loop and native total/per-tool call limits. There are no application budget classes, custom tool-manager decorators, duplicate-call guards, model-call counters, token/cost budgets, or overall chat deadline. The original Spring AI 1.1 project is a separate copy.

## Run locally without Docker

Open two terminals in this project folder (the folder containing `pom.xml`). Python 3 is needed only for the local mock; no Python packages are required.

### Terminal 1: start the hotel API on port 9090

```bash
python3 mock/hotel_api.py --mode success
```

Leave this terminal running. The mock accepts:

```text
GET /hotels/h1/availability?checkIn=2027-01-01&checkOut=2027-01-02&guests=2
```

It returns HTTP 200 with `Content-Type: application/json`:

```json
[
  {
    "roomId": "r1",
    "roomType": "King",
    "price": 120.0,
    "currency": "USD"
  }
]
```

The upstream contract is a room array; `[]` means a successful search with no rooms. Prices and currency are provided by the hotel API. This project does not reserve rooms or process payment.

### Terminal 2: start Spring Boot on port 8080

macOS/Linux:

```bash
export HOTEL_API_BASE_URL='http://localhost:9090'
export OPENAI_API_KEY='your-key'
mvn spring-boot:run
```

Windows PowerShell:

```powershell
$env:HOTEL_API_BASE_URL = 'http://localhost:9090'
$env:OPENAI_API_KEY = 'your-key'
mvn spring-boot:run
```

`OPENAI_MODEL` defaults to `gpt-4o-mini`. A live chat request needs a valid API key. Automated tests use simulated models and do not call OpenAI.

The earlier project also uses port 8080. Run one copy at a time, or use `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` and send chat requests to port 8081.

## Test success in Postman

First send **GET**, with no body:

```text
http://localhost:9090/hotels/h1/availability?checkIn=2027-01-01&checkOut=2027-01-02&guests=2
```

Expect HTTP 200 with the room array. This checks the mock directly and does not exercise Spring AI or Java retries.

Then send **POST**:

```text
http://localhost:8080/api/chat
```

Set `Content-Type: application/json`. Select **Body → raw → JSON**:

```json
{
  "message": "Search availability at hotel h1 from 2027-01-01 to 2027-01-02 for 2 guests."
}
```

Expected response (assistant wording varies):

```json
{
  "success": true,
  "message": "A King room is available for USD 120.",
  "errorCode": null
}
```

Each HTTP chat request is stateless. Include the hotel ID, dates and guest count each time. The model decides whether to request a tool. `success` here means the chat completed; the assistant may also successfully explain that a hotel API search failed. The internal tool response retains its separate `success`, `rooms`, `errorCode`, `message` and `retryable` fields.

## Configure native Spring AI limits

Edit the existing `spring.ai.tools.limits` section in `src/main/resources/application.yml`:

```yaml
spring:
  ai:
    tools:
      limits:
        max-total-tool-calls: 3
        max-calls-per-tool-default: 2
        on-limit-exceeded: THROW
```

- `max-total-tool-calls`: maximum individual tool invocations across all tools during a user turn.
- `max-calls-per-tool-default`: maximum invocations of each tool by name, whether arguments are the same or different.
- `on-limit-exceeded: THROW`: stops the native loop at the breached limit. The application maps Spring AI's native limit finish reason to a failed chat response.

Spring AI reconstructs counts from the current turn's conversation history. A new HTTP chat request starts a fresh turn and resets counts. No application counter tracks them. With one exposed tool, the stricter cap applies: the default configuration allows at most two calls to `searchAvailability`.

To override one tool, add a native per-tool setting:

```yaml
spring:
  ai:
    tools:
      limits:
        max-total-tool-calls: 3
        max-calls-per-tool-default: 2
        max-calls-per-tool:
          searchAvailability: 1
        on-limit-exceeded: THROW
```

Modify the existing YAML section instead of adding a second `spring` key. Restart the app after editing. The old `agent.limits` settings are not used.

Identical searches are allowed until the native count is exhausted; there is no special duplicate detection. Native limits check each invocation during batch execution. Earlier calls in a batch can complete before a later call is rejected; there is no rollback.

Spring AI also offers `RETURN_ERROR_RESPONSE`, which feeds an error result back to the model and continues the conversation. Keep `THROW` for this project's stop-at-limit behavior. Native tool counts do not provide an overall execution deadline or token/cost budget. The OpenAI `timeout: 20s` setting is a transport timeout for an individual model request, not an overall chat deadline; `max-retries: 0` disables SDK retries.

### Check a per-tool limit in Postman

Stop Spring Boot with Ctrl+C, then restart with one allowed call per tool:

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.ai.tools.limits.max-total-tool-calls=3 --spring.ai.tools.limits.max-calls-per-tool-default=1"
```

Send this JSON to the same POST endpoint:

```json
{
  "message": "Search hotel h1 and hotel h2, both from 2027-01-01 to 2027-01-02 for 2 guests. Search each hotel separately."
}
```

If the model requests both searches, the first runs and the second is blocked. The mock accepts both hotel IDs and returns the same example room. Expect a failed chat response:

```json
{
  "success": false,
  "message": "Tool call limit (1) exceeded for tool 'searchAvailability'. No further calls to this tool are allowed in this turn.",
  "errorCode": "TOOL_CALL_LIMIT_EXCEEDED"
}
```

This is HTTP 200 with `success: false`. The message is produced by Spring AI. No additional model request is made to explain the limit. The response has only `success`, `message` and `errorCode`.

### Check a total tool limit in Postman

Restart Spring Boot with:

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.ai.tools.limits.max-total-tool-calls=1 --spring.ai.tools.limits.max-calls-per-tool-default=5"
```

Send the same two-hotel JSON. If the model requests two calls, the second is blocked by the total cap. Expect `errorCode: TOOL_CALL_LIMIT_EXCEEDED` and a message about the total tool-call limit. Model behavior varies, so automated tests are the deterministic check. Restart with the normal `mvn spring-boot:run` command afterward to restore YAML defaults.

## Hotel mock modes

Stop terminal 1 with **Ctrl+C**, then restart with one mode. Keep Spring Boot running while changing mock modes. Only run one mock instance on port 9090 at a time.

| Mode | Command | Behavior |
|---|---|---|
| Success | `python3 mock/hotel_api.py --mode success` | Immediate HTTP 200 with a room array |
| Retry recovery | `python3 mock/hotel_api.py --mode retry` | First two requests return 503, later requests return 200 |
| Persistent failure | `python3 mock/hotel_api.py --mode failure` | Always HTTP 503 |
| Bad request | `python3 mock/hotel_api.py --mode bad-request` | Always HTTP 400 |
| Slow response | `python3 mock/hotel_api.py --mode timeout` | Delay each response by 3 seconds |

For the two main cases:

```bash
python3 mock/hotel_api.py --mode success
```

```bash
python3 mock/hotel_api.py --mode timeout
```

In retry mode, send the chat request once and look for these Spring Boot logs:

```text
Availability retry attempt=1
Availability retry attempt=2
```

That means three total hotel HTTP attempts within **one** Spring AI tool invocation. Restarting the mock resets its attempt counter. Do not call the mock directly before a retry test, because direct requests consume its first two failures too.

Persistent 503 failures eventually map to `TEMPORARILY_UNAVAILABLE`. HTTP 400 maps to `INVALID_REQUEST` without internal retries. These are internal availability tool codes; the chat response contains the assistant's explanation.

In timeout mode, the default 1500ms HTTP read timeout normally fires before the 2-second Resilience4j time limiter. Expect a retried transport failure with internal code `TEMPORARILY_UNAVAILABLE`. To exercise the Resilience4j `TIMEOUT` path, restart Spring Boot with a shorter per-attempt limit:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--resilience4j.timelimiter.instances.availability.timeoutDuration=1s
```

Calling port 9090 directly bypasses Java timeouts: Postman can receive HTTP 200 after 3 seconds. A Postman timeout of 60 seconds gives manual chat tests time for the model calls and hotel retries. Restart Spring Boot normally after temporary overrides.

## Architecture

```text
POST /api/chat
 → ChatService calls ChatClient
 → native ToolCallingAdvisor manages model/tool rounds and history
 → native DefaultToolCallingManager checks total/per-tool counts
 → synchronous HotelTools @Tool
 → AvailabilityService
 → CircuitBreaker [ Retry [ TimeLimiter [ async HTTP task ] ] ]
 → HotelApiClient / RestClient → hotel API
 → structured tool result → model → final chat response
```

`ChatConfig` wires the native advisor to Spring AI's auto-configured manager so YAML limits apply, then registers `HotelTools`. There is no custom advisor, manager wrapper, orchestration executor, or manual loop. `ChatService` only maps the final native response to the HTTP response shape and sanitizes unexpected provider errors as `CHAT_EXECUTION_FAILED`.

| Package | Responsibility |
|---|---|
| `chat` | Endpoint, simple chat service and response |
| `config` | Native ChatClient setup, hotel HTTP client/executor and event hooks |
| `tool` | Synchronous `@Tool` and `@ToolParam` method |
| `dto` | Search parameters, room and availability result |
| `service` | Async hotel resilience and safe failure mapping |
| `client` | Hotel RestClient adapter |
| `exception` | Hotel validation, transient and contract failures |

The hotel resilience layer remains separate from Spring AI's tool-call counting. Explicit aspect orders 1/2/3 make the circuit breaker outermost, so it counts one logical search after retries. The fallback preserves the final cause and AvailabilityService maps it safely. The dedicated availability executor is bounded to 8–16 workers with a queue of 16; HTTP timeouts bound blocking work that may continue after future cancellation.

## Tests and observability

Validated with `mvn clean verify`: **27 tests passed**, with zero failures or errors, and the executable JAR was built. No live OpenAI calls were made.

Run all tests and package the executable JAR:

```bash
mvn clean verify
```

The tests use local simulated HTTP servers and models. No OpenAI key, Docker or separately running mock is needed.

```bash
mvn -Dtest=NativeTotalLimitIntegrationTest test
mvn -Dtest=NativePerToolLimitIntegrationTest test
mvn -Dtest=ChatHttpIntegrationTest test
mvn -Dtest=AvailabilityIntegrationTest#retriesTransientErrorsThenSucceeds test
mvn -Dtest=AvailabilityIntegrationTest#exhaustedRetriesAreOneCircuitFailure test
```

Native configuration tests confirm that the actual auto-configured Spring AI manager reads the properties and resets counts for a new user request. The real OpenAI HTTP adapter test uses a repeating simulated model: two hotel calls execute, and the third requested call is rejected by the native per-tool cap. Other tests cover normal completion, native batch handling, input validation, hotel retries/timeouts/circuit opening, and sanitized failures.

Send **GET** `http://localhost:8080/actuator/health` for app health; expect `{"status":"UP"}`. This does not test the external model or hotel API. `/actuator/prometheus` exposes JVM, HTTP, Resilience4j and `hotel.availability.results` metrics. Spring AI provides native tool observations. Application hooks log circuit transitions and hotel retries without raw chat messages or upstream error bodies.

## Troubleshooting

- **9090 is unreachable:** confirm terminal 1 is running and nothing else occupies the port. Test the direct GET before chat.
- **9090 responds slowly:** timeout mode intentionally delays responses; restart with `--mode success` for an immediate result.
- **Circuit remains open:** wait at least 20 seconds or restart Spring Boot. Restarting only the mock does not reset the Java circuit breaker.
- **8080 is unreachable:** check Spring Boot startup logs, API key configuration and port conflicts.
- **Only one search runs in a two-hotel test:** the model may choose one call. Use native integration tests to verify limits deterministically.
- **Spring Boot runs inside Docker:** localhost refers to that container. This walkthrough assumes both processes run directly on your computer; the Python mock binds to 127.0.0.1.

References: [Spring AI native tool limits](https://docs.spring.io/spring-ai/reference/api/tools.html#_tool_call_limits), [ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html), [Resilience4j Spring Boot setup](https://resilience4j.readme.io/docs/getting-started-3).

# Follow the logs in five places

Start here to learn the logging flow. The application keeps its booking/payment behavior,
Spring AI limits, duplicate-write detection and Resilience4j handling.
The custom tool callback decorator has been removed. Tool registration is now ordinary
Spring AI registration, and every tool method visibly calls `audit.log(...)`.

The console starts every audit line with its uppercase layer, followed by the event name
and a few fields. See the README for an example. Full fields remain in `logs/ai-chat.jsonl`.

## 1. Chat request: ChatService

Open `src/main/java/com/example/hotel/chat/ChatService.java`, method `chat`:

```java
var frame = audit.begin(conversationId);        // user.message.received
try (var scope = AuditContext.open(frame)) {
  ChatResult result = runChat(message, idempotencyKey, paymentMethodToken);
  audit.finish(frame, result);                 // turn.completed
  return result.withAudit(frame);
}
```

One HTTP chat request gets one traceId and turnId. Reuse conversationId to group several
requests. It does not provide chat memory. The context makes these IDs available to logs
without passing them through every synchronous method.

## 2. Model call: AuditModelAdvisor

Open `src/main/java/com/example/hotel/audit/AuditModelAdvisor.java`.
It logs before and after `chain.nextCall(request)`, the actual model invocation.
That gives `model.call.started` and `model.call`, including token usage, requested tools
and latency. It runs inside Spring AI's tool loop: asking for a tool and producing a final
answer are normally two model calls.

`ChatConfig` registers the model advisor once:

```java
.defaultTools(tools)
.defaultAdvisors(toolAdvisor, auditModel)
```

`toolAdvisor` is Spring AI's tool execution loop; `auditModel` only observes model calls.
The booking tool is added by `ChatService` with `prompt.tools(reservationTools)` only when
there is a valid client Idempotency-Key.

## 3. Tool method: HotelTools / ReservationTools

Open `src/main/java/com/example/hotel/tool/HotelTools.java`:

```java
return audit.log("searchAvailability", () -> {
  // Validate arguments, then call the service.
  return service.search(request);
});
```

The real method also includes request construction and validation inside this block.
`createReservation` and `getReservationStatus` use the same visible pattern.

Open `audit/ToolAudit.java` to see the short common implementation:
start the tool event, run the action, read its typed response, and finish the event.
There is no custom ToolCallback wrapper and no JSON parsing to discover the result.

Events: `tool.started`, then `tool.call` with `toolName`, status, error code and latency.
A structured failure response is logged as FAILED even though Java did not throw.
A thrown exception is logged safely and rethrown for the existing chat error handling.

## 4. Service and backend: ResilienceLogging / AuditHttpInterceptor

`integration/AvailabilityService.java` and `integration/ReservationService.java` map final
backend failures into structured responses and update metrics. The `integration/Resilient*Gateway`
classes protect backend HTTP calls. Each integration entry method calls
`AuditStore.serviceCall("ClassName.methodName")` to produce a visible `[SERVICE] service.call` line. The companion Hotel Service owns the booking/payment
business logic; these AI2 classes do not duplicate that logic.
`config/ResilienceLogging.java` prints availability retry and circuit-state transitions
to the normal console logger. It does not implement retries; Resilience4j does.

`audit/AuditHttpInterceptor.java`, registered in `config/HotelApiConfig.java`, logs each
actual HTTP attempt as `downstream.started` and `downstream.call`. It records status,
latency, attemptNo, retryCount and circuitState. A retry produces another HTTP attempt.
It also observes reading the response body so a timeout after HTTP 200 headers is
recorded as a failed attempt. This explains why it is longer than a simple before/after log.

The async gateway passes the audit frame explicitly to its executor task. This keeps the
same traceId and toolName on retries that run on another thread.

## 5. Output: AuditStore and logback-spring.xml

`AuditStore` formats the shared fields and emits the events. That is why JSON entries
have logger_name `com.example.hotel.audit.AuditStore` even when they originated in different
layers. Read `event` and `toolName` to understand the operation.

`src/main/resources/logback-spring.xml` sends a short uppercase-prefixed line to the console and full structured JSON to
`logs/ai-chat.jsonl`. Prompts, guest details, payment tokens and raw payloads are excluded.
Only important reservation-write outcomes also go to the single `reservation_audit` table.
A search writes no audit rows. `/api/audit/events` reads that business table, not the log file.

## Read one search request

POST `http://localhost:8080/api/chat` in Postman, JSON body:

```json
{"message":"Use searchAvailability for hotel h1 from 2027-04-10 to 2027-04-11 for 2 guests."}
```

Copy the response traceId, then run from this project's folder:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID
```

Typical chronological order for one successful search:

```text
user.message.received             ChatService
model.call.started                AuditModelAdvisor
model.call (requests tool)        AuditModelAdvisor
 tool.started                    HotelTools -> ToolAudit
  service.call                   AvailabilityService
  downstream.started             AuditHttpInterceptor
  downstream.call                AuditHttpInterceptor
 tool.call                       HotelTools -> ToolAudit
model.call.started                AuditModelAdvisor
model.call (final answer)         AuditModelAdvisor
turn.completed                   ChatService
```

To see only actual tool executions:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID --event tool.call
```

For booking, follow the README payment sequence. Look for `createReservation` and a
`business.event`; read `reservations[0].reservationId` and check its status afterwards.
Native limits and duplicate checks can reject calls before a tool method is entered.
Those rejections are recorded by `WriteFingerprintingToolCallingManager` through AuditStore.
See [AUDIT.md](AUDIT.md) for field definitions, retention and business-audit API details.

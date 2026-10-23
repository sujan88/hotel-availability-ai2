# Logs first, one small business-audit table

Chat/reservation endpoints require Basic Auth (default) or OAuth2 bearer tokens, selected by
`AI_AUTH_MODE`; see [authentication setup](AUTHENTICATION.md). They select tools from server-authenticated
permissions. See [TOOL-PERMISSIONS.md](TOOL-PERMISSIONS.md) before using the chat examples below.
The audit-read API keeps its separate loopback / optional Bearer-token policy.

Audit and observability live in **standalone AI2**. Operational details go to structured JSON application logs. The database has **one table: `reservation_audit`**, used only for booking-write outcomes observed by AI2.

| Information | Storage |
|---|---|
| Conversation, turn and trace IDs | JSON logs and chat response |
| Each model invocation, model name, prompt version, usage and latency | JSON logs |
| Each tool call, safe result metadata and duplicate rejection | JSON logs |
| Each actual backend HTTP attempt, retry number, circuit state and error | JSON logs |
| Turn completion/failure and aggregate token usage | JSON logs |
| Accepted/confirmed/cancelled reservation reported by a booking write | `reservation_audit` and JSON logs |
| Status reads, native limits and duplicate detection | JSON logs only |
| Chat text, model prompts, tool arguments, guest details, payment tokens and raw idempotency keys | Neither logs nor database |

There are no conversation, turn, model-call, tool-call or downstream-call tables. There is no RAG, so no RAG tables or fabricated retrieval events are added. Payment, user-confirmation, refund and authorization audit tables are not added because AI2 is not the authoritative owner of those operations.

## Request flow

```text
/api/chat -> generated conversationId / turnId / traceId
  -> user.message.received log
  -> model.call.started -> model.call log
  -> tool.started
       -> downstream.started -> downstream.call (attempt 1)
       -> downstream.started -> downstream.call (retry attempt 2, if needed)
       -> tool.call log
       -> reservation_audit INSERT only for a booking-write business outcome
  -> next model.call log
  -> turn.completed log
```

Spring AI still owns its tool loop and native limits. Write fingerprinting, backend idempotency, bounded async executors and Resilience4j remain unchanged. The model advisor runs inside the tool loop, so each invocation is recorded separately. Every retry carries an explicit audit frame through gateway arguments; scoped thread-local/MDC state is restored after each operation. The backend receives `X-Trace-Id` and `X-Tool-Call-Id`.

## Quick test

Start either hotel backend on 9090, then AI2 on 8080. For a retry demonstration, use the Python mock instead of Java Hotel Service:

```bash
# From hotel-availability-ai2, terminal 1:
python3 mock/hotel_api.py --mode retry
```

```bash
# Terminal 2, same AI2 folder:
export OPENAI_API_KEY='your-valid-key'
export HOTEL_API_BASE_URL='http://localhost:9090'
mvn spring-boot:run
```

Live `/api/chat` requires a valid OpenAI key. Automated tests use local fake model/provider HTTP servers and do not call OpenAI.

Import [the Postman collection](../postman/chat-audit.postman_collection.json), or send:

```http
POST http://localhost:8080/api/chat
Content-Type: application/json

{"message":"Search availability at hotel h1 from 2027-01-01 to 2027-01-02 for 2 guests."}
```

The existing response fields remain, with:

```json
{
  "conversationId":"actual-server-generated-UUID",
  "turnId":"actual-server-generated-UUID",
  "traceId":"actual-server-generated-32-character-hex-ID",
  "auditComplete":true
}
```

Copy the real trace ID. Inspect its events in the JSON file:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID
```

Or search directly:

```bash
grep 'COPY_TRACE_ID' logs/ai-chat.jsonl
```

On Windows PowerShell:

```powershell
Select-String -Path logs/ai-chat.jsonl -Pattern 'COPY_TRACE_ID'
```

The helper can read rotated gzip files too:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID \
  --file 'logs/ai-chat.jsonl*'
```

The file contains only the allow-listed `AuditStore` event logger. Ordinary framework/provider logs remain on the console and do not enter this dedicated file. The same event logger writes short text lines beginning with `[CHAT]`, `[MODEL]`, `[TOOL]`, `[SERVICE]`, `[EXTERNAL API]`, `[BUSINESS]` or `[AUDIT]` to the console; JSON fields remain in the file. Do not enable request/body debug logging if you want to keep sensitive payloads out of other logs.

## JSON event fields

Example downstream retry completion:

```json
{
  "@timestamp":"2026-10-22T14:32:10Z",
  "level":"WARN",
  "event":"downstream.call",
  "traceId":"actual-trace-id",
  "conversationId":"actual-conversation-id",
  "turnId":"actual-turn-id",
  "modelCallId":"actual-model-call-id",
  "toolCallId":"actual-tool-call-id",
  "toolName":"searchAvailability",
  "downstreamCallId":"actual-attempt-id",
  "serviceName":"HOTEL_API",
  "operation":"SEARCH_AVAILABILITY",
  "attemptNo":2,
  "retryCount":1,
  "circuitState":"CLOSED",
  "httpStatus":503,
  "status":"FAILED",
  "errorCode":"HTTP_503",
  "latencyMs":32
}
```

Field availability depends on the event. A connection timeout has no HTTP status. A body-read timeout can have HTTP 200 and still be failed with `HTTP_TIMEOUT`.

| Event | Useful fields |
|---|---|
| `user.message.received` | IDs, `messageStored: false` |
| `model.call.started` / `model.call` | modelCallId, modelName, availableTools, requestedTools, promptVersion, reported tokens, finishReason, latency/status |
| `tool.started` / `tool.call` | toolCallId, originating modelCallId, toolName, duplicateDetected, safe reservation ID/status, latency/error |
| `downstream.started` / `downstream.call` | attempt ID, service/operation, attemptNo/retryCount, circuit state, HTTP/error status, latency |
| `audit.event` | operational events such as TOOL_REJECTED, TOOL_LIMIT_EXCEEDED, RESERVATION_STATUS_OBSERVED |
| `business.event` | reservation ID, eventType, auditId, `persisted` flag |
| `turn.completed` | final status/error, toolsCalled, toolCallCount, totalLatencyMs, reported totalTokens, usageReported, auditComplete |

No raw messages, prompts, arguments, response bodies, request URLs, guest names/emails, payment tokens, idempotency keys, API keys or exception messages are included. Sanitized result metadata means opaque reservation IDs and allow-listed status/error codes, not the full result payload. Missing provider usage is not estimated as cost. `hotel-tools-v1` identifies the system prompt version and should change when that prompt changes.

## Conversations without a conversation table

Send `Conversation-Id: ACTUAL_UUID` on the next chat request to reuse an audit grouping ID. The server validates the UUID format; it does not look up a database session or assign a sequence number. A syntactically valid UUID can be reused after restart. Each request still gets a new turn and trace ID.

```bash
python3 scripts/read_trace.py --conversation-id COPY_CONVERSATION_ID
```

This groups logs, not model memory. Prior messages are not automatically sent back to the model. The ID is not authentication, tenant ownership or reservation approval. Production needs trusted user/tenant authorization if these IDs are used across users.

## One database table

`reservation_audit` contains:

```text
audit_id (PK)
trace_id
conversation_id
turn_id
tool_call_id
reservation_id
event_type
created_at (epoch milliseconds)
```

Only `RESERVATION_ACCEPTED`, `RESERVATION_CONFIRMED` and `RESERVATION_CANCELLED` from a **booking-write result** are inserted. Normal chat/search requests perform zero audit SQL operations at runtime. Status reads, model calls, retries, technical errors, duplicate detection and native limit events create no database rows.

These are outcomes reported to AI2, not a complete authoritative booking ledger. A pending write followed by a later status read of CONFIRMED is visible in the logs; the read does not create a database event. Replaying a booking write can create another observed outcome record under a different trace. Backend idempotency still prevents duplicate business effects.

Actual payment capture/refund, user-confirmation decisions, Email/PMS delivery and backend saga transitions belong in the systems that perform them. AI2 does not invent PAYMENT_SUCCESS or USER_CONFIRMED events from model prose. Java Hotel Service is unchanged.

### Read persisted business events

```http
GET http://localhost:8080/api/audit/events?traceId=ACTUAL_TRACE_ID
```

Or use exactly one of:

```http
GET http://localhost:8080/api/audit/events?reservationId=ACTUAL_RESERVATION_ID
GET http://localhost:8080/api/audit/events?conversationId=ACTUAL_CONVERSATION_UUID
```

The response contains `source: DATABASE_BUSINESS_EVENTS`, `events` and `truncated`. At most 200 oldest matching records are returned. A search-only trace correctly returns an empty list. It does not mean the trace is absent from logs.

The obsolete `/api/audit/traces/{id}` and `/api/audit/conversations/{id}` database reconstruction endpoints have been removed and return **404**. Their compatibility handler is no longer part of the code. Use structured logs for operational traces and `/events` only for durable booking outcomes. The Postman collection has been updated accordingly.

The read API remains loopback-only by default. If `AUDIT_READ_TOKEN` is configured, supply `Authorization: Bearer YOUR_AUDIT_READ_TOKEN`. This token is separate from OpenAI/payment credentials. You can disable the read API while retaining logs and business recording. Reverse-proxy deployments need real endpoint authentication and tenant-scoped access; a peer-address check is a local learning safeguard.

## Configuration and retention

| Environment variable / setting | Default | Purpose |
|---|---|---|
| `AUDIT_LOG_FILE` / `audit.log-file` | `logs/ai-chat.jsonl` | Operational JSON event file |
| `AUDIT_LOG_MAX_SIZE` / `audit.log-max-size` | 10MB | File-size rotation |
| `AUDIT_LOG_HISTORY_DAYS` / `audit.log-history-days` | 14 | Archive history window |
| `AUDIT_LOG_TOTAL_SIZE` / `audit.log-total-size` | 200MB | Archive size cap; can shorten retained history |
| `AUDIT_DB_URL` / `spring.datasource.url` | `jdbc:h2:file:./data/business-audit;DB_CLOSE_ON_EXIT=FALSE` | One-table business-audit database |
| `AUDIT_DB_PASSWORD` | empty | Local H2 password |
| `AUDIT_READ_API_ENABLED` | true | Business-event read endpoints |
| `AUDIT_READ_TOKEN` | empty | Optional bearer token |

Paths are relative to the directory from which AI2 starts. `data/` and `logs/` are ignored by Git and excluded from the source ZIP. The active file and rotated archives are local; shipping to CloudWatch/OpenSearch/ELK/Datadog/Splunk requires a separately configured log collector. No external logging service is connected by this project.

Rotation bounds archive storage; it does not guarantee 14 full days under heavy load. There is no automatic database retention job. For production, configure collection, retention, access controls and backups. The application correlation ID is not an OpenTelemetry exporter or a complete W3C tracing implementation.

## Migration and failure behavior

The previous six-table recorder used `data/chat-audit.mv.db`. The new default is **`data/business-audit.mv.db`**. Old files/tables are left untouched; they are not queried or updated by this version. Keep an archive if you want the old history. This change does not drop existing tables or delete audit history. If you explicitly reuse an old `AUDIT_DB_URL`, legacy tables remain present but receive no writes.

Only the one-table schema is created in a fresh database. H2 initialization must succeed when the application starts. Once running, ordinary logging paths do not depend on database availability. A failed business-audit INSERT is logged as `business.event` with `persisted: false` and sets chat `auditComplete: false`; it does not turn an already accepted booking into a failure or cause a new booking write.

`auditComplete` reports observed **business-database write failures**. It is not proof of log delivery, payment success or an exactly-once audit guarantee. Disk/appender/collector failures must be monitored operationally; logging cannot be made atomic with remote booking side effects. A process crash can leave a start event without its completion. A slow HTTP attempt may finish after the chat response; inspect later events before treating a trace as final.

Invalid request bodies rejected before `ChatService` and direct `/api/reservations` calls remain outside the chat trace. Direct APIs still support deterministic testing without a model.

## SQL and log investigation

```sql
SELECT * FROM reservation_audit
WHERE trace_id = 'COPY_TRACE_ID' ORDER BY created_at;

SELECT * FROM reservation_audit
WHERE reservation_id = 'COPY_RESERVATION_ID' ORDER BY created_at;
```

For technical questions, search logs:

```text
Full request:        traceId = ...
Tool calls:          traceId = ... AND event = tool.call
Physical retries:   toolCallId = ... AND event = downstream.call
Model usage:        traceId = ... AND event = model.call
Why it failed:      traceId = ... AND status = FAILED
Incomplete work:    a *.started event without the matching completion ID
```

## Tests and code

```bash
mvn clean verify
mvn -Dtest=AuditFlowIntegrationTest,AuditPersistenceTest test
python3 -m unittest discover -s mock -p 'test_*.py'
```

Tests check actual JSON output, correlation/parent IDs, async retry propagation, response-body timeouts, duplicate/limit handling, concurrent conversations, sensitive-data exclusion, zero SQL for operational tracing, one table in a fresh database, business-event persistence after restart, read access controls and business-audit failure behavior.

`AuditStore` emits safe structured events and owns the single INSERT/query. The model advisor, explicit `ToolAudit.log(...)` calls inside each tool method, HTTP interceptor and scoped audit context are the integration hooks. The custom callback decorator has been removed; start with [the logging walkthrough](LOGGING-WALKTHROUGH.md). `logback-spring.xml` routes only safe audit events into a rotating JSON file. The helper `scripts/read_trace.py` reads local JSON/gzip files; it does not create another database or cache.

Reference: [Spring Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html).

## Find the tool name quickly

`user.message.received` runs before the model selects a tool, so it intentionally has no `toolName`. The later events show the stages separately:

```json
{"event":"model.call","availableTools":["searchAvailability","getReservationStatus"],"requestedTools":["searchAvailability"],"toolRequestsCount":1}
{"event":"tool.call","toolName":"searchAvailability","status":"COMPLETED"}
{"event":"downstream.call","toolName":"searchAvailability","attemptNo":1,"httpStatus":200}
{"event":"turn.completed","toolsCalled":["searchAvailability"],"toolCallCount":1}
```

These compact examples omit IDs/timing; actual events include their correlation fields. `toolsCalled` contains distinct names of tool methods actually entered. `toolCallCount` counts invocations, including failed tools; preflight-rejected writes are not counted as invoked. A model can request a tool that is prevented by a limit or duplicate check, so `requestedTools` is not proof of execution. Unrecognized model-requested names are recorded as `UNKNOWN_TOOL` rather than copying arbitrary model text into logs.

From AI2, show only tool execution events for one trace:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID --event tool.call
```

Or inspect the model selection and final summary:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID   --event model.call --event turn.completed
```

Troubleshooting a booking that only searches or asks for confirmation:

- `availableTools` has no `createReservation`: verify both RESERVATION_CREATE permission and a valid `Idempotency-Key`. Booking tools are conditional; read tools are always available.
- `requestedTools: []` and `toolsCalled: []`: the model returned text without calling a tool. No tool name should be invented for that request.
- `toolsCalled` contains only `searchAvailability`: availability ran, but booking/payment validation was not reached.
- A requested write was rejected: check `tool.call` status/error and `audit.event` for duplicate or limit handling.
- Checking only `user.message.received` or `turn.completed` from an older build can hide this detail. Update/restart AI2 and inspect all events with the returned trace ID.

These extra fields are per-request log metadata; the database remains the same single business-audit table.

### JSON logging fix

The event emitter merges its fields into one map and temporarily excludes MDC while encoding that specific event, then restores the prior MDC. Spring Boot's JSON encoder otherwise receives duplicate correlation keys from both MDC and explicit event fields and can reject the line. This previously allowed `user.message.received` to appear while later scoped tool/HTTP events were missing. The regression test checks the actual JSON file for scoped tool, HTTP and turn-completion events.

After updating, restart AI2 and send a **new** request. Missing lines from an older run cannot be reconstructed retroactively from the one-table business database.

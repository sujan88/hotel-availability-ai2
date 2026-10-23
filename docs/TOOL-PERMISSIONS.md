# Dynamic tools from authenticated permissions

The shared ChatClient has no default tools. Every request gets its own allowed tools:

```text
Postman Basic Auth or OAuth2 Bearer -> Spring Security authenticates the request
  -> ChatService -> UserContext.current()
  -> ToolExposureService.toolsFor(user, hasBookingKey)
  -> prompt.tools(allowedTools)
  -> model -> allowed tool -> authorized integration service -> Hotel API
```

No role, permission or username from the JSON body, chat message or X-Role header is trusted.
This adds no user tables. In default Basic mode, demo accounts are held in memory and passwords
are hashed with BCrypt. Set `AI_AUTH_MODE=oauth2` to authenticate signed access tokens from your
identity provider instead. See [authentication modes and OAuth Postman setup](AUTHENTICATION.md).
Both modes use the same permission policy; OAuth users receive authorities from the configured
token claim rather than the Basic account list below.

## Accounts and policy

| Account | Server permissions | Tools |
|---|---|---|
| `reader` | HOTEL_READ | searchAvailability, getReservationStatus |
| `booker` | HOTEL_READ, RESERVATION_CREATE | Read tools; createReservation only with a valid Idempotency-Key |
| `chat` | CHAT_ONLY | No tools; ordinary model conversation |
| No credentials / wrong password | None | HTTP 401 before chat execution |

Reader and booker are trusted staff demo accounts. HOTEL_READ intentionally allows reading
any reservation known to the backend. This is not a guest ownership policy.

## Start locally

In the AI2 terminal, configure the accounts you want, then restart the application:

```bash
export OPENAI_API_KEY='your-valid-openai-key'
export AI_AUTH_MODE=basic
export AI_READER_PASSWORD='choose-your-own-reader-password'
export AI_BOOKER_PASSWORD='choose-your-own-booker-password'
export AI_CHAT_PASSWORD='choose-your-own-chat-password'
mvn spring-boot:run
```

Each account is disabled if its password variable is blank or unset. There are no default
passwords and no generated fallback account. A dummy OpenAI key only permits startup/direct
API tests; live chat requires a valid model key. The hotel backend still runs separately on 9090.

The local Basic Auth setup is for Postman learning. Use HTTPS and a managed identity provider
for deployment. The separate Hotel Service remains a trusted local backend on 127.0.0.1:9090;
this change does not add authentication or ownership checks to that application's endpoints.

## Postman tests

Import `postman/tool-permissions.postman_collection.json` and set the collection variables
`readerPassword`, `bookerPassword`, `chatPassword` to the passwords you configured locally.
The collection contains no saved passwords and makes no booking write in its normal examples.

In Basic mode, for every request to `/api/chat` or `/api/reservations`, use **Authorization -> Basic Auth**:
username `reader`, `booker` or `chat`; password from the corresponding environment variable.
Existing README chat/reservation examples now require this Authorization setting too.

1. **Missing credentials:** POST `/api/chat` without Authorization -> 401 AUTHENTICATION_REQUIRED.
2. **Reader search:** authenticate as reader; POST `/api/chat`:

   ```json
   {"message":"Search availability at hotel h1 from 2027-04-10 to 2027-04-11 for 2 guests."}
   ```

   In logs, `model.call.availableTools` contains the two read tools.
3. **Reader cannot book:** POST `/api/reservations` as reader -> 403 ACCESS_DENIED, even with
   an Idempotency-Key. A booking key or role claim in a message does not grant permissions.
4. **Booker without key:** same search as booker, without Idempotency-Key -> only read tools.
5. **Booker with key:** add `Idempotency-Key: permission-demo-001` -> createReservation is also
   available. Use an explicit full booking message to execute it. The existing payment rules
   still apply: Java Hotel Service requires a Payment-Method-Token outside the chat message.
6. **Chat only:** authenticate as chat; ask `Hello` -> availableTools=[] and no tool execution.
7. **Switch back to reader:** even after a booker request, the reader cannot see the write tool.

Use each response's traceId to inspect the model's exact available tools:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID --event model.call
```

The short console shows requested tools. The JSON file has availableTools and requestedTools.
API authorization failures happen before chat processing, so those 401/403 responses do not
have chat audit IDs. An unexpected model request for an unregistered tool fails without
backend dispatch; it does not grant access or turn that tool into an allowed callback.

The audit read API retains its existing loopback / optional AUDIT_READ_TOKEN policy; it does
not use these Basic Auth accounts. In Postman keep its request-specific Bearer token setting.

## Where to change the policy

- `config/SecurityConfig`: authenticates the demo accounts and assigns permissions. Also protects
  direct reservation endpoints. In OAuth mode it validates JWTs and maps the configured claim
  to the same authorities. Account names are examples; there are no hardcoded passwords.
- `security/UserContext`: reads only the authenticated Spring Security identity; copies its
  authorities into an immutable permission set.
- `security/ToolExposureService`: chooses the tool objects for this request. Additional trusted
  conditions can be checked here; booking currently also requires an idempotency key.
- `chat/ChatService`: calls toolsFor and attaches the returned tools to this prompt only.
- `audit/ToolAudit`: checks permission again before actually running a tool.
- `integration/AvailabilityService` and `integration/ReservationService`: @PreAuthorize guards
  protect Spring-managed entry methods, including calls from the direct API.

The selection code in ChatService is deliberately small:

```java
Object[] allowedTools = toolExposure.toolsFor(
    UserContext.current(), execution.canCreateReservation());
if (allowedTools.length > 0) prompt.tools(allowedTools);
```

When adding a tool, assign its permission in ToolExposureService, update UserContext.requireTool
(the default denies unknown names), and put the matching @PreAuthorize guard on its integration
entry method. Keep write tools separate from read-only tool objects.

HotelTools deliberately groups both read methods because they share HOTEL_READ. To give search
and reservation-status access different permissions later, split that exposure by method and
update the corresponding tool/service guards together. Do not add shared default tools to
ChatConfig; they could expose capabilities to users who were not selected for them.

Existing native tool limits, duplicate-write fingerprints, backend idempotency, payment context,
Resilience4j and logs remain active after tool selection. A permission denial is not retried as
an upstream/transient error. Do not add extra retries around authorization failures.

## Tests

```bash
mvn clean verify
mvn -Dtest=ToolPermissionsIntegrationTest test
```

The default suite runs local Java tests; the live-model evaluation suite is skipped unless enabled.
Tests use local fake models/backends. They cover real Basic authentication, missing/wrong
credentials, each permission set, the booking-key condition, sequential and concurrent request
isolation, forged role claims, an unexpected model write, successful authorized booking,
and tool/service/direct-API bypass denial. No live OpenAI or payment call is made.
`OAuth2PermissionsIntegrationTest` separately checks signed tokens, invalid signature/issuer/
audience/timestamps, the same permissions and rejection of Basic Auth in OAuth mode.

References: [Spring Security Basic Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)
and [method authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html).

# Choose Basic Auth or OAuth 2.0

AI2 supports one authentication mode at a time. Change `AI_AUTH_MODE`, then restart.
The mode protects `/api/chat` and `/api/reservations/**`. Both modes populate Spring
Security's Authentication; the existing UserContext, tool exposure, ToolAudit permission
checks and service `@PreAuthorize` checks use its authorities.

```text
basic  → username/password → in-memory account authorities ┐
                                                        ├→ SecurityContext → allowed tools
oauth2 → validated JWT → configured permissions claim    ┘                 → service guards
```

## Basic mode (default)

```bash
export AI_AUTH_MODE=basic
export AI_READER_PASSWORD='your-own-reader-password'
export AI_BOOKER_PASSWORD='your-own-booker-password'
export AI_CHAT_PASSWORD='your-own-chat-password'
export OPENAI_API_KEY='your-valid-openai-key'
mvn spring-boot:run
```

Postman: Authorization → Basic Auth. Username `reader`, `booker` or `chat`, with the
corresponding password above. An unset or blank password disables that account. OAuth
configuration is not needed in this mode. Bearer tokens do not authenticate these routes.
The write account is called `booker`, not `writer`.

## OAuth mode (JWT resource server)

Use your existing OAuth/OIDC identity provider. AI2 validates its access tokens; it does
not issue tokens, manage OAuth users, store their passwords, or implement a login page.

```bash
export AI_AUTH_MODE=oauth2
export OAUTH_ISSUER_URI='https://your-identity-provider.example/your-issuer'
export OAUTH_AUDIENCE='hotel-ai2'
export OAUTH_AUTHORITIES_CLAIM='permissions'
export OPENAI_API_KEY='your-valid-openai-key'
mvn spring-boot:run
```

Replace the example issuer and audience with values configured at your provider. The issuer
must exactly match the token's `iss`. The `aud` list must contain your configured audience.
Normally Spring discovers public signing keys through issuer metadata. If discovery is not
available, also set `OAUTH_JWK_SET_URI` to your provider's trusted JWKS URL. Issuer, signature,
audience and timestamp validation still apply when using that URL. Missing issuer/audience,
an empty authorities-claim setting, or an unknown authentication mode prevents startup.
Use HTTPS for provider URLs and application traffic outside local development.

Example decoded **access token claims** (illustrative, not a usable token):

```json
{
  "iss": "https://your-identity-provider.example/your-issuer",
  "sub": "user-123",
  "aud": ["hotel-ai2"],
  "permissions": ["HOTEL_READ", "RESERVATION_CREATE"]
}
```

The provider must also issue normal validity claims such as `exp`. Configure it to emit a
top-level string-array `permissions` claim. If your provider uses another top-level claim
name, set `OAUTH_AUTHORITIES_CLAIM` to that name. Names must match the permissions below
exactly; the converter adds no `SCOPE_` or `ROLE_` prefix. Nested provider roles (for example,
Keycloak `realm_access.roles`) are not automatically traversed; use a provider claim mapper
to emit the top-level claim. Identity defaults to JWT `sub`.

| Token permissions | Tools exposed |
|---|---|
| `HOTEL_READ` | searchAvailability, getReservationStatus |
| `HOTEL_READ`, `RESERVATION_CREATE` | Read tools; createReservation only with a valid Idempotency-Key |
| Missing / empty permissions, or only `CHAT_ONLY` | Authenticated chat without tools |

`scope`, prompt text, usernames, and X-Role headers do not grant permissions when the
configured authorities claim is `permissions`. Local Basic users are not created in OAuth
mode, even if their password variables remain set. There is no Basic Auth fallback.

For user login, obtain an **access token** through your provider's Authorization Code + PKCE
flow (Postman Authorization → OAuth 2.0 can do this with the provider's client, authorization
URL, token URL, scopes and registered callback). Then use the token as Bearer Token on AI2
requests. Do not use an ID token or the OpenAI API key as the AI2 access token. Client
credentials are for service identities rather than an interactive user's identity.

## Postman checks

Import [oauth2-permissions.postman_collection.json](../postman/oauth2-permissions.postman_collection.json).
Set `readerAccessToken`, `bookerAccessToken` and `chatAccessToken` locally with real tokens
from your provider; no tokens or client secrets are saved in the collection. Start the
application in OAuth mode. Existing Basic Auth collections are for Basic mode.

1. **Missing token:** POST `/api/chat` → 401 `AUTHENTICATION_REQUIRED`.
2. **Reader token:** search message → 200; model `availableTools` contains read tools only.
3. **Reader direct booking:** POST `/api/reservations` → 403 `ACCESS_DENIED` before the backend.
4. **Booker token without booking key:** chat offers read tools only.
5. **Booker token with `Idempotency-Key`:** chat also offers createReservation. The supplied
   collection only searches; follow the README's confirmation/payment flow for a real booking.
6. **Chat-only token:** `Hello` → 200, no available tools.
7. **Expired, wrong issuer/audience, modified or malformed token:** 401 before model execution.
8. **Basic credentials in OAuth mode:** 401. Switch to `AI_AUTH_MODE=basic` and restart to use them.

The same token goes on each request. `Conversation-Id` groups chat logs; it is not an
authentication credential. Verify offered/requested tools with the response trace ID:

```bash
python3 scripts/read_trace.py --trace-id COPY_TRACE_ID --event model.call
```

401/403 failures at the security filter happen before chat execution and have no chat trace ID.
Existing tool limits, fingerprints, idempotency, Resilience4j and payment handling remain active.

## Boundaries and code locations

- `config/SecurityConfig.java`: mode selection, Basic accounts, JWT decoder/converter and API guards.
- `application.yml`: `app.security` values and environment-variable bindings.
- `security/UserContext.java`: authenticated authorities snapshot; no authentication-mode branching needed.
- `security/ToolExposureService.java`: per-request allowed tools.

The audit read API still uses its separate loopback / optional `AUDIT_READ_TOKEN` policy.
The OpenAI key is still a separate server credential. AI2 does not forward the user's token
or Basic credentials to Hotel Service. Port 9090 retains its existing local trust boundary;
protecting that backend or adding guest-specific ownership requires a separate change.

## Verify without a real provider

```bash
mvn -Dtest=SecurityConfigTest,ToolPermissionsIntegrationTest,OAuth2PermissionsIntegrationTest test
mvn clean verify
```

The OAuth tests use locally signed RSA JWTs and a local JWKS server, exercising the real
bearer filter, signature/issuer/audience/timestamp validation, claim mapping, tool exposure
and service guards. A deterministic model and mock hotel backend avoid live API calls.
Basic tests verify existing access and rejection of Bearer credentials. The live-model
evaluation suite remains separately opt-in.

Reference: [Spring Security JWT resource servers](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

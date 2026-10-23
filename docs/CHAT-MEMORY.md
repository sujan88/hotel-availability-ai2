# Simple memory per user and conversation

Chat history lives in application memory, not H2. H2 still stores only reservation audit
outcomes. There is no summarization, vector store, transcript database or extra dependency.

```text
Authenticated user + Conversation-Id
  -> verify conversation belongs to that user
  -> Spring AI loads that conversation's recent user/assistant text
  -> current message + freshly selected tools -> model/tool loop
  -> save user/final assistant text; return same conversationId, new turnId/traceId
```

## Configuration

```yaml
app:
  chat:
    memory:
      max-messages: ${CHAT_MEMORY_MAX_MESSAGES:6}
      idle-ttl: ${CHAT_MEMORY_IDLE_TTL:PT30M}
      max-conversations: ${CHAT_MEMORY_MAX_CONVERSATIONS:1000}
```

Six messages normally means three user/assistant exchanges. The stored history window is
separate for each conversation. The model can receive up to that window plus the current
message and system instructions. This is a message-count limit, not a strict token budget.
Keep messages/replies concise; no extra model call is made to produce summaries.

Inactive histories expire after 30 minutes. Expired entries are cleaned on subsequent chat
requests. The process holds at most 1,000 conversation histories; creating another evicts
the least recently used one. All histories disappear on restart. This single-instance demo
does not share memory across application replicas or provide a full transcript archive.

## Postman: two messages in one conversation

Restart AI2 after updating the project. Use its actual port (8080 by default) and authenticate
with Basic Auth or OAuth2 as configured. For this test, no hotel backend or booking key is
needed, but a valid OpenAI key is required for real chat.

1. POST `http://localhost:8080/api/chat` **without** a Conversation-Id header:

   ```json
   {"message":"Remember that my preferred hotel ID is h1. Reply briefly."}
   ```

2. Copy `conversationId` from the response. Send the next request as the **same user**:

   ```http
   POST http://localhost:8080/api/chat
   Content-Type: application/json
   Conversation-Id: COPY_THE_RETURNED_UUID
   ```

   ```json
   {"message":"What hotel ID did I mention?"}
   ```

   The model has the earlier exchange and should answer h1. Wording is model-dependent.

3. Omit Conversation-Id to start a fresh conversation without that history.
4. Authenticate as another user and send the first user's conversation ID: HTTP 403,
   `CONVERSATION_ACCESS_DENIED`, before any model call.

Import [the memory collection](../postman/chat-memory.postman_collection.json) for these
Basic Auth examples. Set local reader/booker passwords. Its first request saves the returned
ID into `memoryConversationId`; the second reuses it. For OAuth, replace request authorization
with the appropriate user's access token. Do not paste tokens into chat text.

| Supplied Conversation-Id | Behavior |
|---|---|
| Omitted | Server creates a conversation owned by the authenticated user |
| Existing, owned by current user | Load its history |
| Existing, owned by another user | 403 CONVERSATION_ACCESS_DENIED |
| Unknown, expired, evicted, or from a previous process | 404 CONVERSATION_NOT_FOUND; omit the header to start fresh |
| Invalid UUID | 400 INVALID_CONVERSATION_ID |

Do not invent the first ID yourself or assume an audit record restores history. The server
must have created the conversation in this running instance. Authorization still applies
on every HTTP request; a conversation ID is not a credential.

## Where the code is

- `chat/ConversationMemory`: a small owner/expiry registry. Each conversation gets its own
  Spring AI MessageWindowChatMemory and MessageChatMemoryAdvisor. No history is shared globally.
- `chat/ChatService`: resolves the owner's conversation, serializes its turns, and attaches
  its advisor and ChatMemory.CONVERSATION_ID to this prompt.
- The memory advisor runs **outside** the native tool loop. Intermediate tool calls/results
  stay within the current execution; only user/final assistant text is kept for later turns.

Tools, permissions, Idempotency-Key, payment context and fingerprint tracking are freshly
resolved for every request. Never reuse an old booking operation merely because memory
mentions it. Reservation status, payment, inventory and prices must still be verified through
the backend. Memory text is context, not proof of confirmation or authorization.

The audit log still excludes raw chat text; `messageStored=false` describes audit storage.
Conversation memory itself contains recent chat text in RAM. Do not put payment tokens,
card details or credentials in messages. Trusted payment/idempotency headers are not copied
into chat history. This feature adds no durable booking-state store or transcript redaction.

## Tests

```bash
mvn -Dtest=ConversationMemoryTest,ChatMemoryIntegrationTest test
mvn clean verify
```

Tests use a deterministic local model and mock backend. They check recall, separate
conversations/users, forbidden/unknown IDs, the six-message window, expiry and capacity,
and that old tool messages or booking keys do not leak into later turns.

# Tool selection evaluations

These tests ask: **Did the model choose the right tool and arguments, or correctly choose no tool?**
They complement the existing execution, permission, retry, timeout and idempotency tests.
They do not execute tool methods, make hotel/payment calls or create reservations.

## The case record

All evaluation code lives under `src/test/java/com/example/hotel/eval`, not in the production API:

```java
record ToolEvalCase(
    String userPrompt,
    String expectedTool,
    Map<String, Object> expectedArguments,
    Set<String> forbiddenTools,
    boolean expectNoTool,
    boolean confirmationRequired) {}
```

For example, the catalog contains:

```java
new ToolEvalCase(
    "Show rooms at hotel H500 from 2026-11-10 to 2026-11-12 for 2 guests.",
    "searchAvailability",
    Map.of("hotelId", "H500", "checkIn", "2026-11-10",
           "checkOut", "2026-11-12", "guests", 2),
    Set.of("createReservation", "cancelReservation", "issueRefund"),
    false,
    false);
```

The negative case is:

```java
new ToolEvalCase("Thanks", null, Map.of(),
    Set.of("createReservation", "cancelReservation", "issueRefund"), true, false);
```

A `Scenario` adds a case ID, synthetic user permissions and whether a booking key exists.
The real `ToolExposureService` chooses schemas from those conditions. These fixtures do not
change how real HTTP requests authenticate; production roles still come from Spring Security.

## Included cases

`ToolSelectionCases.all()` defines 15 scenarios:

| Case | Expected first decision |
|---|---|
| Search with ISO dates and guest count | searchAvailability, exact four arguments |
| Search with natural-language dates and explicit hotel ID/year | searchAvailability, normalized ISO dates |
| Existing reservation status | getReservationStatus, correct reservationId |
| Explicitly confirmed booking with all details and a booking key | createReservation, exact seven arguments |
| Thanks | No tool |
| General hotel question | No tool |
| Missing search dates | No tool; ask for dates |
| Missing guest count | No tool; ask for guest count |
| Missing booking details | No tool; collect details |
| Guest explicitly has not confirmed | No tool and no write before confirmation |
| Unsupported refund | No tool; no invented issueRefund |
| Unsupported cancellation | No tool; no invented cancelReservation |
| Reader requests booking | No write tool exposed; expected no tool |
| Booker requests booking without an idempotency key | No write tool exposed; expected no tool |
| Chat-only account requests search | No tools exposed; expected no tool |

Years and IDs are explicit; guest data is synthetic. The example without a guest count is
intentionally a missing-information case because this project's search schema requires guests.
Cancellation and refund are forbidden examples, not implemented project features.

## How capture works

```text
Scenario permissions + booking-key condition
  -> real ToolExposureService
  -> real @Tool definitions, with execution-disabled callbacks
  -> raw ChatModel.call(system prompt + user prompt + offered schemas)
  -> capture ALL assistant ToolCalls
  -> compare tool names, arguments and forbidden calls
```

The runner uses `ChatConfig.SYSTEM_PROMPT`, the same prompt as production. It calls the raw
ChatModel directly, with no ChatClient ToolCallingAdvisor or tool loop. Schema-only callbacks
throw immediately if execution is attempted. A tool decision therefore stops after one model
call, even when that decision is createReservation. The production chat flow is unchanged. There is no new runtime evaluation endpoint. Because
this runner bypasses ChatService, investigate evaluations using their case IDs and reports,
rather than expecting a chat traceId.
No expected tool is forced through tool_choice, and the evaluator checks the complete returned
batch rather than selecting only the first call.

`ToolSelectionEvaluator.Result.actualCalls()` contains the captured tool names and argument JSON.
`violations()` identifies failed rules; `passed()` is true only if there are no violations.
Arguments must be exactly the expected keys and values: property order is ignored and 2/2.0
are equivalent numeric values. Missing/extra keys, wrong strings/dates, duplicate JSON keys,
malformed JSON and extra calls fail. A missing or truncated model response is not a successful
no-tool result. Tools not exposed for the scenario also fail, even if they otherwise look valid.

For pending-confirmation cases, `confirmationRequired` checks that no write is requested.
It does not judge the wording of a confirmation question or implement durable user consent.
The negative fixtures also set expectNoTool, so they require no tool call at all.

## 1. Run the ordinary local tests

```bash
RUN_LIVE_TOOL_EVALS=false mvn clean verify
```

Or run only the evaluation harness tests:

```bash
RUN_LIVE_TOOL_EVALS=false mvn \
  -Dtest=ToolSelectionEvaluatorTest,ToolSelectionCaptureIntegrationTest test
```

Verification: 119 local Java tests passed with zero failures/errors, and the optional live-model suite was skipped (including the authentication-mode tests).

No valid OpenAI key or running backend is needed. These tests use a scripted localhost model
HTTP server and real Spring AI serialization. They validate grading, schema exposure, one-call
capture and zero backend dispatch. **Scripted responses do not prove the real model selects
correctly.** The live suite is skipped unless explicitly enabled.

## 2. Evaluate the real model (optional)

This sends the 15 synthetic prompts to the configured OpenAI model and consumes API usage.
It still does not execute hotel/payment tools. Use a valid test API key and synthetic fixtures.
No hotel-service on 9090 or Basic Auth account password is required for this selection-only test.

```bash
export OPENAI_API_KEY='your-valid-test-key'
export OPENAI_MODEL='gpt-4o-mini'
RUN_LIVE_TOOL_EVALS=true mvn -Dtest=LiveToolSelectionEvalTest test
```

JUnit shows a separate pass/fail result per named scenario. Model behavior can vary and live
cases are not promised to pass. A failure can reveal a selection/prompt issue even when all
local integration tests pass. Invalid credentials or provider errors fail the affected cases;
they are not treated as valid no-tool answers.

The application retains its configured model timeout and zero provider retries. The runner
makes one model request per case; it does not retry a failed assertion to obtain a passing answer.
It requests store=false. Raw prompts, credentials and raw tool argument payloads are not written
to the summary report. Keep any fixtures you add synthetic too.

## Reports and interpretation

Live evaluations overwrite `target/tool-evals/results.jsonl` at the start of the run:

```json
{"case":"search-iso-dates","expectedTool":"searchAvailability","expectNoTool":false,"confirmationRequired":false,"model":"...","actualTools":["searchAvailability"],"passed":true,"violations":[]}
```

For an argument error, violations contains a field name such as `Argument mismatch: checkOut`.
For Thanks with an unwanted call, it contains `Expected no tool calls`. JUnit XML and
text reports are also in `target/surefire-reports`. Reports are ignored by Git and excluded
from the downloadable source ZIP.

These are first-decision evaluations. They do not prove answer correctness, multi-turn memory,
confirmation UI behavior, payment success, native loop limits or service retries. Those remain
separate application concerns and existing integration tests. In particular, conversationId is
still audit grouping, not chat memory; a standalone "Yes" does not supply earlier booking details.

## Add another case

Add a Scenario to `ToolSelectionCases.all()`, with exact expected arguments and the intended
permissions/key condition. For a no-tool case, use expectedTool=null and an empty argument map.
Set forbiddenTools for actions that must never be chosen. Run the local harness checks first,
then the opt-in live suite to measure the actual model. Do not change a fixture to a passing
answer without deciding whether the product's expected behavior should actually change.

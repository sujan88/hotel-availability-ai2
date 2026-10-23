package com.example.hotel.audit;

import com.example.hotel.chat.ChatResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Operational details go to structured logs; only booking-write outcomes go to one table. */
@Component
public class AuditStore {
  public record Span(String id, long startedNanos, Map<String, Object> fields) {
    public Span() {
      this(AuditContext.id(), System.nanoTime(), new ConcurrentHashMap<>());
    }
  }

  private static final Logger log = LoggerFactory.getLogger(AuditStore.class);
  private static final Set<String> BUSINESS_EVENTS =
      Set.of("RESERVATION_ACCEPTED", "RESERVATION_CONFIRMED", "RESERVATION_CANCELLED");
  private final JdbcTemplate db;

  public AuditStore(JdbcTemplate db) {
    this.db = db;
  }

  public AuditContext.Frame begin(String conversationId) {
    if (conversationId != null && !uuid(conversationId))
      throw new AuditProblem(400, "INVALID_CONVERSATION_ID");
    var turn =
        new AuditContext.Turn(
            conversationId == null ? AuditContext.id() : conversationId,
            AuditContext.id(),
            AuditContext.id().replace("-", ""));
    var frame = new AuditContext.Frame(turn, null, null);
    emit(frame, "user.message.received", Map.of("status", "STARTED", "messageStored", false));
    return frame;
  }

  public void finish(AuditContext.Frame frame, ChatResult result) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("status", result.success() ? "COMPLETED" : "FAILED");
    put(fields, "errorCode", result.errorCode());
    fields.put("totalLatencyMs", elapsed(frame.turn().startedNanos));
    fields.put("totalTokens", frame.turn().totalTokens.get());
    fields.put("usageReported", frame.turn().usageReported.get());
    fields.put("auditComplete", frame.turn().complete.get());
    fields.put("toolsCalled", frame.turn().toolsCalled());
    fields.put("toolCallCount", frame.turn().toolCallCount());
    emit(frame, "turn.completed", fields);
  }

  public Span startModel(AuditContext.Frame frame, String model) {
    var span = new Span();
    span.fields().put("modelCallId", span.id());
    put(span.fields(), "modelName", safeName(model));
    span.fields().put("promptVersion", "hotel-tools-v1");
    emit(frame, "model.call.started", span.fields());
    return span;
  }

  public void finishModel(
      AuditContext.Frame frame, Span span, ChatResponse response, String error) {
    if (response != null) {
      put(span.fields(), "modelName", safeName(response.getMetadata().getModel()));
      var usage = response.getMetadata().getUsage();
      if (usage != null) {
        put(span.fields(), "inputTokens", usage.getPromptTokens());
        put(span.fields(), "outputTokens", usage.getCompletionTokens());
        put(span.fields(), "totalTokens", usage.getTotalTokens());
        if (usage.getTotalTokens() != null) {
          frame.turn().totalTokens.addAndGet(usage.getTotalTokens());
          frame.turn().usageReported.set(true);
        }
      }
      if (response.getResult() != null) {
        var allowed =
            span.fields().get("availableTools") instanceof Collection<?> names ? names : List.of();
        var requested = response.getResult().getOutput().getToolCalls();
        span.fields()
            .put(
                "requestedTools",
                requested.stream()
                    .map(call -> allowed.contains(call.name()) ? call.name() : "UNKNOWN_TOOL")
                    .distinct()
                    .toList());
        span.fields().put("toolRequestsCount", requested.size());
      }
      if (response.getResult() != null)
        put(
            span.fields(),
            "finishReason",
            safeName(response.getResult().getMetadata().getFinishReason()));
    }
    complete(frame, span, "model.call", error == null ? "COMPLETED" : "FAILED", error);
  }

  public Span startTool(AuditContext.Frame frame, String name) {
    var span = new Span();
    span.fields().put("toolCallId", span.id());
    put(span.fields(), "modelCallId", frame.turn().lastModelCallId);
    put(span.fields(), "toolName", safeName(name));
    frame.turn().registerTool(span.id(), safeName(name) == null ? "UNKNOWN_TOOL" : name);
    emit(frame, "tool.started", span.fields());
    return span;
  }

  public void finishTool(AuditContext.Frame frame, Span span, String status, String error) {
    span.fields().put("duplicateDetected", "DUPLICATE_TOOL_CALL".equals(error));
    complete(frame, span, "tool.call", status, error);
  }

  public void rejected(String tool, String code) {
    var frame = AuditContext.current();
    if (frame == null) return;
    var span = startTool(frame, tool);
    finishTool(frame, span, "REJECTED", code);
    event(frame.withTool(span.id()), "TOOL_REJECTED", null, code);
  }

  /** Visible entry point in the integration service; operational logs only, no database write. */
  public static void serviceCall(String operation) {
    var frame = AuditContext.current();
    if (frame != null)
      emit(frame, "service.call", Map.of("operation", operation, "status", "STARTED"));
  }

  public Span startHttp(AuditContext.Frame frame, String operation, String circuitState) {
    var span = new Span();
    span.fields().put("downstreamCallId", span.id());
    span.fields().put("serviceName", "HOTEL_API");
    span.fields().put("operation", operation);
    int attempt = frame.turn().nextAttempt(frame.toolCallId());
    span.fields().put("attemptNo", attempt);
    span.fields().put("retryCount", attempt - 1);
    put(span.fields(), "circuitState", circuitState);
    emit(frame, "downstream.started", span.fields());
    return span;
  }

  public void finishHttp(AuditContext.Frame frame, Span span, Integer httpStatus, String error) {
    put(span.fields(), "httpStatus", httpStatus);
    complete(frame, span, "downstream.call", error == null ? "COMPLETED" : "FAILED", error);
  }

  private void complete(
      AuditContext.Frame frame, Span span, String event, String status, String error) {
    span.fields().put("status", status);
    put(span.fields(), "errorCode", error);
    span.fields().put("latencyMs", elapsed(span.startedNanos()));
    emit(frame, event, span.fields());
  }

  public void event(AuditContext.Frame frame, String type, String reservationId, String code) {
    if (frame == null) return;
    var fields = new LinkedHashMap<String, Object>();
    fields.put("eventType", type);
    put(fields, "reservationId", reservationId);
    put(fields, "errorCode", code);
    if (BUSINESS_EVENTS.contains(type) && reservationId != null) {
      String id = AuditContext.id();
      fields.put("auditId", id);
      try {
        db.update(
            "INSERT INTO reservation_audit VALUES(?,?,?,?,?,?,?,?)",
            id,
            frame.turn().traceId,
            frame.turn().conversationId,
            frame.turn().turnId,
            frame.toolCallId(),
            reservationId,
            type,
            System.currentTimeMillis());
        fields.put("persisted", true);
      } catch (DataAccessException e) {
        frame.turn().complete.set(false);
        fields.put("persisted", false);
        fields.put("persistenceError", "AUDIT_UNAVAILABLE");
      }
      emit(frame, "business.event", fields);
    } else emit(frame, "audit.event", fields);
  }

  /** Business events only. There is deliberately no DB reconstruction of operational traces. */
  public Map<String, Object> events(String traceId, String reservationId, String conversationId) {
    int filters =
        (traceId != null ? 1 : 0)
            + (reservationId != null ? 1 : 0)
            + (conversationId != null ? 1 : 0);
    if (filters != 1) throw new AuditProblem(400, "PROVIDE_ONE_AUDIT_FILTER");
    String column, value;
    if (traceId != null) {
      if (!traceId.matches("[a-f0-9]{32}")) throw new AuditProblem(400, "INVALID_TRACE_ID");
      column = "trace_id";
      value = traceId;
    } else if (reservationId != null) {
      if (!reservationId.matches("[A-Za-z0-9_-]{1,80}"))
        throw new AuditProblem(400, "INVALID_RESERVATION_ID");
      column = "reservation_id";
      value = reservationId;
    } else {
      if (!uuid(conversationId)) throw new AuditProblem(400, "INVALID_CONVERSATION_ID");
      column = "conversation_id";
      value = conversationId;
    }
    var rows =
        db.query(
            "SELECT * FROM reservation_audit WHERE "
                + column
                + "=? ORDER BY created_at,audit_id FETCH FIRST 201 ROWS ONLY",
            (rs, n) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("auditId", rs.getString("audit_id"));
              row.put("traceId", rs.getString("trace_id"));
              row.put("conversationId", rs.getString("conversation_id"));
              row.put("turnId", rs.getString("turn_id"));
              row.put("toolCallId", rs.getString("tool_call_id"));
              row.put("reservationId", rs.getString("reservation_id"));
              row.put("eventType", rs.getString("event_type"));
              row.put("createdAt", rs.getLong("created_at"));
              return row;
            },
            value);
    return Map.of(
        "source",
        "DATABASE_BUSINESS_EVENTS",
        "events",
        rows.subList(0, Math.min(rows.size(), 200)),
        "truncated",
        rows.size() > 200);
  }

  private static void emit(AuditContext.Frame frame, String event, Map<String, Object> fields) {
    // The structured encoder also serializes MDC. Emit one allow-listed map so duplicate
    // correlation keys cannot make it reject the JSON event, and unrelated MDC stays private.
    var data = new LinkedHashMap<String, Object>();
    data.put("traceId", frame.turn().traceId);
    data.put("conversationId", frame.turn().conversationId);
    data.put("turnId", frame.turn().turnId);
    put(data, "modelCallId", frame.modelCallId());
    put(data, "toolCallId", frame.toolCallId());
    put(data, "toolName", frame.turn().toolName(frame.toolCallId()));
    data.putAll(fields);
    data.put("event", event);
    var previousMdc = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      var builder =
          Boolean.FALSE.equals(fields.get("persisted"))
              ? log.atError()
              : "FAILED".equals(fields.get("status")) ? log.atWarn() : log.atInfo();
      data.forEach(builder::addKeyValue);
      builder.log(consoleMessage(event, data));
    } finally {
      if (previousMdc == null) MDC.clear();
      else MDC.setContextMap(previousMdc);
    }
  }

  /** Short console line. Full structured fields still go to the rotating JSON file. */
  private static String consoleMessage(String event, Map<String, Object> data) {
    String prefix =
        switch (event) {
          case "user.message.received", "turn.completed" -> "CHAT";
          case "model.call.started", "model.call" -> "MODEL";
          case "tool.started", "tool.call" -> "TOOL";
          case "service.call" -> "SERVICE";
          case "downstream.started", "downstream.call" -> "EXTERNAL API";
          case "business.event" -> "BUSINESS";
          default -> "AUDIT";
        };
    var line = new StringBuilder("[").append(prefix).append("] ").append(event);
    for (String key :
        List.of(
            "toolName",
            "operation",
            "status",
            "httpStatus",
            "attemptNo",
            "latencyMs",
            "requestedTools",
            "toolsCalled",
            "eventType",
            "errorCode",
            "persistenceError")) {
      Object value = data.get(key);
      if (value != null) line.append(" ").append(key).append("=").append(value);
    }
    return line.append(" traceId=").append(data.get("traceId")).toString();
  }

  private static void put(Map<String, Object> fields, String key, Object value) {
    if (value != null) fields.put(key, value);
  }

  private static boolean uuid(String id) {
    return id != null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
  }

  private static String safeName(String name) {
    return name != null && name.matches("[A-Za-z0-9._:/-]{1,120}") ? name : null;
  }

  private static long elapsed(long nanos) {
    return Math.max(0, (System.nanoTime() - nanos) / 1000000);
  }
}

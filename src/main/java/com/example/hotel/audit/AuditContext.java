package com.example.hotel.audit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.slf4j.MDC;

/** Lexical scope only. Retry/worker propagation is explicit through immutable Frame arguments. */
public final class AuditContext {
  public static final class Turn {
    public final String conversationId, turnId, traceId;
    public final long startedNanos = System.nanoTime();
    public volatile String lastModelCallId;
    public final AtomicBoolean complete = new AtomicBoolean(true);
    public final AtomicLong totalTokens = new AtomicLong();
    public final AtomicBoolean usageReported = new AtomicBoolean();
    private final ConcurrentHashMap<String, String> toolNames = new ConcurrentHashMap<>();
    private final java.util.Set<String> invokedToolIds = ConcurrentHashMap.newKeySet();

    void registerTool(String id, String name) {
      toolNames.put(id, name);
    }

    void toolInvoked(String id) {
      invokedToolIds.add(id);
    }

    String toolName(String id) {
      return id == null ? null : toolNames.get(id);
    }

    java.util.List<String> toolsCalled() {
      return invokedToolIds.stream().map(toolNames::get).distinct().sorted().toList();
    }

    int toolCallCount() {
      return invokedToolIds.size();
    }

    private final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public Turn(String conversationId, String turnId, String traceId) {
      this.conversationId = conversationId;
      this.turnId = turnId;
      this.traceId = traceId;
    }

    int nextAttempt(String toolId) {
      return attempts.computeIfAbsent(toolId, k -> new AtomicInteger()).incrementAndGet();
    }
  }

  public record Frame(Turn turn, String modelCallId, String toolCallId) {
    public Frame withModel(String id) {
      return new Frame(turn, id, null);
    }

    public Frame withTool(String id) {
      return new Frame(turn, turn.lastModelCallId, id);
    }
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

  public static Frame current() {
    return CURRENT.get();
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static Scope open(Frame frame) {
    Frame previous = CURRENT.get();
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    if (frame == null) CURRENT.remove();
    else CURRENT.set(frame);
    MDC.remove("traceId");
    MDC.remove("turnId");
    MDC.remove("toolCallId");
    MDC.remove("toolName");
    if (frame != null) {
      MDC.put("traceId", frame.turn().traceId);
      MDC.put("turnId", frame.turn().turnId);
      if (frame.toolCallId() != null) MDC.put("toolCallId", frame.toolCallId());
      if (frame.turn().toolName(frame.toolCallId()) != null)
        MDC.put("toolName", frame.turn().toolName(frame.toolCallId()));
    }
    return () -> {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
      if (previousMdc == null) MDC.clear();
      else MDC.setContextMap(previousMdc);
    };
  }

  private AuditContext() {}
}

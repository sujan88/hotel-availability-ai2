package com.example.hotel.audit;

import com.example.hotel.dto.AvailabilityResponse;
import com.example.hotel.dto.ReservationResponse;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Called explicitly by each @Tool method: start log, run the method, finish log. */
@Component
public class ToolAudit {
  private final AuditStore store;

  public ToolAudit(AuditStore store) {
    this.store = store;
  }

  public <T> T log(String toolName, Supplier<T> action) {
    var frame = AuditContext.current();
    if (frame == null) return action.get(); // Direct Java calls outside chat have no turn.
    var span = store.startTool(frame, toolName);
    var toolFrame = frame.withTool(span.id());
    try (var scope = AuditContext.open(toolFrame)) {
      frame.turn().toolInvoked(span.id());
      T result;
      try {
        result = action.get();
      } catch (RuntimeException e) {
        store.finishTool(frame, span, "FAILED", "TOOL_EXECUTION_FAILED");
        throw e;
      }
      // Read typed results, never parse or log raw tool JSON, guest details or payment tokens.
      String error;
      if (result instanceof AvailabilityResponse availability) {
        error = availability.success() ? null : safeError(availability.errorCode());
      } else if (result instanceof ReservationResponse reservation) {
        error = reservation.success() ? null : safeError(reservation.errorCode());
        if (reservation.success() && !recordReservation(toolFrame, span, toolName, reservation))
          error = "INVALID_TOOL_RESPONSE";
      } else {
        error = "INVALID_TOOL_RESPONSE";
      }
      store.finishTool(frame, span, error == null ? "COMPLETED" : "FAILED", error);
      return result;
    }
  }

  private boolean recordReservation(
      AuditContext.Frame frame, AuditStore.Span span, String toolName, ReservationResponse result) {
    if (result.reservationId() == null
        || !result.reservationId().matches("[A-Za-z0-9_-]{1,80}")
        || result.status() == null
        || !Set.of("CONFIRMED", "CANCELLED", "PENDING_PAYMENT", "PAYMENT_UNKNOWN")
            .contains(result.status())) return false;
    span.fields().put("resultStatus", result.status());
    span.fields().put("reservationId", result.reservationId());
    String event =
        toolName.equals("getReservationStatus")
            ? "RESERVATION_STATUS_OBSERVED"
            : switch (result.status()) {
              case "CONFIRMED" -> "RESERVATION_CONFIRMED";
              case "CANCELLED" -> "RESERVATION_CANCELLED";
              default -> "RESERVATION_ACCEPTED";
            };
    store.event(frame, event, result.reservationId(), null);
    return true;
  }

  private static String safeError(String code) {
    return code != null && code.matches("[A-Z_]{1,80}") ? code : "TOOL_FAILED";
  }
}

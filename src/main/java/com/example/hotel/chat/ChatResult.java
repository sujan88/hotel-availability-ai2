package com.example.hotel.chat;

import com.example.hotel.dto.ReservationResponse;
import java.util.List;

public record ChatResult(
    boolean success,
    String message,
    String errorCode,
    List<ReservationResponse> reservations,
    String conversationId,
    String turnId,
    String traceId,
    boolean auditComplete) {
  public ChatResult {
    reservations = List.copyOf(reservations);
  }

  public static ChatResult success(String text) {
    return new ChatResult(true, text, null, List.of(), null, null, null, true);
  }

  public static ChatResult failure(String code, String message) {
    return new ChatResult(false, message, code, List.of(), null, null, null, true);
  }

  public ChatResult withReservations(List<ReservationResponse> results) {
    return new ChatResult(
        success, message, errorCode, results, conversationId, turnId, traceId, auditComplete);
  }

  public ChatResult withAudit(com.example.hotel.audit.AuditContext.Frame frame) {
    var turn = frame.turn();
    return new ChatResult(
        success,
        message,
        errorCode,
        reservations,
        turn.conversationId,
        turn.turnId,
        turn.traceId,
        turn.complete.get());
  }
}

package com.example.hotel.integration;

import com.example.hotel.audit.AuditContext;
import com.example.hotel.client.HotelApiClient;
import com.example.hotel.dto.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ResilientReservationGateway {
  private final HotelApiClient client;
  private final Executor executor;

  public ResilientReservationGateway(
      HotelApiClient client, @Qualifier("reservationExecutor") Executor executor) {
    this.client = client;
    this.executor = executor;
  }

  @CircuitBreaker(name = "reservation")
  @Retry(name = "reservation")
  @TimeLimiter(name = "reservation")
  public CompletableFuture<Reservation> create(
      ReservationRequest request, String idempotencyKey, AuditContext.Frame audit) {
    // The caller supplies one stable key BEFORE this retried method is entered.
    try {
      return CompletableFuture.supplyAsync(
          () -> {
            try (var scope = AuditContext.open(audit)) {
              return client.createReservation(request, idempotencyKey);
            }
          },
          executor);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @CircuitBreaker(name = "reservation")
  @Retry(name = "reservation")
  @TimeLimiter(name = "reservation")
  public CompletableFuture<Reservation> status(String id, AuditContext.Frame audit) {
    try {
      return CompletableFuture.supplyAsync(
          () -> {
            try (var scope = AuditContext.open(audit)) {
              return client.getReservationStatus(id);
            }
          },
          executor);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}

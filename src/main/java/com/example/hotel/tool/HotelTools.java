package com.example.hotel.tool;

import com.example.hotel.audit.ToolAudit;
import com.example.hotel.dto.*;
import com.example.hotel.reservation.ReservationExecution;
import com.example.hotel.integration.AvailabilityService;
import com.example.hotel.integration.ReservationService;
import java.time.LocalDate;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class HotelTools {
  private final ToolAudit audit;
  private final AvailabilityService service;
  private final ReservationService reservations;

  public HotelTools(AvailabilityService service, ReservationService reservations, ToolAudit audit) {
    this.service = service;
    this.audit = audit;
    this.reservations = reservations;
  }

  @Tool(
      description =
          "Search live hotel availability. Never invent rooms or prices. An unsuccessful response"
              + " is not an empty availability result. Do not automatically repeat failed"
              + " searches.")
  public AvailabilityResponse searchAvailability(
      @ToolParam(description = "Hotel identifier") String hotelId,
      @ToolParam(description = "Check-in date, YYYY-MM-DD") String checkIn,
      @ToolParam(description = "Check-out date, YYYY-MM-DD") String checkOut,
      @ToolParam(description = "Number of guests, 1–20") int guests) {
    return audit.log(
        "searchAvailability",
        () -> {
          AvailabilityRequest request;
          try {
            request =
                new AvailabilityRequest(
                    hotelId, LocalDate.parse(checkIn), LocalDate.parse(checkOut), guests);
          } catch (RuntimeException e) {
            return AvailabilityResponse.failure(
                "INVALID_REQUEST", "Provide a valid hotel ID, ISO dates and 1–20 guests.", false);
          }
          return service.search(request);
        });
  }

  @Tool(
      description =
          "Read the current state of an existing reservation. Pending payment is not confirmation."
              + " Never repeat creation to check status.")
  public ReservationResponse getReservationStatus(
      @ToolParam(description = "Existing reservation identifier") String reservationId,
      ToolContext context) {
    return audit.log(
        "getReservationStatus",
        () -> ReservationExecution.capture(context, reservations.status(reservationId)));
  }
}

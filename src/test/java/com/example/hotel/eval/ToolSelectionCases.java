package com.example.hotel.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed years and synthetic IDs/guest data keep selection independent of the current date. */
public final class ToolSelectionCases {
  public record Scenario(
      String id, ToolEvalCase expectation, Set<String> permissions, boolean bookingKey) {
    public Scenario {
      permissions = Set.copyOf(permissions);
    }

    @Override
    public String toString() {
      return id;
    }
  }

  static final Set<String> READER = Set.of("HOTEL_READ");
  static final Set<String> BOOKER = Set.of("HOTEL_READ", "RESERVATION_CREATE");
  static final Set<String> NO_WRITES =
      Set.of("createReservation", "cancelReservation", "issueRefund");
  static final Map<String, Object> SEARCH_ARGS =
      Map.of("hotelId", "H500", "checkIn", "2026-11-10", "checkOut", "2026-11-12", "guests", 2);
  static final Map<String, Object> BOOKING_ARGS =
      Map.of(
          "hotelId",
          "h1",
          "roomId",
          "r1",
          "checkIn",
          "2027-04-10",
          "checkOut",
          "2027-04-11",
          "guests",
          2,
          "guestName",
          "Alex Eval",
          "guestEmail",
          "alex@example.test");
  static final String BOOKING =
      "I confirm that I want to book hotel h1, room r1, from 2027-04-10 to 2027-04-11 for 2 guests."
          + " Guest name Alex Eval, email alex@example.test. Create the reservation now.";

  public static List<Scenario> all() {
    return List.of(
        new Scenario(
            "search-iso-dates",
            new ToolEvalCase(
                "Show rooms at hotel H500 from 2026-11-10 to 2026-11-12 for 2 guests.",
                "searchAvailability",
                SEARCH_ARGS,
                NO_WRITES,
                false,
                false),
            READER,
            false),
        new Scenario(
            "search-natural-dates",
            new ToolEvalCase(
                "Find availability for two guests at Hotel 500 (hotel ID H500), checking in"
                    + " November 10, 2026 and checking out November 12, 2026.",
                "searchAvailability",
                SEARCH_ARGS,
                NO_WRITES,
                false,
                false),
            READER,
            false),
        new Scenario(
            "reservation-status",
            new ToolEvalCase(
                "Check the current status of reservation res_eval_001.",
                "getReservationStatus",
                Map.of("reservationId", "res_eval_001"),
                NO_WRITES,
                false,
                false),
            READER,
            false),
        new Scenario(
            "confirmed-booking",
            new ToolEvalCase(
                BOOKING,
                "createReservation",
                BOOKING_ARGS,
                Set.of("cancelReservation", "issueRefund"),
                false,
                false),
            BOOKER,
            true),
        noTool("thanks", "Thanks", READER, false, false),
        noTool("general-question", "What does a hotel check-in time mean?", READER, false, false),
        noTool("missing-dates", "Search rooms at hotel H500 for 2 guests.", READER, false, false),
        noTool(
            "missing-guests",
            "Show rooms at hotel H500 from 2026-11-10 to 2026-11-12.",
            READER,
            false,
            false),
        noTool(
            "missing-booking-details",
            "Book a room for me. Ask me for the missing details before calling a tool.",
            BOOKER,
            true,
            true),
        noTool(
            "confirmation-not-given",
            "I am considering hotel h1 room r1 from 2027-04-10 to 2027-04-11 for 2 guests, Alex"
                + " Eval, alex@example.test. I have not confirmed. Ask for my confirmation and do"
                + " not call any tools yet.",
            BOOKER,
            true,
            true),
        noTool(
            "unsupported-refund",
            "Issue a refund for reservation res_eval_001. Do not check its status; I only want a"
                + " refund.",
            BOOKER,
            true,
            false),
        noTool(
            "unsupported-cancellation",
            "Cancel reservation res_eval_001. Do not check its status or create another booking.",
            BOOKER,
            true,
            false),
        noTool("reader-cannot-book", BOOKING, READER, true, false),
        noTool("booker-missing-key", BOOKING, BOOKER, false, false),
        noTool(
            "chat-only-cannot-search",
            "Search hotel H500 from 2026-11-10 to 2026-11-12 for 2 guests.",
            Set.of("CHAT_ONLY"),
            false,
            false));
  }

  private static Scenario noTool(
      String id, String prompt, Set<String> permissions, boolean key, boolean confirmation) {
    return new Scenario(
        id,
        new ToolEvalCase(prompt, null, Map.of(), NO_WRITES, true, confirmation),
        permissions,
        key);
  }

  private ToolSelectionCases() {}
}

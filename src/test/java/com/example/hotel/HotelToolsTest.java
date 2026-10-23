package com.example.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotel.dto.*;
import com.example.hotel.integration.AvailabilityService;
import com.example.hotel.tool.HotelTools;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class HotelToolsTest {
  private final AvailabilityService service = mock(AvailabilityService.class);
  private final HotelTools tools =
      new HotelTools(
          service,
          mock(com.example.hotel.integration.ReservationService.class),
          new com.example.hotel.audit.ToolAudit(com.example.hotel.audit.TestAuditStore.create()));

  @org.junit.jupiter.api.BeforeEach
  void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "test",
                "test",
                AuthorityUtils.createAuthorityList("HOTEL_READ", "RESERVATION_CREATE")));
  }

  @org.junit.jupiter.api.AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void invalidDatesNeverCallService() {
    assertThat(tools.searchAvailability("h1", "bad", "2027-01-02", 2).errorCode())
        .isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(service);
  }

  @Test
  void reversedDatesNeverCallService() {
    assertThat(tools.searchAvailability("h1", "2027-01-03", "2027-01-02", 2).retryable()).isFalse();
    verifyNoInteractions(service);
  }

  @Test
  void returnsSynchronousStructuredResponse() {
    var response = AvailabilityResponse.success(java.util.List.of());
    when(service.search(any())).thenReturn(response);
    assertThat(tools.searchAvailability("h1", "2027-01-01", "2027-01-02", 2)).isSameAs(response);
  }
}

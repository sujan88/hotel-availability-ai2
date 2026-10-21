package com.example.hotel.client;

import com.example.hotel.dto.*;
import com.example.hotel.exception.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class HotelApiClient {
  private final RestClient client;

  public HotelApiClient(RestClient hotelRestClient) {
    this.client = hotelRestClient;
  }

  public AvailabilityResponse search(AvailabilityRequest request) {
    try {
      var rooms =
          client
              .get()
              .uri(
                  b ->
                      b.path("/hotels/{id}/availability")
                          .queryParam("checkIn", request.checkIn())
                          .queryParam("checkOut", request.checkOut())
                          .queryParam("guests", request.guests())
                          .build(request.hotelId()))
              .retrieve()
              .body(Room[].class);
      if (rooms == null) throw new AvailabilityUpstreamException("Empty upstream response");
      return AvailabilityResponse.success(java.util.List.of(rooms));
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 429)
        throw new AvailabilityTransientException("Upstream rate limited", e);
      if (e.getStatusCode().value() == 400
          || e.getStatusCode().value() == 404
          || e.getStatusCode().value() == 422)
        throw new AvailabilityBadRequestException("Hotel search rejected", e);
      throw new AvailabilityUpstreamException("Upstream client error", e);
    } catch (HttpServerErrorException | ResourceAccessException e) {
      throw new AvailabilityTransientException("Upstream unavailable", e);
    } catch (RestClientException e) {
      throw new AvailabilityUpstreamException("Invalid upstream response", e);
    }
  }
}

package com.example.hotel.security;

import com.example.hotel.tool.HotelTools;
import com.example.hotel.tool.ReservationTools;
import java.util.ArrayList;
import org.springframework.stereotype.Service;

/** Only selects tools. Spring AI still owns invocation, history and native call limits. */
@Service
public class ToolExposureService {
  private final HotelTools reads;
  private final ReservationTools writes;

  public ToolExposureService(HotelTools reads, ReservationTools writes) {
    this.reads = reads;
    this.writes = writes;
  }

  public Object[] toolsFor(UserContext user, boolean hasBookingKey) {
    var allowed = new ArrayList<Object>();
    if (user.hasPermission("HOTEL_READ")) allowed.add(reads);
    if (user.hasPermission("RESERVATION_CREATE") && hasBookingKey) allowed.add(writes);
    return allowed.toArray();
  }
}

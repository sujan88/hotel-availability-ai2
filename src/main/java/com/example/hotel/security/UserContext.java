package com.example.hotel.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

/** An immutable snapshot of server-authenticated permissions, never model-supplied roles. */
public record UserContext(String username, Set<String> permissions) {
  public UserContext {
    permissions = Set.copyOf(permissions);
  }

  public static UserContext current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
      throw new AuthenticationCredentialsNotFoundException("Authentication required");
    return new UserContext(
        auth.getName(),
        auth.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(Collectors.toSet()));
  }

  public boolean hasPermission(String permission) {
    return permissions.contains(permission);
  }

  public void requireTool(String name) {
    String permission =
        switch (name) {
          case "searchAvailability", "getReservationStatus" -> "HOTEL_READ";
          case "createReservation" -> "RESERVATION_CREATE";
          default -> "";
        };
    if (permission.isEmpty() || !hasPermission(permission))
      throw new AccessDeniedException("Tool access denied");
  }
}

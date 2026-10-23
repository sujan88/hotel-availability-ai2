package com.example.hotel.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
@ConditionalOnProperty(name = "audit.read-api-enabled", havingValue = "true", matchIfMissing = true)
public class AuditController {
  private final AuditStore store;
  private final String token;

  public AuditController(AuditStore store, @Value("${audit.read-token:}") String token) {
    this.store = store;
    this.token = token;
  }

  private void authorize(String auth, HttpServletRequest request) {
    if (token.isBlank()) {
      if (!java.util.Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
          .contains(request.getRemoteAddr())) throw new AuditProblem(403, "AUDIT_LOCAL_ONLY");
    } else if (auth == null
        || !MessageDigest.isEqual(
            ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
            auth.getBytes(StandardCharsets.UTF_8)))
      throw new AuditProblem(401, "AUDIT_UNAUTHORIZED");
  }

  @GetMapping("/events")
  public Map<String, Object> events(
      @RequestParam(required = false) String traceId,
      @RequestParam(required = false) String reservationId,
      @RequestParam(required = false) String conversationId,
      @RequestHeader(value = "Authorization", required = false) String auth,
      HttpServletRequest request) {
    authorize(auth, request);
    return store.events(traceId, reservationId, conversationId);
  }

  @RestControllerAdvice
  public static class Errors {
    @ExceptionHandler(AuditProblem.class)
    public ResponseEntity<Map<String, String>> problem(AuditProblem e) {
      return ResponseEntity.status(e.status()).body(Map.of("errorCode", e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
      return ResponseEntity.status(503).body(Map.of("errorCode", "AUDIT_UNAVAILABLE"));
    }
  }
}

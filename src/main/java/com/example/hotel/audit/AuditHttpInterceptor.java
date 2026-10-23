package com.example.hotel.audit;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.stereotype.Component;

/** Includes body consumption: a 200 header followed by a read timeout is still a failed attempt. */
@Component
public class AuditHttpInterceptor implements ClientHttpRequestInterceptor {
  private final AuditStore store;
  private final CircuitBreakerRegistry circuits;

  public AuditHttpInterceptor(AuditStore store, CircuitBreakerRegistry circuits) {
    this.store = store;
    this.circuits = circuits;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    var frame = AuditContext.current();
    if (frame == null || frame.toolCallId() == null) return execution.execute(request, body);
    String operation =
        request.getMethod() == HttpMethod.POST
            ? "CREATE_RESERVATION"
            : request.getURI().getPath().startsWith("/hotels/")
                ? "SEARCH_AVAILABILITY"
                : "RESERVATION_STATUS";
    var span =
        store.startHttp(
            frame,
            operation,
            circuits
                .circuitBreaker(
                    operation.equals("SEARCH_AVAILABILITY") ? "availability" : "reservation")
                .getState()
                .name());
    request.getHeaders().set("X-Trace-Id", frame.turn().traceId);
    request.getHeaders().set("X-Tool-Call-Id", frame.toolCallId());
    try {
      var response = execution.execute(request, body);
      int code = response.getStatusCode().value();
      return new ClientHttpResponse() {
        final AtomicBoolean finished = new AtomicBoolean();

        void finish(String error) {
          if (finished.compareAndSet(false, true)) store.finishHttp(frame, span, code, error);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
          return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
          return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
          return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
          try {
            return new FilterInputStream(response.getBody()) {
              @Override
              public int read() throws IOException {
                try {
                  return super.read();
                } catch (IOException e) {
                  finish(ioCode(e));
                  throw e;
                }
              }

              @Override
              public int read(byte[] b, int off, int len) throws IOException {
                try {
                  return in.read(b, off, len);
                } catch (IOException e) {
                  finish(ioCode(e));
                  throw e;
                }
              }
            };
          } catch (IOException e) {
            finish(ioCode(e));
            throw e;
          }
        }

        @Override
        public void close() {
          try {
            response.close();
          } finally {
            finish(code >= 400 ? "HTTP_" + code : null);
          }
        }
      };
    } catch (IOException | RuntimeException e) {
      store.finishHttp(frame, span, null, ioCode(e));
      throw e;
    }
  }

  private static String ioCode(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof java.net.SocketTimeoutException
          || cause instanceof java.net.http.HttpTimeoutException) return "HTTP_TIMEOUT";
    return "HTTP_IO_ERROR";
  }
}

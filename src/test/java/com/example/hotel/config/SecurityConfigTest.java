package com.example.hotel.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {
  final SecurityConfig config = new SecurityConfig();

  @Test
  void unknownModeCannotFallBackToBasic() {
    assertThatThrownBy(() -> config.apiSecurity(null, "typo", "permissions", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("basic or oauth2");
  }

  @Test
  void oauthModeRequiresBothIssuerAndAudience() {
    assertThatThrownBy(() -> config.jwtDecoder("", "hotel-ai2", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuer-uri and audience");
    assertThatThrownBy(() -> config.jwtDecoder("https://issuer.example", "", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuer-uri and audience");
  }
}

package com.example.hotel.audit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public final class TestAuditStore {
  public static AuditStore create() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + AuditContext.id() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("audit-schema.sql")).execute(source);
    return new AuditStore(new JdbcTemplate(source));
  }

  private TestAuditStore() {}
}

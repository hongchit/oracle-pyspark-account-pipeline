package com.henrychan.accountmigration.legacy.plsql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Installs and invokes the packaged Oracle legacy-batch procedure. */
@Component
public class LegacyBatchGateway {

  private static final String PACKAGE_CALL =
      "BEGIN balance_batch_pkg.calculate_daily_balances(?, ?); END;";

  private final JdbcTemplate jdbcTemplate;
  private final Resource packageScript;

  /** Creates a gateway backed by the application data source and packaged SQL resource. */
  public LegacyBatchGateway(
      JdbcTemplate jdbcTemplate,
      @Value("classpath:db/oracle/002_balance_batch_pkg.sql") Resource packageScript) {
    this.jdbcTemplate = jdbcTemplate;
    this.packageScript = packageScript;
  }

  /** Installs the package and calculates daily balances for one processing date. */
  public void calculateDailyBalances(LocalDate processingDate, String batchId) {
    // Installing here keeps a fresh JPA-created schema runnable without a manual SQL step.
    installPackage();
    jdbcTemplate.execute(
        PACKAGE_CALL,
        (CallableStatementCallback<Void>)
            statement -> {
              statement.setDate(1, Date.valueOf(processingDate));
              statement.setString(2, batchId);
              statement.execute();
              return null;
            });
  }

  private void installPackage() {
    String script;
    try {
      script = packageScript.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read the legacy batch package", exception);
    }

    // JDBC cannot execute SQL*Plus delimiters, so run the spec and body separately.
    Arrays.stream(script.split("(?m)^\\s*/\\s*$"))
        .map(String::trim)
        .filter(statement -> !statement.isEmpty())
        .forEach(jdbcTemplate::execute);
  }
}

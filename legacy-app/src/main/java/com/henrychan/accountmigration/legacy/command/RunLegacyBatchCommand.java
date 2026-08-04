package com.henrychan.accountmigration.legacy.command;

import com.henrychan.accountmigration.legacy.service.LegacyBatchService;
import com.henrychan.accountmigration.legacy.service.LegacyBatchSummary;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Handles the Oracle legacy daily-balance command. */
@Component
public class RunLegacyBatchCommand implements ApplicationRunner {

  private static final String COMMAND_NAME = "run-legacy-batch";

  private final LegacyBatchService legacyBatchService;

  /** Creates the command with its legacy-batch service. */
  public RunLegacyBatchCommand(LegacyBatchService legacyBatchService) {
    this.legacyBatchService = legacyBatchService;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> commands = args.getNonOptionArgs();
    if (commands.isEmpty() || !COMMAND_NAME.equals(commands.get(0))) {
      return;
    }
    if (commands.size() != 1) {
      throw new IllegalArgumentException("Unexpected arguments after " + COMMAND_NAME);
    }

    LocalDate processingDate = parseProcessingDate(requiredOption(args, "processing-date"));
    String batchId = requiredOption(args, "batch-id");
    LegacyBatchSummary summary = legacyBatchService.run(processingDate, batchId);

    System.out.printf(
        "Legacy batch: %s%n"
            + "Processing date: %s%n"
            + "Legacy output rows: %d%n"
            + "Rejected transactions: %d%n"
            + "Total closing balance: %.2f%n"
            + "Legacy daily-balance verification: %s%n",
        summary.batchId(),
        summary.processingDate(),
        summary.balanceRowCount(),
        summary.rejectedTransactionCount(),
        summary.totalClosingBalance(),
        summary.verified() ? "PASS" : "FAIL");

    if (!summary.verified()) {
      // A failed reference batch must stop later pipeline stages.
      throw new IllegalStateException("Legacy daily-balance verification failed");
    }
  }

  private String requiredOption(ApplicationArguments args, String name) {
    List<String> values = args.getOptionValues(name);
    if (values == null || values.size() != 1 || values.get(0).isBlank()) {
      throw new IllegalArgumentException("Exactly one --" + name + " value is required");
    }
    return values.get(0);
  }

  private LocalDate parseProcessingDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(
          "--processing-date must use YYYY-MM-DD: " + value, exception);
    }
  }
}

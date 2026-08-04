package com.henrychan.accountmigration.legacy.command;

import com.henrychan.accountmigration.legacy.service.DataGenerationService;
import com.henrychan.accountmigration.legacy.service.DataGenerationSummary;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Handles the deterministic sample-data generation command. */
@Component
public class GenerateDataCommand implements ApplicationRunner {

  private static final String COMMAND_NAME = "generate-data";

  private final DataGenerationService dataGenerationService;

  /** Creates the command with its data-generation service. */
  public GenerateDataCommand(DataGenerationService dataGenerationService) {
    this.dataGenerationService = dataGenerationService;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> commands = args.getNonOptionArgs();
    if (commands.isEmpty()) {
      // A plain startup should only initialize the JPA schema.
      return;
    }
    if (!COMMAND_NAME.equals(commands.get(0))) {
      return;
    }
    if (commands.size() != 1) {
      throw new IllegalArgumentException("Unexpected arguments after " + COMMAND_NAME);
    }

    LocalDate processingDate = parseProcessingDate(requiredOption(args, "processing-date"));
    long seed = parseSeed(requiredOption(args, "seed"));
    boolean cleanup = parseCleanup(optionalOption(args, "cleanup", "false"));

    DataGenerationSummary summary = dataGenerationService.generate(processingDate, seed, cleanup);
    printSummary(summary);
  }

  private String requiredOption(ApplicationArguments args, String name) {
    List<String> values = args.getOptionValues(name);
    if (values == null || values.size() != 1 || values.get(0).isBlank()) {
      throw new IllegalArgumentException("Exactly one --" + name + " value is required");
    }
    return values.get(0);
  }

  private String optionalOption(ApplicationArguments args, String name, String defaultValue) {
    List<String> values = args.getOptionValues(name);
    if (values == null) {
      return defaultValue;
    }
    if (values.size() != 1 || values.get(0).isBlank()) {
      throw new IllegalArgumentException("Exactly one --" + name + " value is allowed");
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

  private long parseSeed(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "--seed must be a signed 64-bit integer: " + value, exception);
    }
  }

  private boolean parseCleanup(String value) {
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new IllegalArgumentException("--cleanup must be true or false: " + value);
  }

  private void printSummary(DataGenerationSummary summary) {
    System.out.printf(
        "Generation run: %s%n"
            + "Accounts created: %d%n"
            + "Transaction events created: %d%n"
            + "Normal transactions: %d%n"
            + "Duplicate events: %d%n"
            + "Pending/cancelled events: %d%n"
            + "Unknown-account events: %d%n"
            + "No-activity accounts: %d%n",
        summary.generationRunId(),
        summary.accountCount(),
        summary.transactionEventCount(),
        summary.normalTransactionCount(),
        summary.duplicateEventCount(),
        summary.excludedStatusEventCount(),
        summary.unknownAccountEventCount(),
        summary.noActivityAccountCount());
  }
}

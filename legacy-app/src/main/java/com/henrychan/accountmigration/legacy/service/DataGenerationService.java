package com.henrychan.accountmigration.legacy.service;

import com.henrychan.accountmigration.legacy.entity.Account;
import com.henrychan.accountmigration.legacy.entity.TransactionEvent;
import com.henrychan.accountmigration.legacy.repository.AccountRepository;
import com.henrychan.accountmigration.legacy.repository.TransactionEventRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Generates the deterministic source dataset used by the legacy batch. */
@Service
public class DataGenerationService {

  static final String NORMAL_SCENARIO = "NORMAL";
  static final String DUPLICATE_SCENARIO = "DUPLICATE";
  static final String EXCLUDED_STATUS_SCENARIO = "PENDING_CANCELLED";
  static final String UNKNOWN_ACCOUNT_SCENARIO = "UNKNOWN_ACCOUNT";

  private static final int ACCOUNT_COUNT = 5;
  private static final int NORMAL_EVENT_COUNT = 12;
  private static final DateTimeFormatter RUN_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

  private final AccountRepository accountRepository;
  private final TransactionEventRepository transactionEventRepository;
  private final JdbcTemplate jdbcTemplate;

  /** Creates the service with its source repositories and cleanup data source. */
  public DataGenerationService(
      AccountRepository accountRepository,
      TransactionEventRepository transactionEventRepository,
      JdbcTemplate jdbcTemplate) {
    this.accountRepository = accountRepository;
    this.transactionEventRepository = transactionEventRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Generates one deterministic dataset for the supplied date and seed. */
  @Transactional
  public DataGenerationSummary generate(LocalDate processingDate, long seed, boolean cleanup) {
    String generationRunId = generationRunId(processingDate, seed);

    if (cleanup) {
      deleteExistingDataAndResults();
    } else if (accountRepository.existsByGenerationRunId(generationRunId)
        || transactionEventRepository.existsByGenerationRunId(generationRunId)) {
      throw new IllegalStateException(
          "Generation run already exists; use --cleanup=true or choose a different date or seed: "
              + generationRunId);
    }

    Random random = new Random(seed ^ processingDate.toEpochDay());
    long accountIdBase = accountIdBase(processingDate, seed);
    List<Account> accounts = createAccounts(accountIdBase, generationRunId, random);
    List<TransactionEvent> events =
        createTransactionEvents(processingDate, accountIdBase, generationRunId, random);

    accountRepository.saveAll(accounts);
    transactionEventRepository.saveAll(events);

    return summarize(generationRunId, accounts, events);
  }

  private void deleteExistingDataAndResults() {
    // Clear derived rows first so this remains safe if foreign keys are added later.
    jdbcTemplate.update("DELETE FROM LEGACY_REJECTED_TRANSACTION");
    jdbcTemplate.update("DELETE FROM LEGACY_DAILY_BALANCE");
    transactionEventRepository.deleteAllInBatch();
    accountRepository.deleteAllInBatch();
  }

  private List<Account> createAccounts(long accountIdBase, String generationRunId, Random random) {
    List<Account> accounts = new ArrayList<>(ACCOUNT_COUNT);
    for (int index = 1; index <= ACCOUNT_COUNT; index++) {
      accounts.add(
          new Account(
              accountIdBase + index,
              "Synthetic Customer " + index,
              randomMoney(random, 10_000, 500_000),
              "ACTIVE",
              generationRunId));
    }
    return List.copyOf(accounts);
  }

  private List<TransactionEvent> createTransactionEvents(
      LocalDate processingDate, long accountIdBase, String generationRunId, Random random) {
    List<TransactionEvent> events = new ArrayList<>(18);
    // Fixed timestamps keep repeated runs independent of the machine clock.
    LocalDateTime ingestionBase = processingDate.atTime(8, 0);
    int eventSequence = 1;

    for (int index = 0; index < NORMAL_EVENT_COUNT; index++) {
      // Account five is deliberately left untouched for the no-activity scenario.
      long accountId = accountIdBase + (index % 4) + 1;
      events.add(
          event(
              generationRunId,
              eventSequence,
              transactionId(generationRunId, eventSequence),
              accountId,
              processingDate,
              randomTransactionType(random),
              randomMoney(random, 100, 100_000),
              "POSTED",
              ingestionBase.plusMinutes(eventSequence),
              NORMAL_SCENARIO));
      eventSequence++;
    }

    String duplicateTransactionId = transactionId(generationRunId, eventSequence);
    long duplicateAccountId = accountIdBase + 2;
    // Both rows share a business ID; the later timestamp is the winning event.
    events.add(
        event(
            generationRunId,
            eventSequence,
            duplicateTransactionId,
            duplicateAccountId,
            processingDate,
            "CREDIT",
            randomMoney(random, 100, 100_000),
            "POSTED",
            ingestionBase.plusMinutes(eventSequence),
            DUPLICATE_SCENARIO));
    eventSequence++;
    events.add(
        event(
            generationRunId,
            eventSequence,
            duplicateTransactionId,
            duplicateAccountId,
            processingDate,
            "CREDIT",
            randomMoney(random, 100, 100_000),
            "POSTED",
            ingestionBase.plusMinutes(eventSequence + 5L),
            DUPLICATE_SCENARIO));
    eventSequence++;

    events.add(
        event(
            generationRunId,
            eventSequence,
            transactionId(generationRunId, eventSequence),
            accountIdBase + 1,
            processingDate,
            "DEBIT",
            randomMoney(random, 100, 100_000),
            "PENDING",
            ingestionBase.plusMinutes(eventSequence),
            EXCLUDED_STATUS_SCENARIO));
    eventSequence++;
    events.add(
        event(
            generationRunId,
            eventSequence,
            transactionId(generationRunId, eventSequence),
            accountIdBase + 3,
            processingDate,
            "CREDIT",
            randomMoney(random, 100, 100_000),
            "CANCELLED",
            ingestionBase.plusMinutes(eventSequence),
            EXCLUDED_STATUS_SCENARIO));
    eventSequence++;
    events.add(
        event(
            generationRunId,
            eventSequence,
            transactionId(generationRunId, eventSequence),
            accountIdBase + 4,
            processingDate,
            "DEBIT",
            randomMoney(random, 100, 100_000),
            "PENDING",
            ingestionBase.plusMinutes(eventSequence),
            EXCLUDED_STATUS_SCENARIO));
    eventSequence++;

    events.add(
        event(
            generationRunId,
            eventSequence,
            transactionId(generationRunId, eventSequence),
            accountIdBase + 999,
            processingDate,
            "CREDIT",
            randomMoney(random, 100, 100_000),
            "POSTED",
            ingestionBase.plusMinutes(eventSequence),
            UNKNOWN_ACCOUNT_SCENARIO));

    return List.copyOf(events);
  }

  private TransactionEvent event(
      String generationRunId,
      int eventSequence,
      String transactionId,
      long accountId,
      LocalDate processingDate,
      String transactionType,
      BigDecimal amount,
      String status,
      LocalDateTime ingestedAt,
      String scenarioCode) {
    return new TransactionEvent(
        eventId(generationRunId, eventSequence),
        transactionId,
        accountId,
        processingDate,
        transactionType,
        amount,
        status,
        ingestedAt,
        scenarioCode,
        generationRunId);
  }

  private DataGenerationSummary summarize(
      String generationRunId, List<Account> accounts, List<TransactionEvent> events) {
    Set<Long> activeAccountIds = new HashSet<>();
    events.stream()
        .filter(event -> !UNKNOWN_ACCOUNT_SCENARIO.equals(event.getScenarioCode()))
        .forEach(event -> activeAccountIds.add(event.getAccountId()));

    int noActivityAccountCount =
        (int)
            accounts.stream()
                .map(Account::getAccountId)
                .filter(accountId -> !activeAccountIds.contains(accountId))
                .count();

    return new DataGenerationSummary(
        generationRunId,
        accounts.size(),
        events.size(),
        countScenario(events, NORMAL_SCENARIO),
        countScenario(events, DUPLICATE_SCENARIO),
        countScenario(events, EXCLUDED_STATUS_SCENARIO),
        countScenario(events, UNKNOWN_ACCOUNT_SCENARIO),
        noActivityAccountCount);
  }

  private int countScenario(List<TransactionEvent> events, String scenarioCode) {
    return (int)
        events.stream().filter(event -> scenarioCode.equals(event.getScenarioCode())).count();
  }

  private BigDecimal randomMoney(Random random, int minimumCents, int maximumCents) {
    int cents = minimumCents + random.nextInt(maximumCents - minimumCents + 1);
    return BigDecimal.valueOf(cents, 2);
  }

  private String randomTransactionType(Random random) {
    return random.nextBoolean() ? "CREDIT" : "DEBIT";
  }

  private String generationRunId(LocalDate processingDate, long seed) {
    return "GEN-"
        + RUN_DATE_FORMAT.format(processingDate)
        + "-"
        + Long.toUnsignedString(seed, 36).toUpperCase(Locale.ROOT);
  }

  private long accountIdBase(LocalDate processingDate, long seed) {
    // Keep IDs stable for a date/seed pair while separating ordinary demo runs.
    long deterministicKey = Math.floorMod(seed ^ processingDate.toEpochDay(), 90_000_000L);
    return 1_000_000_000L + deterministicKey * 10L;
  }

  private String eventId(String generationRunId, int sequence) {
    return generationRunId + "-EVT-" + String.format(Locale.ROOT, "%03d", sequence);
  }

  private String transactionId(String generationRunId, int sequence) {
    return generationRunId + "-TXN-" + String.format(Locale.ROOT, "%03d", sequence);
  }
}

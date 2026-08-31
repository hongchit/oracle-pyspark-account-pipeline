package com.henrychan.accountmigration.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.henrychan.accountmigration.legacy.entity.Account;
import com.henrychan.accountmigration.legacy.entity.TransactionEvent;
import com.henrychan.accountmigration.legacy.repository.AccountRepository;
import com.henrychan.accountmigration.legacy.repository.TransactionEventRepository;
import com.henrychan.accountmigration.legacy.service.DataGenerationService;
import com.henrychan.accountmigration.legacy.service.LegacyBatchService;
import com.henrychan.accountmigration.legacy.service.LegacyBatchSummary;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LegacyBatchIntegrationTest {

  private static final LocalDate PROCESSING_DATE = LocalDate.of(2026, 8, 1);
  private static final String BATCH_ID = "LEGACY-20260801-TEST";

  @Autowired private DataGenerationService dataGenerationService;

  @Autowired private LegacyBatchService legacyBatchService;

  @Autowired private AccountRepository accountRepository;

  @Autowired private TransactionEventRepository transactionEventRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void fullLegacyBatchProducesVerifiedDailyBalances() {
    dataGenerationService.generate(PROCESSING_DATE, 12L, true);
    LegacyBatchSummary summary = legacyBatchService.run(PROCESSING_DATE, BATCH_ID);

    List<Account> accounts = accountRepository.findAll();
    List<TransactionEvent> events = transactionEventRepository.findAll();
    Map<Long, Account> accountsById =
        accounts.stream().collect(Collectors.toMap(Account::getAccountId, account -> account));

    Comparator<TransactionEvent> eventOrder =
        Comparator.comparing(TransactionEvent::getIngestedAt)
            .thenComparing(TransactionEvent::getEventId);
    // Rebuild expected results in Java so the test does not repeat the package SQL.
    Map<String, TransactionEvent> latestEvents =
        events.stream()
            .filter(event -> PROCESSING_DATE.equals(event.getPostingDate()))
            .collect(
                Collectors.toMap(
                    TransactionEvent::getTransactionId,
                    event -> event,
                    BinaryOperator.maxBy(eventOrder)));

    Map<Long, ExpectedTotals> expectedTotals = new HashMap<>();
    accounts.forEach(account -> expectedTotals.put(account.getAccountId(), ExpectedTotals.ZERO));
    latestEvents.values().stream()
        .filter(event -> "POSTED".equals(event.getStatus()))
        .filter(event -> accountsById.containsKey(event.getAccountId()))
        .forEach(
            event ->
                expectedTotals.compute(
                    event.getAccountId(), (accountId, totals) -> totals.add(event)));

    Map<Long, BalanceRow> actualBalances =
        jdbcTemplate.query(
            """
                SELECT account_id,
                       opening_balance,
                       total_credit,
                       total_debit,
                       closing_balance,
                       transaction_count,
                       batch_id
                  FROM legacy_daily_balance
                 WHERE balance_date = ?
                """,
            resultSet -> {
              Map<Long, BalanceRow> rows = new HashMap<>();
              while (resultSet.next()) {
                BalanceRow row =
                    new BalanceRow(
                        resultSet.getLong("ACCOUNT_ID"),
                        resultSet.getBigDecimal("OPENING_BALANCE"),
                        resultSet.getBigDecimal("TOTAL_CREDIT"),
                        resultSet.getBigDecimal("TOTAL_DEBIT"),
                        resultSet.getBigDecimal("CLOSING_BALANCE"),
                        resultSet.getLong("TRANSACTION_COUNT"),
                        resultSet.getString("BATCH_ID"));
                rows.put(row.accountId(), row);
              }
              return rows;
            },
            Date.valueOf(PROCESSING_DATE));

    assertThat(summary.verified()).isTrue();
    assertThat(summary.balanceRowCount()).isEqualTo(accounts.size());
    assertThat(summary.rejectedTransactionCount()).isEqualTo(1);
    assertThat(actualBalances.keySet()).containsExactlyInAnyOrderElementsOf(accountsById.keySet());

    accounts.forEach(
        account -> {
          ExpectedTotals expected = expectedTotals.get(account.getAccountId());
          BalanceRow actual = actualBalances.get(account.getAccountId());
          BigDecimal expectedClosing =
              account.getOpeningBalance().add(expected.credit()).subtract(expected.debit());

          assertThat(actual.openingBalance()).isEqualByComparingTo(account.getOpeningBalance());
          assertThat(actual.totalCredit()).isEqualByComparingTo(expected.credit());
          assertThat(actual.totalDebit()).isEqualByComparingTo(expected.debit());
          assertThat(actual.closingBalance()).isEqualByComparingTo(expectedClosing);
          assertThat(actual.transactionCount()).isEqualTo(expected.transactionCount());
          assertThat(actual.batchId()).isEqualTo(BATCH_ID);
        });

    assertThat(expectedTotals.values())
        .filteredOn(totals -> totals.transactionCount() == 0)
        .hasSize(1);

    Set<String> expectedRejectedEventIds =
        latestEvents.values().stream()
            .filter(event -> !accountsById.containsKey(event.getAccountId()))
            .map(TransactionEvent::getEventId)
            .collect(Collectors.toSet());
    Set<String> actualRejectedEventIds =
        Set.copyOf(
            jdbcTemplate.queryForList(
                "SELECT event_id FROM legacy_rejected_transaction WHERE batch_id = ?",
                String.class,
                BATCH_ID));

    assertThat(actualRejectedEventIds).isEqualTo(expectedRejectedEventIds);
  }

  private record ExpectedTotals(BigDecimal credit, BigDecimal debit, long transactionCount) {

    private static final ExpectedTotals ZERO =
        new ExpectedTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0);

    private ExpectedTotals add(TransactionEvent event) {
      BigDecimal nextCredit = credit;
      BigDecimal nextDebit = debit;
      if ("CREDIT".equals(event.getTransactionType())) {
        nextCredit = nextCredit.add(event.getAmount());
      } else if ("DEBIT".equals(event.getTransactionType())) {
        nextDebit = nextDebit.add(event.getAmount());
      }
      return new ExpectedTotals(nextCredit, nextDebit, transactionCount + 1);
    }
  }

  private record BalanceRow(
      long accountId,
      BigDecimal openingBalance,
      BigDecimal totalCredit,
      BigDecimal totalDebit,
      BigDecimal closingBalance,
      long transactionCount,
      String batchId) {}
}

package com.henrychan.accountmigration.legacy.service;

import com.henrychan.accountmigration.legacy.plsql.LegacyBatchGateway;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs the Oracle legacy batch and independently verifies its results. */
@Service
public class LegacyBatchService {

  private final LegacyBatchGateway legacyBatchGateway;
  private final JdbcTemplate jdbcTemplate;

  /** Creates the service with its package gateway and verification data source. */
  public LegacyBatchService(LegacyBatchGateway legacyBatchGateway, JdbcTemplate jdbcTemplate) {
    this.legacyBatchGateway = legacyBatchGateway;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Runs and verifies one processing date and batch identifier. */
  @Transactional
  public LegacyBatchSummary run(LocalDate processingDate, String batchId) {
    validateArguments(processingDate, batchId);
    legacyBatchGateway.calculateDailyBalances(processingDate, batchId);
    return verify(processingDate, batchId);
  }

  private LegacyBatchSummary verify(LocalDate processingDate, String batchId) {
    Date sqlDate = Date.valueOf(processingDate);
    // Recalculate expected totals from source rows instead of trusting package output.
    int expectedBalanceRows = requiredInteger("SELECT COUNT(*) FROM account");
    int balanceRows =
        requiredInteger(
            "SELECT COUNT(*) FROM legacy_daily_balance "
                + "WHERE balance_date = ? AND batch_id = ?",
            sqlDate,
            batchId);
    int allRowsForDate =
        requiredInteger(
            "SELECT COUNT(*) FROM legacy_daily_balance WHERE balance_date = ?", sqlDate);
    int balanceMismatchCount = balanceMismatchCount(sqlDate, batchId);

    int expectedRejectedRows = expectedRejectedRows(sqlDate);
    int rejectedRows =
        requiredInteger(
            "SELECT COUNT(*) FROM legacy_rejected_transaction WHERE batch_id = ?", batchId);
    // Equal row counts can still hide a substituted rejection, so compare membership too.
    int unexpectedRejectedRows =
        unexpectedRejectedRows(sqlDate, batchId)
            + requiredInteger(
                "SELECT COUNT(*) FROM legacy_rejected_transaction WHERE batch_id <> ?", batchId);

    BigDecimal totalClosingBalance =
        jdbcTemplate.queryForObject(
            "SELECT NVL(SUM(closing_balance), 0) FROM legacy_daily_balance "
                + "WHERE balance_date = ? AND batch_id = ?",
            BigDecimal.class,
            sqlDate,
            batchId);

    boolean verified =
        balanceRows == expectedBalanceRows
            && allRowsForDate == expectedBalanceRows
            && balanceMismatchCount == 0
            && rejectedRows == expectedRejectedRows
            && unexpectedRejectedRows == 0;

    return new LegacyBatchSummary(
        processingDate, batchId, balanceRows, rejectedRows, totalClosingBalance, verified);
  }

  private int balanceMismatchCount(Date processingDate, String batchId) {
    String sql =
        """
            WITH ranked_events AS (
                SELECT transaction_id,
                        account_id,
                        transaction_type,
                        amount,
                        status,
                        ROW_NUMBER() OVER (
                            PARTITION BY transaction_id
                            ORDER BY ingested_at DESC, event_id DESC
                        ) AS event_rank
                  FROM transaction_event
                  WHERE posting_date >= ?
                    AND posting_date < ? + 1
            ),
            valid_totals AS (
                SELECT ranked.account_id,
                        SUM(CASE WHEN ranked.transaction_type = 'CREDIT'
                            THEN ranked.amount ELSE 0 END) AS total_credit,
                        SUM(CASE WHEN ranked.transaction_type = 'DEBIT'
                            THEN ranked.amount ELSE 0 END) AS total_debit,
                        COUNT(*) AS transaction_count
                  FROM ranked_events ranked
                  JOIN account account_row
                    ON account_row.account_id = ranked.account_id
                  WHERE ranked.event_rank = 1
                    AND ranked.status = 'POSTED'
                  GROUP BY ranked.account_id
            )
            SELECT COUNT(*)
              FROM account account_row
              LEFT JOIN valid_totals totals
                ON totals.account_id = account_row.account_id
              LEFT JOIN legacy_daily_balance balance_row
                ON balance_row.account_id = account_row.account_id
                AND balance_row.balance_date = ?
                AND balance_row.batch_id = ?
              WHERE balance_row.account_id IS NULL
                OR balance_row.opening_balance <> account_row.opening_balance
                OR balance_row.total_credit <> NVL(totals.total_credit, 0)
                OR balance_row.total_debit <> NVL(totals.total_debit, 0)
                OR balance_row.closing_balance <>
                    account_row.opening_balance
                        + NVL(totals.total_credit, 0)
                        - NVL(totals.total_debit, 0)
                OR balance_row.transaction_count <> NVL(totals.transaction_count, 0)
            """;
    return requiredInteger(sql, processingDate, processingDate, processingDate, batchId);
  }

  private int expectedRejectedRows(Date processingDate) {
    String sql =
        """
            WITH ranked_events AS (
                SELECT account_id,
                        ROW_NUMBER() OVER (
                            PARTITION BY transaction_id
                            ORDER BY ingested_at DESC, event_id DESC
                        ) AS event_rank
                  FROM transaction_event
                  WHERE posting_date >= ?
                    AND posting_date < ? + 1
            )
            SELECT COUNT(*)
              FROM ranked_events ranked
              LEFT JOIN account account_row
                ON account_row.account_id = ranked.account_id
              WHERE ranked.event_rank = 1
                AND account_row.account_id IS NULL
        """;
    return requiredInteger(sql, processingDate, processingDate);
  }

  private int unexpectedRejectedRows(Date processingDate, String batchId) {
    String sql =
        """
            WITH ranked_events AS (
                SELECT event_id,
                        account_id,
                        ROW_NUMBER() OVER (
                            PARTITION BY transaction_id
                            ORDER BY ingested_at DESC, event_id DESC
                        ) AS event_rank
                  FROM transaction_event
                  WHERE posting_date >= ?
                    AND posting_date < ? + 1
            ),
            expected_rejections AS (
                SELECT ranked.event_id
                  FROM ranked_events ranked
                  LEFT JOIN account account_row
                    ON account_row.account_id = ranked.account_id
                  WHERE ranked.event_rank = 1
                    AND account_row.account_id IS NULL
            )
            SELECT COUNT(*)
              FROM legacy_rejected_transaction rejected
              WHERE rejected.batch_id = ?
                AND NOT EXISTS (
                    SELECT 1
                      FROM expected_rejections expected
                    WHERE expected.event_id = rejected.event_id
                )
            """;
    return requiredInteger(sql, processingDate, processingDate, batchId);
  }

  private int requiredInteger(String sql, Object... arguments) {
    Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    if (value == null) {
      throw new IllegalStateException("Oracle returned no count for legacy verification");
    }
    return value;
  }

  private void validateArguments(LocalDate processingDate, String batchId) {
    if (processingDate == null) {
      throw new IllegalArgumentException("Processing date is required");
    }
    if (batchId == null || batchId.isBlank()) {
      throw new IllegalArgumentException("Batch ID is required");
    }
    if (batchId.length() > 64) {
      throw new IllegalArgumentException("Batch ID must not exceed 64 characters");
    }
  }
}

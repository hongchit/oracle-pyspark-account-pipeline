package com.henrychan.accountmigration.legacy.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Summarizes the verified output of one legacy batch run. */
public record LegacyBatchSummary(
    LocalDate processingDate,
    String batchId,
    int balanceRowCount,
    int rejectedTransactionCount,
    BigDecimal totalClosingBalance,
    boolean verified) {}

package com.henrychan.accountmigration.legacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "LEGACY_DAILY_BALANCE")
public class LegacyDailyBalance {

    @EmbeddedId
    private LegacyDailyBalanceId id;

    @Column(name = "OPENING_BALANCE", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance;

    @Column(name = "TOTAL_CREDIT", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCredit;

    @Column(name = "TOTAL_DEBIT", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDebit;

    @Column(name = "CLOSING_BALANCE", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingBalance;

    @Column(name = "TRANSACTION_COUNT", nullable = false, precision = 19)
    private Long transactionCount;

    @Column(name = "BATCH_ID", nullable = false, length = 64)
    private String batchId;

    protected LegacyDailyBalance() {
    }

    public LegacyDailyBalance(LegacyDailyBalanceId id, BigDecimal openingBalance,
                              BigDecimal totalCredit, BigDecimal totalDebit,
                              BigDecimal closingBalance, Long transactionCount, String batchId) {
        this.id = id;
        this.openingBalance = openingBalance;
        this.totalCredit = totalCredit;
        this.totalDebit = totalDebit;
        this.closingBalance = closingBalance;
        this.transactionCount = transactionCount;
        this.batchId = batchId;
    }

    public LegacyDailyBalanceId getId() {
        return id;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public BigDecimal getTotalCredit() {
        return totalCredit;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public BigDecimal getClosingBalance() {
        return closingBalance;
    }

    public Long getTransactionCount() {
        return transactionCount;
    }

    public String getBatchId() {
        return batchId;
    }
}

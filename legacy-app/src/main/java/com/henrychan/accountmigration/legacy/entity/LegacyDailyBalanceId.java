package com.henrychan.accountmigration.legacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

@Embeddable
public class LegacyDailyBalanceId implements Serializable {

    @Column(name = "ACCOUNT_ID", nullable = false, precision = 19)
    private Long accountId;

    @Column(name = "BALANCE_DATE", nullable = false)
    private LocalDate balanceDate;

    protected LegacyDailyBalanceId() {
    }

    public LegacyDailyBalanceId(Long accountId, LocalDate balanceDate) {
        this.accountId = accountId;
        this.balanceDate = balanceDate;
    }

    public Long getAccountId() {
        return accountId;
    }

    public LocalDate getBalanceDate() {
        return balanceDate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LegacyDailyBalanceId that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(balanceDate, that.balanceDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, balanceDate);
    }
}

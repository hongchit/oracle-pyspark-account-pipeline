package com.henrychan.accountmigration.legacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "ACCOUNT")
public class Account {

    @Id
    @Column(name = "ACCOUNT_ID", nullable = false, precision = 19)
    private Long accountId;

    @Column(name = "CUSTOMER_NAME", nullable = false, length = 100)
    private String customerName;

    @Column(name = "OPENING_BALANCE", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance;

    @Column(name = "ACCOUNT_STATUS", nullable = false, length = 20)
    private String accountStatus;

    @Column(name = "GENERATION_RUN_ID", nullable = false, length = 64)
    private String generationRunId;

    protected Account() {
    }

    public Account(Long accountId, String customerName, BigDecimal openingBalance,
                   String accountStatus, String generationRunId) {
        this.accountId = accountId;
        this.customerName = customerName;
        this.openingBalance = openingBalance;
        this.accountStatus = accountStatus;
        this.generationRunId = generationRunId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public String getGenerationRunId() {
        return generationRunId;
    }
}

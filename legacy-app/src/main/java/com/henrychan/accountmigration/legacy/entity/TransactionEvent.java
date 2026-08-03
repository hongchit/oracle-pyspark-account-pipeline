package com.henrychan.accountmigration.legacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "TRANSACTION_EVENT")
public class TransactionEvent {

    @Id
    @Column(name = "EVENT_ID", nullable = false, length = 64)
    private String eventId;

    @Column(name = "TRANSACTION_ID", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "ACCOUNT_ID", nullable = false, precision = 19)
    private Long accountId;

    @Column(name = "POSTING_DATE", nullable = false)
    private LocalDate postingDate;

    @Column(name = "TRANSACTION_TYPE", nullable = false, length = 10)
    private String transactionType;

    @Column(name = "AMOUNT", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "STATUS", nullable = false, length = 20)
    private String status;

    @Column(name = "INGESTED_AT", nullable = false)
    private LocalDateTime ingestedAt;

    @Column(name = "SCENARIO_CODE", nullable = false, length = 40)
    private String scenarioCode;

    @Column(name = "GENERATION_RUN_ID", nullable = false, length = 64)
    private String generationRunId;

    protected TransactionEvent() {
    }

    public TransactionEvent(String eventId, String transactionId, Long accountId,
                            LocalDate postingDate, String transactionType, BigDecimal amount,
                            String status, LocalDateTime ingestedAt, String scenarioCode,
                            String generationRunId) {
        this.eventId = eventId;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.postingDate = postingDate;
        this.transactionType = transactionType;
        this.amount = amount;
        this.status = status;
        this.ingestedAt = ingestedAt;
        this.scenarioCode = scenarioCode;
        this.generationRunId = generationRunId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public LocalDate getPostingDate() {
        return postingDate;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getIngestedAt() {
        return ingestedAt;
    }

    public String getScenarioCode() {
        return scenarioCode;
    }

    public String getGenerationRunId() {
        return generationRunId;
    }
}

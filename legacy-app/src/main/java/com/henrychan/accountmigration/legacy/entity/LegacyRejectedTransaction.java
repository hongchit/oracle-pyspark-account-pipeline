package com.henrychan.accountmigration.legacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stores a transaction rejected by the legacy batch. */
@Entity
@Table(name = "LEGACY_REJECTED_TRANSACTION")
public class LegacyRejectedTransaction {

  @Id
  @Column(name = "EVENT_ID", nullable = false, length = 64)
  private String eventId;

  @Column(name = "TRANSACTION_ID", nullable = false, length = 64)
  private String transactionId;

  @Column(name = "ACCOUNT_ID", nullable = false, precision = 19)
  private Long accountId;

  @Column(name = "REASON_CODE", nullable = false, length = 64)
  private String reasonCode;

  @Column(name = "BATCH_ID", nullable = false, length = 64)
  private String batchId;

  /** Creates an empty rejected transaction for JPA. */
  protected LegacyRejectedTransaction() {}

  /** Creates a rejected-transaction row. */
  public LegacyRejectedTransaction(
      String eventId, String transactionId, Long accountId, String reasonCode, String batchId) {
    this.eventId = eventId;
    this.transactionId = transactionId;
    this.accountId = accountId;
    this.reasonCode = reasonCode;
    this.batchId = batchId;
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

  public String getReasonCode() {
    return reasonCode;
  }

  public String getBatchId() {
    return batchId;
  }
}

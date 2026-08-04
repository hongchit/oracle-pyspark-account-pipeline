package com.henrychan.accountmigration.legacy.repository;

import com.henrychan.accountmigration.legacy.entity.TransactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides persistence operations for transaction events. */
public interface TransactionEventRepository extends JpaRepository<TransactionEvent, String> {

  /** Reports whether a transaction event exists for the supplied generation run. */
  boolean existsByGenerationRunId(String generationRunId);
}

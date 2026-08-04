package com.henrychan.accountmigration.legacy.repository;

import com.henrychan.accountmigration.legacy.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides persistence operations for accounts. */
public interface AccountRepository extends JpaRepository<Account, Long> {

  /** Reports whether an account exists for the supplied generation run. */
  boolean existsByGenerationRunId(String generationRunId);
}

package com.henrychan.accountmigration.legacy.repository;

import com.henrychan.accountmigration.legacy.entity.TransactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionEventRepository extends JpaRepository<TransactionEvent, String> {
}

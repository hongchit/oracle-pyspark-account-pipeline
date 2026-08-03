package com.henrychan.accountmigration.legacy.repository;

import com.henrychan.accountmigration.legacy.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}

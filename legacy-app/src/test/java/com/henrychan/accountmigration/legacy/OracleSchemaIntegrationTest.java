package com.henrychan.accountmigration.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.henrychan.accountmigration.legacy.repository.AccountRepository;
import com.henrychan.accountmigration.legacy.repository.TransactionEventRepository;

@SpringBootTest
class OracleSchemaIntegrationTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "ACCOUNT",
            "TRANSACTION_EVENT",
            "LEGACY_DAILY_BALANCE",
            "LEGACY_REJECTED_TRANSACTION"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionEventRepository transactionEventRepository;

    @Test
    void createsRequiredTablesInDedicatedSchema() {
        assertThat(jdbcTemplate.queryForObject("SELECT USER FROM dual", String.class))
                .isEqualTo("ACCOUNT_APP");

        Set<String> actualTables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM user_tables WHERE table_name IN "
                        + "('ACCOUNT', 'TRANSACTION_EVENT', 'LEGACY_DAILY_BALANCE', "
                        + "'LEGACY_REJECTED_TRANSACTION')",
                String.class));

        assertThat(actualTables).isEqualTo(EXPECTED_TABLES);
        assertThat(accountRepository).isNotNull();
        assertThat(transactionEventRepository).isNotNull();
    }

    @Test
    void createsExpectedPrimaryKeysAndColumnTypes() {
        List<Map<String, Object>> primaryKeyRows = jdbcTemplate.queryForList("""
                SELECT uc.table_name, ucc.column_name
                  FROM user_constraints uc
                  JOIN user_cons_columns ucc
                    ON ucc.constraint_name = uc.constraint_name
                 WHERE uc.constraint_type = 'P'
                   AND uc.table_name IN (
                       'ACCOUNT',
                       'TRANSACTION_EVENT',
                       'LEGACY_DAILY_BALANCE',
                       'LEGACY_REJECTED_TRANSACTION'
                   )
                """);

        Map<String, Set<String>> primaryKeys = primaryKeyRows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.get("TABLE_NAME").toString(),
                        Collectors.mapping(row -> row.get("COLUMN_NAME").toString(), Collectors.toSet())
                ));

        assertThat(primaryKeys.get("ACCOUNT")).containsExactly("ACCOUNT_ID");
        assertThat(primaryKeys.get("TRANSACTION_EVENT")).containsExactly("EVENT_ID");
        assertThat(primaryKeys.get("LEGACY_DAILY_BALANCE"))
                .containsExactlyInAnyOrder("ACCOUNT_ID", "BALANCE_DATE");
        assertThat(primaryKeys.get("LEGACY_REJECTED_TRANSACTION")).containsExactly("EVENT_ID");

        Integer exactDecimalColumnCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM user_tab_columns
                 WHERE (table_name, column_name) IN (
                       ('ACCOUNT', 'OPENING_BALANCE'),
                       ('TRANSACTION_EVENT', 'AMOUNT'),
                       ('LEGACY_DAILY_BALANCE', 'OPENING_BALANCE'),
                       ('LEGACY_DAILY_BALANCE', 'TOTAL_CREDIT'),
                       ('LEGACY_DAILY_BALANCE', 'TOTAL_DEBIT'),
                       ('LEGACY_DAILY_BALANCE', 'CLOSING_BALANCE')
                 )
                   AND data_type = 'NUMBER'
                   AND data_precision = 18
                   AND data_scale = 2
                """, Integer.class);

        assertThat(exactDecimalColumnCount).isEqualTo(6);

        Map<String, String> temporalTypes = jdbcTemplate.query("""
                SELECT column_name, data_type
                  FROM user_tab_columns
                 WHERE table_name = 'TRANSACTION_EVENT'
                   AND column_name IN ('POSTING_DATE', 'INGESTED_AT')
                """, resultSet -> {
            Map<String, String> types = new java.util.HashMap<>();
            while (resultSet.next()) {
                types.put(resultSet.getString("COLUMN_NAME"), resultSet.getString("DATA_TYPE"));
            }
            return types;
        });

        assertThat(temporalTypes.get("POSTING_DATE")).isEqualTo("DATE");
        assertThat(temporalTypes.get("INGESTED_AT")).startsWith("TIMESTAMP");
    }

    @Test
    void doesNotCreateTransactionAccountForeignKey() {
        Integer foreignKeyCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM user_constraints uc
                  JOIN user_cons_columns ucc
                    ON ucc.constraint_name = uc.constraint_name
                 WHERE uc.constraint_type = 'R'
                   AND uc.table_name = 'TRANSACTION_EVENT'
                   AND ucc.column_name = 'ACCOUNT_ID'
                """, Integer.class);

        assertThat(foreignKeyCount).isZero();
    }
}

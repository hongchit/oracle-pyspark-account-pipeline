package com.henrychan.accountmigration.legacy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.henrychan.accountmigration.legacy.entity.Account;
import com.henrychan.accountmigration.legacy.entity.TransactionEvent;
import com.henrychan.accountmigration.legacy.repository.AccountRepository;
import com.henrychan.accountmigration.legacy.repository.TransactionEventRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DataGenerationServiceTest {

  private static final LocalDate PROCESSING_DATE = LocalDate.of(2026, 8, 1);
  private static final long SEED = 42L;

  @Mock private AccountRepository accountRepository;

  @Mock private TransactionEventRepository transactionEventRepository;

  @Mock private JdbcTemplate jdbcTemplate;

  private DataGenerationService service;

  @BeforeEach
  void setUp() {
    service =
        new DataGenerationService(accountRepository, transactionEventRepository, jdbcTemplate);
    when(accountRepository.existsByGenerationRunId(anyString())).thenReturn(false);
    when(transactionEventRepository.existsByGenerationRunId(anyString())).thenReturn(false);
  }

  @Test
  void sameDateAndSeedGenerateExactlyTheSameDataset() {
    DataGenerationSummary firstSummary = service.generate(PROCESSING_DATE, SEED, false);
    DataGenerationSummary secondSummary = service.generate(PROCESSING_DATE, SEED, false);

    ArgumentCaptor<List<Account>> accountCaptor = accountListCaptor();
    ArgumentCaptor<List<TransactionEvent>> eventCaptor = transactionEventListCaptor();
    verify(accountRepository, org.mockito.Mockito.times(2)).saveAll(accountCaptor.capture());
    verify(transactionEventRepository, org.mockito.Mockito.times(2)).saveAll(eventCaptor.capture());

    assertThat(secondSummary).isEqualTo(firstSummary);
    assertThat(accountCaptor.getAllValues().get(1))
        .usingRecursiveComparison()
        .isEqualTo(accountCaptor.getAllValues().get(0));
    assertThat(eventCaptor.getAllValues().get(1))
        .usingRecursiveComparison()
        .isEqualTo(eventCaptor.getAllValues().get(0));
  }

  @Test
  void generatedDataset() {
    DataGenerationSummary summary = service.generate(PROCESSING_DATE, SEED, false);

    ArgumentCaptor<List<Account>> accountCaptor = accountListCaptor();
    ArgumentCaptor<List<TransactionEvent>> eventCaptor = transactionEventListCaptor();
    verify(accountRepository).saveAll(accountCaptor.capture());
    verify(transactionEventRepository).saveAll(eventCaptor.capture());

    List<Account> accounts = accountCaptor.getValue();
    List<TransactionEvent> events = eventCaptor.getValue();
    Set<Long> accountIds = accounts.stream().map(Account::getAccountId).collect(Collectors.toSet());
    Map<String, Long> transactionIdCounts =
        events.stream()
            .collect(
                Collectors.groupingBy(TransactionEvent::getTransactionId, Collectors.counting()));

    assertThat(summary.accountCount()).isEqualTo(5);
    assertThat(summary.transactionEventCount()).isEqualTo(18);
    assertThat(summary.normalTransactionCount()).isEqualTo(12);
    assertThat(summary.duplicateEventCount()).isEqualTo(2);
    assertThat(summary.excludedStatusEventCount()).isEqualTo(3);
    assertThat(summary.unknownAccountEventCount()).isEqualTo(1);
    assertThat(summary.noActivityAccountCount()).isEqualTo(1);

    String duplicateTransactionId =
        transactionIdCounts.entrySet().stream()
            .filter(entry -> entry.getValue() == 2L)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow();

    assertThat(events)
        .filteredOn(
            event -> DataGenerationService.DUPLICATE_SCENARIO.equals(event.getScenarioCode()))
        .hasSize(2)
        .extracting(TransactionEvent::getTransactionId)
        .containsOnly(duplicateTransactionId);

    List<TransactionEvent> duplicateEvents =
        events.stream()
            .filter(event -> duplicateTransactionId.equals(event.getTransactionId()))
            .sorted(Comparator.comparing(TransactionEvent::getIngestedAt))
            .toList();
    assertThat(duplicateEvents.get(1).getIngestedAt())
        .isAfter(duplicateEvents.get(0).getIngestedAt());
    assertThat(duplicateEvents).extracting(TransactionEvent::getEventId).doesNotHaveDuplicates();

    assertThat(transactionIdCounts.values()).filteredOn(count -> count == 2L).hasSize(1);
    assertThat(transactionIdCounts.values()).filteredOn(count -> count == 1L).hasSize(16);
    assertThat(events).filteredOn(event -> "PENDING".equals(event.getStatus())).hasSize(2);
    assertThat(events).filteredOn(event -> "CANCELLED".equals(event.getStatus())).hasSize(1);
    assertThat(events).filteredOn(event -> !accountIds.contains(event.getAccountId())).hasSize(1);

    Set<Long> accountsWithEvents =
        events.stream()
            .map(TransactionEvent::getAccountId)
            .filter(accountIds::contains)
            .collect(Collectors.toSet());
    assertThat(accountIds)
        .filteredOn(accountId -> !accountsWithEvents.contains(accountId))
        .hasSize(1);
    assertThat(events)
        .allSatisfy(
            event -> {
              assertThat(event.getPostingDate()).isEqualTo(PROCESSING_DATE);
              assertThat(event.getAmount().scale()).isEqualTo(2);
              assertThat(event.getGenerationRunId()).isEqualTo(summary.generationRunId());
            });
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ArgumentCaptor<List<Account>> accountListCaptor() {
    return ArgumentCaptor.forClass((Class) List.class);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ArgumentCaptor<List<TransactionEvent>> transactionEventListCaptor() {
    return ArgumentCaptor.forClass((Class) List.class);
  }
}

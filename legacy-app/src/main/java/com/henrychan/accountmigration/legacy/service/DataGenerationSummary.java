package com.henrychan.accountmigration.legacy.service;

/** Summarizes a deterministic source-data generation run. */
public record DataGenerationSummary(
    String generationRunId,
    int accountCount,
    int transactionEventCount,
    int normalTransactionCount,
    int duplicateEventCount,
    int excludedStatusEventCount,
    int unknownAccountEventCount,
    int noActivityAccountCount) {}

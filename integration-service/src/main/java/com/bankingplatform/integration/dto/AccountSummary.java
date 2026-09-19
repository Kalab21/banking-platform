package com.bankingplatform.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * As much of an account as this service is entitled to know.
 *
 * <p>account-service returns a fuller record than this. Only the owning user
 * is needed to answer "may this caller send money from this account?", so only
 * the owning user is mapped: a balance or an account number deserialised here
 * would be a copy of customer data living in a service that has no reason to
 * hold it, and one more place it could be logged or returned by mistake.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountSummary {

    private Long id;
    private Long userId;
}

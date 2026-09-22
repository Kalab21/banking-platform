package com.bankingplatform.application.service;

import com.bankingplatform.application.dto.OfferResponse;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.Offer;
import com.bankingplatform.application.underwriting.OfferedTerms;

import java.util.List;

public interface OfferService {

    /** Records what the bank will lend on, once an application is approved. */
    Offer offer(Application application, OfferedTerms terms, Long decisionSnapshotId);

    /** The customer takes the offer as made. Idempotent. */
    OfferResponse accept(Long applicationId, Long callerUserId);

    /** The customer turns it down. Idempotent. */
    OfferResponse decline(Long applicationId, Long callerUserId);

    List<OfferResponse> forApplication(Long applicationId);
}

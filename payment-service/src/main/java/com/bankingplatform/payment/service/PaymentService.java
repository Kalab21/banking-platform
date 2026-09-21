package com.bankingplatform.payment.service;

import com.bankingplatform.payment.dto.CreatePaymentRequest;
import com.bankingplatform.payment.dto.PaymentResponse;
import com.bankingplatform.payment.model.PaymentStatus;

import java.util.List;

public interface PaymentService {
    PaymentResponse createPayment(CreatePaymentRequest request);
    PaymentResponse getByRef(String ref);
    PaymentResponse getById(Long id);
    List<PaymentResponse> getByPayerAccount(Long accountId);
    List<PaymentResponse> getScheduledByPayerAccount(Long accountId);
    PaymentResponse cancel(Long id);
    /**
     * Takes ownership of up to {@code limit} payments that are due, and of
     * any left stalled by a worker that died.
     *
     * @return the ids now owned by this caller, to be handed to
     *         {@link #processClaimedPayment(Long)} one at a time
     */
    List<Long> claimScheduledPayments(int limit);

    /** Processes one payment this worker has already claimed. */
    void processClaimedPayment(Long paymentId);
}

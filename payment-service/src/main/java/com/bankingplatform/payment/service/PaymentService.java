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
    void processScheduledPayments();
}

package com.bankingplatform.payment.service;

import com.bankingplatform.payment.dto.BeneficiaryResponse;
import com.bankingplatform.payment.dto.CreateBeneficiaryRequest;

import java.util.List;

public interface BeneficiaryService {
    BeneficiaryResponse create(CreateBeneficiaryRequest request);
    BeneficiaryResponse getById(Long id);
    List<BeneficiaryResponse> getByUserId(Long userId);
    void delete(Long id, Long userId);
}

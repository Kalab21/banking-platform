package com.bankingplatform.payment.service.impl;

import com.bankingplatform.payment.dto.BeneficiaryResponse;
import com.bankingplatform.payment.dto.CreateBeneficiaryRequest;
import com.bankingplatform.payment.exception.PaymentException;
import com.bankingplatform.payment.exception.ResourceNotFoundException;
import com.bankingplatform.payment.mapper.BeneficiaryMapper;
import com.bankingplatform.payment.model.Beneficiary;
import com.bankingplatform.payment.repository.BeneficiaryRepository;
import com.bankingplatform.payment.service.BeneficiaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BeneficiaryServiceImpl implements BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final BeneficiaryMapper beneficiaryMapper;

    @Override
    @Transactional
    public BeneficiaryResponse create(CreateBeneficiaryRequest request) {
        Beneficiary beneficiary = Beneficiary.builder()
                .userId(request.getUserId())
                .name(request.getName())
                .nickname(request.getNickname())
                .accountNumber(request.getAccountNumber())
                .bankName(request.getBankName())
                .routingNumber(request.getRoutingNumber())
                .swiftCode(request.getSwiftCode())
                .iban(request.getIban())
                .beneficiaryType(request.getBeneficiaryType())
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .build();
        return beneficiaryMapper.toResponse(beneficiaryRepository.save(beneficiary));
    }

    @Override
    public BeneficiaryResponse getById(Long id) {
        return beneficiaryMapper.toResponse(findById(id));
    }

    @Override
    public List<BeneficiaryResponse> getByUserId(Long userId) {
        return beneficiaryRepository.findByUserId(userId).stream()
                .map(beneficiaryMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        Beneficiary beneficiary = findById(id);
        if (!beneficiary.getUserId().equals(userId)) {
            throw new PaymentException("Cannot delete another user's beneficiary");
        }
        beneficiaryRepository.delete(beneficiary);
    }

    private Beneficiary findById(Long id) {
        return beneficiaryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary not found: " + id));
    }
}

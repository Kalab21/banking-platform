package com.bankingplatform.integration.service;

import com.bankingplatform.integration.dto.*;
import com.bankingplatform.integration.exception.ResourceNotFoundException;
import com.bankingplatform.integration.exception.UnsupportedCurrencyException;
import com.bankingplatform.integration.kafka.producer.ExternalTransferEventProducer;
import com.bankingplatform.integration.model.ExternalTransfer;
import com.bankingplatform.integration.model.TransferType;
import com.bankingplatform.integration.repository.ExternalTransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final ExternalTransferRepository transferRepository;
    private final ExternalTransferEventProducer eventProducer;

    // Stub FX rates — real impl would call Fixer.io or Open Exchange Rates
    private static final Map<String, BigDecimal> RATES = Map.of(
        "USD_EUR", new BigDecimal("0.92"),
        "USD_GBP", new BigDecimal("0.79"),
        "USD_JPY", new BigDecimal("149.50"),
        "EUR_USD", new BigDecimal("1.09"),
        "GBP_USD", new BigDecimal("1.27"),
        "EUR_GBP", new BigDecimal("0.86"),
        "GBP_EUR", new BigDecimal("1.16"),
        "JPY_USD", new BigDecimal("0.0067")
    );

    @Transactional
    public TransferResponse initiateWireTransfer(WireTransferRequest req) {
        ExternalTransfer transfer = new ExternalTransfer();
        transfer.setTransferRef(UUID.randomUUID().toString());
        transfer.setTransferType(TransferType.WIRE);
        transfer.setFromAccountId(req.getFromAccountId());
        transfer.setBeneficiaryName(req.getBeneficiaryName());
        transfer.setBeneficiaryAccount(req.getBeneficiaryAccount());
        transfer.setRoutingNumber(req.getRoutingNumber());
        transfer.setBankName(req.getBankName());
        transfer.setBankCountry(req.getBankCountry());
        transfer.setAmount(req.getAmount());
        transfer.setCurrency(req.getCurrency() != null ? req.getCurrency() : "USD");
        transfer.setPurpose(req.getPurpose());
        transfer.setEstimatedArrival(LocalDate.now().plusDays(2));
        ExternalTransfer saved = transferRepository.save(transfer);
        eventProducer.publishTransferInitiated(saved.getId(), saved.getTransferRef(),
                saved.getTransferType().name(), saved.getFromAccountId(),
                saved.getAmount(), saved.getCurrency(), saved.getEstimatedArrival());
        return toResponse(saved);
    }

    @Transactional
    public TransferResponse initiateAchTransfer(AchTransferRequest req) {
        ExternalTransfer transfer = new ExternalTransfer();
        transfer.setTransferRef(UUID.randomUUID().toString());
        transfer.setTransferType(TransferType.ACH);
        transfer.setFromAccountId(req.getFromAccountId());
        transfer.setBeneficiaryName(req.getBeneficiaryName());
        transfer.setBeneficiaryAccount(req.getBeneficiaryAccount());
        transfer.setRoutingNumber(req.getRoutingNumber());
        transfer.setBankName(req.getBankName());
        transfer.setAmount(req.getAmount());
        transfer.setCurrency(req.getCurrency() != null ? req.getCurrency() : "USD");
        transfer.setPurpose(req.getPurpose());
        transfer.setEstimatedArrival(LocalDate.now().plusDays(3));
        ExternalTransfer saved = transferRepository.save(transfer);
        eventProducer.publishTransferInitiated(saved.getId(), saved.getTransferRef(),
                saved.getTransferType().name(), saved.getFromAccountId(),
                saved.getAmount(), saved.getCurrency(), saved.getEstimatedArrival());
        return toResponse(saved);
    }

    @Transactional
    public TransferResponse initiateSwiftTransfer(SwiftTransferRequest req) {
        ExternalTransfer transfer = new ExternalTransfer();
        transfer.setTransferRef(UUID.randomUUID().toString());
        transfer.setTransferType(TransferType.SWIFT);
        transfer.setFromAccountId(req.getFromAccountId());
        transfer.setBeneficiaryName(req.getBeneficiaryName());
        transfer.setIban(req.getIban());
        transfer.setSwiftCode(req.getSwiftCode());
        transfer.setBankName(req.getBankName());
        transfer.setBankCountry(req.getBankCountry());
        transfer.setAmount(req.getAmount());
        transfer.setCurrency(req.getCurrency() != null ? req.getCurrency() : "USD");
        transfer.setPurpose(req.getPurpose());
        transfer.setEstimatedArrival(LocalDate.now().plusDays(5));
        ExternalTransfer saved = transferRepository.save(transfer);
        eventProducer.publishTransferInitiated(saved.getId(), saved.getTransferRef(),
                saved.getTransferType().name(), saved.getFromAccountId(),
                saved.getAmount(), saved.getCurrency(), saved.getEstimatedArrival());
        return toResponse(saved);
    }

    public TransferResponse getTransfer(String ref) {
        return transferRepository.findByTransferRef(ref)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + ref));
    }

    public ExchangeRateResponse getExchangeRate(String from, String to) {
        BigDecimal rate = resolveRate(from.toUpperCase(), to.toUpperCase());
        return new ExchangeRateResponse(from.toUpperCase(), to.toUpperCase(), rate);
    }

    public ConvertResponse convert(String from, String to, BigDecimal amount) {
        String fromUpper = from.toUpperCase();
        String toUpper = to.toUpperCase();
        BigDecimal rate = resolveRate(fromUpper, toUpper);
        BigDecimal converted = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return new ConvertResponse(fromUpper, toUpper, amount, converted, rate);
    }

    public AccountValidationResponse validateAccount(String accountNumber) {
        // Stub — real impl would call Plaid or bank directory API
        boolean valid = accountNumber != null && accountNumber.matches("\\d{8,17}");
        String msg = valid ? "Account number format is valid" : "Account number format is invalid";
        return new AccountValidationResponse(accountNumber, valid, msg);
    }

    private BigDecimal resolveRate(String from, String to) {
        if (from.equals(to)) return BigDecimal.ONE;
        String key = from + "_" + to;
        BigDecimal rate = RATES.get(key);
        if (rate == null) {
            throw new UnsupportedCurrencyException("No FX rate available for " + from + " → " + to);
        }
        return rate;
    }

    private TransferResponse toResponse(ExternalTransfer t) {
        TransferResponse r = new TransferResponse();
        r.setId(t.getId());
        r.setTransferRef(t.getTransferRef());
        r.setTransferType(t.getTransferType().name());
        r.setFromAccountId(t.getFromAccountId());
        r.setBeneficiaryName(t.getBeneficiaryName());
        r.setBeneficiaryAccount(t.getBeneficiaryAccount());
        r.setRoutingNumber(t.getRoutingNumber());
        r.setSwiftCode(t.getSwiftCode());
        r.setIban(t.getIban());
        r.setBankName(t.getBankName());
        r.setBankCountry(t.getBankCountry());
        r.setAmount(t.getAmount());
        r.setCurrency(t.getCurrency());
        r.setPurpose(t.getPurpose());
        r.setStatus(t.getStatus());
        r.setEstimatedArrival(t.getEstimatedArrival());
        r.setCreatedAt(t.getCreatedAt());
        return r;
    }
}

package com.bankingplatform.loan.kafka.consumer;

import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.model.LoanType;
import com.bankingplatform.loan.service.LoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationEventConsumer {

    private static final Set<String> LOAN_PRODUCT_TYPES = Set.of("PERSONAL_LOAN", "AUTO_LOAN", "MORTGAGE");
    private final LoanService loanService;

    @KafkaListener(topics = "application-events", groupId = "loan-service")
    public void onApplicationEvent(Map<String, Object> event) {
        try {
            String eventType = (String) event.get("eventType");
            String productType = (String) event.get("productType");

            if (!"APPLICATION_APPROVED".equals(eventType) || !LOAN_PRODUCT_TYPES.contains(productType)) {
                return;
            }

            Long userId = toLong(event.get("userId"));
            Long applicationId = toLong(event.get("applicationId"));
            BigDecimal requestedAmount = toBigDecimal(event.get("requestedAmount"));

            CreateLoanRequest request = new CreateLoanRequest();
            request.setUserId(userId);
            request.setApplicationId(applicationId);
            request.setLoanType(LoanType.valueOf(productType));
            request.setPrincipal(requestedAmount != null ? requestedAmount : resolveDefaultPrincipal(productType));
            request.setInterestRate(resolveRate(productType, toInt(event.get("creditScore"))));
            request.setTermMonths(resolveTermMonths(productType));

            loanService.createLoan(request);
            log.info("Created loan for userId={}, applicationId={}, type={}", userId, applicationId, productType);
        } catch (Exception e) {
            log.error("Error processing application event: {}", e.getMessage(), e);
        }
    }

    private BigDecimal resolveDefaultPrincipal(String loanType) {
        return switch (loanType) {
            case "MORTGAGE" -> new BigDecimal("200000");
            case "AUTO_LOAN" -> new BigDecimal("25000");
            default -> new BigDecimal("10000");
        };
    }

    private BigDecimal resolveRate(String loanType, Integer creditScore) {
        int score = creditScore != null ? creditScore : 650;
        return switch (loanType) {
            case "MORTGAGE" -> score >= 760 ? new BigDecimal("6.50") : new BigDecimal("7.25");
            case "AUTO_LOAN" -> score >= 720 ? new BigDecimal("7.99") : new BigDecimal("9.99");
            default -> score >= 720 ? new BigDecimal("10.99") : new BigDecimal("14.99");
        };
    }

    private Integer resolveTermMonths(String loanType) {
        return switch (loanType) {
            case "MORTGAGE" -> 360;
            case "AUTO_LOAN" -> 60;
            default -> 48;
        };
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    private Integer toInt(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(val.toString());
    }
}

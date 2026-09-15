package com.bankingplatform.loan.dto.response;

import com.bankingplatform.loan.model.ScheduleStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AmortizationScheduleResponse {
    private Long id;
    private Long loanId;
    private Integer paymentNumber;
    private LocalDate dueDate;
    private BigDecimal scheduledPayment;
    private BigDecimal principalPortion;
    private BigDecimal interestPortion;
    private BigDecimal remainingBalance;
    private ScheduleStatus status;
    private LocalDateTime paidAt;
}

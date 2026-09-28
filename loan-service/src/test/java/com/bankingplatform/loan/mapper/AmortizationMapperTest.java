package com.bankingplatform.loan.mapper;

import com.bankingplatform.loan.dto.response.AmortizationScheduleResponse;
import com.bankingplatform.loan.model.AmortizationSchedule;
import com.bankingplatform.loan.model.Loan;
import com.bankingplatform.loan.model.ScheduleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmortizationMapperTest {

    private final AmortizationMapper mapper = new AmortizationMapperImpl();

    @Test
    @DisplayName("a part-paid instalment says how much of it has been paid")
    void carriesTheAmountPaid() {
        // The console offers "pay the rest of this instalment". Without the
        // amount already paid it could only offer the full instalment again,
        // which overpays a PARTIAL one.
        AmortizationSchedule row = new AmortizationSchedule();
        row.setLoan(Loan.builder().id(7L).build());
        row.setPaymentNumber(1);
        row.setScheduledPayment(new BigDecimal("180.00"));
        row.setAmountPaid(new BigDecimal("50.00"));
        row.setStatus(ScheduleStatus.PARTIAL);

        AmortizationScheduleResponse response = mapper.toResponse(row);

        assertThat(response.getLoanId()).isEqualTo(7L);
        assertThat(response.getAmountPaid()).isEqualByComparingTo("50.00");
    }
}

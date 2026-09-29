package com.bankingplatform.loan.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Instalment due dates")
class InstalmentDatesTest {

    @Test
    @DisplayName("keep the start's day of month after a short month")
    void noDriftAfterFebruary() {
        // Chained one month at a time, 31 January gave 28 February, then 28
        // March, 28 April... for the rest of the term.
        LocalDate start = LocalDate.of(2027, 1, 31);
        assertThat(LoanServiceImpl.dueDateOf(start, 1)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(LoanServiceImpl.dueDateOf(start, 2)).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(LoanServiceImpl.dueDateOf(start, 12)).isEqualTo(LocalDate.of(2028, 1, 31));
    }

    @Test
    @DisplayName("a loan started on the 29th keeps the 29th a year on")
    void twentyNinthStaysTheTwentyNinth() {
        LocalDate start = LocalDate.of(2026, 9, 29);
        assertThat(LoanServiceImpl.dueDateOf(start, 12)).isEqualTo(LocalDate.of(2027, 9, 29));
    }
}

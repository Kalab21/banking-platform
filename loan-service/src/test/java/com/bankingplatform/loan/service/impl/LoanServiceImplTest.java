package com.bankingplatform.loan.service.impl;

import com.bankingplatform.loan.client.AccountClient;
import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.dto.request.DisburseRequest;
import com.bankingplatform.loan.dto.request.LoanRepaymentRequest;
import com.bankingplatform.loan.dto.response.PayoffQuoteResponse;
import com.bankingplatform.loan.exception.LoanNotActiveException;
import com.bankingplatform.loan.exception.ResourceNotFoundException;
import com.bankingplatform.loan.kafka.producer.LoanEventProducer;
import com.bankingplatform.loan.mapper.AmortizationMapper;
import com.bankingplatform.loan.mapper.LoanMapper;
import com.bankingplatform.loan.mapper.LoanRepaymentMapper;
import com.bankingplatform.loan.model.AmortizationSchedule;
import com.bankingplatform.loan.model.Loan;
import com.bankingplatform.loan.model.LoanRepayment;
import com.bankingplatform.loan.model.LoanStatus;
import com.bankingplatform.loan.model.LoanType;
import com.bankingplatform.loan.model.ScheduleStatus;
import com.bankingplatform.loan.repository.AmortizationScheduleRepository;
import com.bankingplatform.loan.repository.LoanRepaymentRepository;
import com.bankingplatform.loan.repository.LoanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link LoanServiceImpl}.
 *
 * <p>The reference loan throughout is $10,000 at 6.00% APR over 12 months, whose
 * standard amortised payment is $860.66. That figure is an external reference value,
 * not one produced by re-running the implementation's own formula, so these tests
 * would catch a regression in {@code calculateMonthlyPayment} rather than track it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoanServiceImpl — lending lifecycle")
class LoanServiceImplTest {

    private static final long LOAN_ID = 500L;
    private static final long USER_ID = 9L;
    private static final long ACCOUNT_ID = 77L;

    /** $10,000 at 6.00% APR over 12 months. */
    private static final BigDecimal PRINCIPAL = new BigDecimal("10000.00");
    private static final BigDecimal ANNUAL_RATE = new BigDecimal("6.00");
    private static final int TERM_MONTHS = 12;
    private static final BigDecimal MONTHLY_PAYMENT = new BigDecimal("860.66");

    @Mock private LoanRepository loanRepository;
    @Mock private AmortizationScheduleRepository scheduleRepository;
    @Mock private LoanRepaymentRepository repaymentRepository;
    @Mock private AccountClient accountClient;
    @Mock private LoanEventProducer eventProducer;
    @Mock private LoanMapper loanMapper;
    @Mock private AmortizationMapper amortizationMapper;
    @Mock private LoanRepaymentMapper repaymentMapper;

    @InjectMocks private LoanServiceImpl loanService;

    @Captor private ArgumentCaptor<Loan> savedLoan;
    @Captor private ArgumentCaptor<LoanRepayment> savedRepayment;
    @Captor private ArgumentCaptor<List<AmortizationSchedule>> savedSchedule;

    // ---------------------------------------------------------------- fixtures

    private static CreateLoanRequest createRequest(BigDecimal principal, BigDecimal rate, int termMonths) {
        CreateLoanRequest request = new CreateLoanRequest();
        request.setUserId(USER_ID);
        request.setLoanType(LoanType.PERSONAL_LOAN);
        request.setPrincipal(principal);
        request.setInterestRate(rate);
        request.setTermMonths(termMonths);
        request.setDisbursementAccountId(ACCOUNT_ID);
        return request;
    }

    private static Loan loan(LoanStatus status, String remainingBalance) {
        return Loan.builder()
                .id(LOAN_ID)
                .userId(USER_ID)
                .loanType(LoanType.PERSONAL_LOAN)
                .principal(PRINCIPAL)
                .interestRate(ANNUAL_RATE)
                .termMonths(TERM_MONTHS)
                .monthlyPayment(MONTHLY_PAYMENT)
                .totalInterest(new BigDecimal("327.92"))
                .remainingBalance(new BigDecimal(remainingBalance))
                .paymentsMade(0)
                .status(status)
                .currency("USD")
                .build();
    }

    private static AmortizationSchedule scheduleEntry(int number, String payment, String principal, String interest) {
        return AmortizationSchedule.builder()
                .paymentNumber(number)
                .dueDate(LocalDate.now().plusMonths(number))
                .scheduledPayment(new BigDecimal(payment))
                .principalPortion(new BigDecimal(principal))
                .interestPortion(new BigDecimal(interest))
                .remainingBalance(BigDecimal.ZERO)
                .status(ScheduleStatus.PENDING)
                .build();
    }

    private static LoanRepaymentRequest repaymentRequest(String amount, Long sourceAccountId) {
        LoanRepaymentRequest request = new LoanRepaymentRequest();
        request.setAmount(new BigDecimal(amount));
        request.setSourceAccountId(sourceAccountId);
        return request;
    }

    // ------------------------------------------------------------ loan opening

    @Nested
    @DisplayName("createLoan")
    class CreateLoan {

        @Test
        @DisplayName("derives the amortised monthly payment, total interest and opening balance")
        void computesLoanTerms() {
            when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

            loanService.createLoan(createRequest(PRINCIPAL, ANNUAL_RATE, TERM_MONTHS));

            verify(loanRepository).save(savedLoan.capture());
            Loan result = savedLoan.getValue();

            assertThat(result.getMonthlyPayment()).isEqualByComparingTo(MONTHLY_PAYMENT);
            // 860.66 x 12 = 10,327.92, of which 327.92 is interest
            assertThat(result.getTotalInterest()).isEqualByComparingTo("327.92");
            assertThat(result.getRemainingBalance()).isEqualByComparingTo(PRINCIPAL);
            assertThat(result.getStatus()).isEqualTo(LoanStatus.PENDING);
        }

        @Test
        @DisplayName("splits the principal evenly when the loan carries no interest")
        void zeroInterestLoanDividesPrincipalEvenly() {
            when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

            loanService.createLoan(createRequest(new BigDecimal("1200.00"), BigDecimal.ZERO, 12));

            verify(loanRepository).save(savedLoan.capture());
            assertThat(savedLoan.getValue().getMonthlyPayment()).isEqualByComparingTo("100.00");
            assertThat(savedLoan.getValue().getTotalInterest()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("writes one schedule row per month of the term")
        void scheduleHasOneRowPerTerm() {
            when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

            loanService.createLoan(createRequest(PRINCIPAL, ANNUAL_RATE, TERM_MONTHS));

            verify(scheduleRepository).saveAll(savedSchedule.capture());
            List<AmortizationSchedule> schedule = savedSchedule.getValue();

            assertThat(schedule).hasSize(TERM_MONTHS);
            assertThat(schedule).extracting(AmortizationSchedule::getPaymentNumber)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
            assertThat(schedule).allSatisfy(row ->
                    assertThat(row.getStatus()).isEqualTo(ScheduleStatus.PENDING));
        }

        @Test
        @DisplayName("charges a full month of interest on the opening balance in the first instalment")
        void firstInstalmentSplitsInterestAndPrincipal() {
            when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

            loanService.createLoan(createRequest(PRINCIPAL, ANNUAL_RATE, TERM_MONTHS));

            verify(scheduleRepository).saveAll(savedSchedule.capture());
            AmortizationSchedule first = savedSchedule.getValue().get(0);

            // 10,000.00 x (6.00 / 1200) = 50.00
            assertThat(first.getInterestPortion()).isEqualByComparingTo("50.00");
            assertThat(first.getPrincipalPortion()).isEqualByComparingTo("810.66");
            assertThat(first.getRemainingBalance()).isEqualByComparingTo("9189.34");
        }

        @Test
        @DisplayName("amortises to exactly zero, with the principal portions summing to the amount borrowed")
        void scheduleAmortisesToZero() {
            when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

            loanService.createLoan(createRequest(PRINCIPAL, ANNUAL_RATE, TERM_MONTHS));

            verify(scheduleRepository).saveAll(savedSchedule.capture());
            List<AmortizationSchedule> schedule = savedSchedule.getValue();

            assertThat(schedule.get(TERM_MONTHS - 1).getRemainingBalance()).isEqualByComparingTo("0.00");

            BigDecimal principalRepaid = schedule.stream()
                    .map(AmortizationSchedule::getPrincipalPortion)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(principalRepaid).isEqualByComparingTo(PRINCIPAL);
        }

        @Test
        @DisplayName("schedules the instalments one month apart")
        void instalmentsAreOneMonthApart() {
            when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

            loanService.createLoan(createRequest(PRINCIPAL, ANNUAL_RATE, TERM_MONTHS));

            verify(scheduleRepository).saveAll(savedSchedule.capture());
            List<AmortizationSchedule> schedule = savedSchedule.getValue();

            assertThat(schedule.get(0).getDueDate()).isEqualTo(LocalDate.now().plusMonths(1));
            assertThat(schedule.get(TERM_MONTHS - 1).getDueDate())
                    .isEqualTo(LocalDate.now().plusMonths(TERM_MONTHS));
        }
    }

    // ------------------------------------------------------------ disbursement

    @Nested
    @DisplayName("disburseLoan")
    class Disburse {

        @Test
        @DisplayName("credits the nominated account and activates the loan")
        void disbursesAndActivates() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.PENDING, "10000.00")));
            when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

            DisburseRequest request = new DisburseRequest();
            request.setDisbursementAccountId(ACCOUNT_ID);
            loanService.disburseLoan(LOAN_ID, request);

            verify(accountClient).credit(eq(ACCOUNT_ID), eq(PRINCIPAL), any());
            verify(loanRepository).save(savedLoan.capture());

            Loan result = savedLoan.getValue();
            assertThat(result.getStatus()).isEqualTo(LoanStatus.ACTIVE);
            assertThat(result.getDisbursedAt()).isNotNull();
            assertThat(result.getNextPaymentDate()).isEqualTo(LocalDate.now().plusMonths(1));
            verify(eventProducer).publishLoanDisbursed(eq(LOAN_ID), eq(USER_ID), eq(PRINCIPAL), eq(ACCOUNT_ID));
        }

        @Test
        @DisplayName("refuses to disburse a loan that is not pending")
        void refusesNonPendingLoan() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));

            DisburseRequest request = new DisburseRequest();
            request.setDisbursementAccountId(ACCOUNT_ID);

            assertThatThrownBy(() -> loanService.disburseLoan(LOAN_ID, request))
                    .isInstanceOf(LoanNotActiveException.class)
                    .hasMessageContaining("ACTIVE");

            verify(accountClient, never()).credit(anyLong(), any(), any());
            verify(loanRepository, never()).save(any());
        }
    }

    // --------------------------------------------------------------- repayment

    @Nested
    @DisplayName("makeRepayment")
    class Repayment {

        @Test
        @DisplayName("allocates a scheduled instalment to interest first, then principal")
        void allocatesInterestBeforePrincipal() {
            AmortizationSchedule due = scheduleEntry(1, "860.66", "810.66", "50.00");

            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(due), List.of(scheduleEntry(2, "860.66", "814.71", "45.95")));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID));

            verify(repaymentRepository).save(savedRepayment.capture());
            LoanRepayment repayment = savedRepayment.getValue();

            assertThat(repayment.getInterestPaid()).isEqualByComparingTo("50.00");
            assertThat(repayment.getPrincipalPaid()).isEqualByComparingTo("810.66");
            assertThat(repayment.getAmount()).isEqualByComparingTo("860.66");
            assertThat(repayment.getPaymentNumber()).isEqualTo(1);
            assertThat(repayment.getIsEarlyPayoff()).isFalse();
        }

        @Test
        @DisplayName("reduces the outstanding balance by the principal portion only")
        void reducesBalanceByPrincipalOnly() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(scheduleEntry(1, "860.66", "810.66", "50.00")),
                                List.of(scheduleEntry(2, "860.66", "814.71", "45.95")));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID));

            verify(loanRepository).save(savedLoan.capture());
            Loan result = savedLoan.getValue();

            assertThat(result.getRemainingBalance()).isEqualByComparingTo("9189.34");
            assertThat(result.getPaymentsMade()).isEqualTo(1);
            assertThat(result.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        }

        @Test
        @DisplayName("debits the nominated source account for the amount paid")
        void debitsSourceAccount() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(scheduleEntry(1, "860.66", "810.66", "50.00")),
                                List.of(scheduleEntry(2, "860.66", "814.71", "45.95")));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID));

            verify(accountClient).debit(eq(ACCOUNT_ID), eq(new BigDecimal("860.66")), any());
        }

        @Test
        @DisplayName("leaves the source account untouched when no account is supplied")
        void skipsDebitWhenNoSourceAccount() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(scheduleEntry(1, "860.66", "810.66", "50.00")),
                                List.of(scheduleEntry(2, "860.66", "814.71", "45.95")));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", null));

            verify(accountClient, never()).debit(anyLong(), any(), any());
        }

        @Test
        @DisplayName("marks the instalment PARTIAL when less than the scheduled payment arrives")
        void underpaymentMarksInstalmentPartial() {
            AmortizationSchedule due = scheduleEntry(1, "860.66", "810.66", "50.00");

            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(due), List.of(scheduleEntry(2, "860.66", "814.71", "45.95")));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("100.00", ACCOUNT_ID));

            assertThat(due.getStatus()).isEqualTo(ScheduleStatus.PARTIAL);
            assertThat(due.getPaidAt()).isNotNull();

            verify(repaymentRepository).save(savedRepayment.capture());
            // interest is satisfied first, so only the remainder touches principal
            assertThat(savedRepayment.getValue().getInterestPaid()).isEqualByComparingTo("50.00");
            assertThat(savedRepayment.getValue().getPrincipalPaid()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("marks the instalment PAID when the scheduled payment is met")
        void fullPaymentMarksInstalmentPaid() {
            AmortizationSchedule due = scheduleEntry(1, "860.66", "810.66", "50.00");

            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(due), List.of(scheduleEntry(2, "860.66", "814.71", "45.95")));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID));

            assertThat(due.getStatus()).isEqualTo(ScheduleStatus.PAID);
        }

        @Test
        @DisplayName("closes the loan once the final instalment clears the schedule")
        void finalInstalmentClosesLoan() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "856.38")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(scheduleEntry(12, "860.66", "856.38", "4.28")), List.of());
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID));

            verify(loanRepository).save(savedLoan.capture());
            Loan result = savedLoan.getValue();

            assertThat(result.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
            assertThat(result.getNextPaymentDate()).isNull();
            assertThat(result.getRemainingBalance()).isEqualByComparingTo("0.00");
            verify(eventProducer).publishLoanPaidOff(LOAN_ID, USER_ID);
        }

        @Test
        @DisplayName("advances the next payment date to the following instalment")
        void advancesNextPaymentDate() {
            AmortizationSchedule next = scheduleEntry(2, "860.66", "814.71", "45.95");

            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(scheduleEntry(1, "860.66", "810.66", "50.00")), List.of(next));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID));

            verify(loanRepository).save(savedLoan.capture());
            assertThat(savedLoan.getValue().getNextPaymentDate()).isEqualTo(next.getDueDate());
            verify(eventProducer, never()).publishLoanPaidOff(anyLong(), anyLong());
        }

        @Test
        @DisplayName("never accepts more than the balance plus the interest currently due")
        void overpaymentIsCappedAtPayoffAmount() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "500.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(scheduleEntry(12, "502.50", "500.00", "2.50")), List.of());
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.makeRepayment(LOAN_ID, repaymentRequest("5000.00", ACCOUNT_ID));

            verify(repaymentRepository).save(savedRepayment.capture());
            // capped at remaining balance (500.00) + interest due (2.50)
            assertThat(savedRepayment.getValue().getAmount()).isEqualByComparingTo("502.50");
        }

        @Test
        @DisplayName("refuses repayment on a loan that is not active")
        void refusesRepaymentOnInactiveLoan() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.PENDING, "10000.00")));

            assertThatThrownBy(() -> loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID)))
                    .isInstanceOf(LoanNotActiveException.class)
                    .hasMessageContaining("PENDING");

            verify(accountClient, never()).debit(anyLong(), any(), any());
        }

        @Test
        @DisplayName("fails when the schedule holds nothing left to pay")
        void failsWhenNothingLeftToPay() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "10000.00")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING)).thenReturn(List.of());

            assertThatThrownBy(() -> loanService.makeRepayment(LOAN_ID, repaymentRequest("860.66", ACCOUNT_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No pending payments");
        }
    }

    // ------------------------------------------------------------ early payoff

    @Nested
    @DisplayName("earlyPayoff")
    class EarlyPayoff {

        @Test
        @DisplayName("settles the outstanding balance plus one period of accrued interest")
        void settlesBalancePlusAccruedInterest() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "9189.34")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING)).thenReturn(List.of());
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.earlyPayoff(LOAN_ID, repaymentRequest("0.01", ACCOUNT_ID));

            // 9,189.34 x (6.00 / 1200) = 45.95 accrued; payoff = 9,235.29
            verify(accountClient).debit(eq(ACCOUNT_ID), eq(new BigDecimal("9235.29")), any());

            verify(repaymentRepository).save(savedRepayment.capture());
            assertThat(savedRepayment.getValue().getAmount()).isEqualByComparingTo("9235.29");
            assertThat(savedRepayment.getValue().getInterestPaid()).isEqualByComparingTo("45.95");
            assertThat(savedRepayment.getValue().getIsEarlyPayoff()).isTrue();
        }

        @Test
        @DisplayName("closes the loan and clears the outstanding balance")
        void closesTheLoan() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "9189.34")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING)).thenReturn(List.of());
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.earlyPayoff(LOAN_ID, repaymentRequest("0.01", ACCOUNT_ID));

            verify(loanRepository).save(savedLoan.capture());
            Loan result = savedLoan.getValue();

            assertThat(result.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
            assertThat(result.getRemainingBalance()).isEqualByComparingTo("0.00");
            assertThat(result.getNextPaymentDate()).isNull();
            verify(eventProducer).publishLoanPaidOff(LOAN_ID, USER_ID);
        }

        @Test
        @DisplayName("marks every outstanding instalment as paid")
        void marksRemainingInstalmentsPaid() {
            AmortizationSchedule eleven = scheduleEntry(11, "860.66", "856.38", "4.28");
            AmortizationSchedule twelve = scheduleEntry(12, "860.66", "860.66", "0.00");

            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "9189.34")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(eleven, twelve));
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.earlyPayoff(LOAN_ID, repaymentRequest("0.01", ACCOUNT_ID));

            assertThat(eleven.getStatus()).isEqualTo(ScheduleStatus.PAID);
            assertThat(twelve.getStatus()).isEqualTo(ScheduleStatus.PAID);
            assertThat(eleven.getPaidAt()).isNotNull();
        }

        @Test
        @DisplayName("refuses early payoff on a loan that is not active")
        void refusesPayoffOnInactiveLoan() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.PAID_OFF, "0.00")));

            assertThatThrownBy(() -> loanService.earlyPayoff(LOAN_ID, repaymentRequest("100.00", ACCOUNT_ID)))
                    .isInstanceOf(LoanNotActiveException.class)
                    .hasMessageContaining("PAID_OFF");

            verify(accountClient, never()).debit(anyLong(), any(), any());
        }

        /**
         * Documents a known defect rather than endorsing it: {@code earlyPayoff} zeroes
         * {@code remainingBalance} on the loan before reading it back into the repayment
         * record, so the stored {@code principalPaid} is always zero even though the
         * customer did repay the principal. The payoff amount itself is correct, so this
         * affects reporting rather than money movement. Tracked in the README roadmap.
         */
        @Test
        @DisplayName("known defect: records zero principal paid on the payoff record")
        void knownDefectPrincipalPaidRecordedAsZero() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "9189.34")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING)).thenReturn(List.of());
            when(repaymentRepository.save(any(LoanRepayment.class))).thenAnswer(i -> i.getArgument(0));

            loanService.earlyPayoff(LOAN_ID, repaymentRequest("0.01", ACCOUNT_ID));

            verify(repaymentRepository).save(savedRepayment.capture());
            assertThat(savedRepayment.getValue().getPrincipalPaid()).isEqualByComparingTo("0.00");
        }
    }

    // ------------------------------------------------------------ payoff quote

    @Nested
    @DisplayName("getPayoffQuote")
    class PayoffQuote {

        @Test
        @DisplayName("quotes the balance, the accrued interest and the instalments still outstanding")
        void quotesSettlementFigures() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.of(loan(LoanStatus.ACTIVE, "9189.34")));
            when(scheduleRepository.findByLoanIdAndStatus(LOAN_ID, ScheduleStatus.PENDING))
                    .thenReturn(List.of(
                            scheduleEntry(11, "860.66", "856.38", "4.28"),
                            scheduleEntry(12, "860.66", "860.66", "0.00")));

            PayoffQuoteResponse quote = loanService.getPayoffQuote(LOAN_ID);

            assertThat(quote.getLoanId()).isEqualTo(LOAN_ID);
            assertThat(quote.getRemainingBalance()).isEqualByComparingTo("9189.34");
            assertThat(quote.getAccruedInterest()).isEqualByComparingTo("45.95");
            assertThat(quote.getTotalPayoffAmount()).isEqualByComparingTo("9235.29");
            assertThat(quote.getPaymentsRemaining()).isEqualTo(2);
            assertThat(quote.getQuoteDate()).isEqualTo(LocalDate.now());
        }
    }

    // ------------------------------------------------------------------ lookup

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("an unknown loan id is reported as not found")
        void unknownLoanIsNotFound() {
            when(loanRepository.findById(LOAN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> loanService.getLoan(LOAN_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(String.valueOf(LOAN_ID));
        }
    }
}

package com.bankingplatform.loan.service.impl;

import com.bankingplatform.loan.client.AccountClient;
import com.bankingplatform.loan.dto.request.CreateLoanRequest;
import com.bankingplatform.loan.dto.request.DisburseRequest;
import com.bankingplatform.loan.dto.request.LoanRepaymentRequest;
import com.bankingplatform.loan.dto.response.*;
import com.bankingplatform.loan.exception.LoanNotActiveException;
import com.bankingplatform.loan.exception.ResourceNotFoundException;
import com.bankingplatform.loan.kafka.producer.LoanEventProducer;
import com.bankingplatform.loan.mapper.AmortizationMapper;
import com.bankingplatform.loan.mapper.LoanMapper;
import com.bankingplatform.loan.mapper.LoanRepaymentMapper;
import com.bankingplatform.loan.model.*;
import com.bankingplatform.loan.repository.AmortizationScheduleRepository;
import com.bankingplatform.loan.repository.LoanRepaymentRepository;
import com.bankingplatform.loan.repository.LoanRepository;
import com.bankingplatform.loan.service.LoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class LoanServiceImpl implements LoanService {

    private final LoanRepository loanRepository;
    private final AmortizationScheduleRepository scheduleRepository;
    private final LoanRepaymentRepository repaymentRepository;
    private final AccountClient accountClient;
    private final LoanEventProducer eventProducer;
    private final LoanMapper loanMapper;
    private final AmortizationMapper amortizationMapper;
    private final LoanRepaymentMapper repaymentMapper;

    @Override
    public LoanResponse createLoan(CreateLoanRequest request) {
        BigDecimal monthlyRate = request.getInterestRate()
                .divide(new BigDecimal("1200"), 10, RoundingMode.HALF_UP);
        BigDecimal monthlyPayment = calculateMonthlyPayment(request.getPrincipal(), monthlyRate, request.getTermMonths());
        BigDecimal totalPayable = monthlyPayment.multiply(BigDecimal.valueOf(request.getTermMonths()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalInterest = totalPayable.subtract(request.getPrincipal());

        Loan loan = Loan.builder()
                .userId(request.getUserId())
                .applicationId(request.getApplicationId())
                .loanType(request.getLoanType())
                .principal(request.getPrincipal())
                .interestRate(request.getInterestRate())
                .termMonths(request.getTermMonths())
                .monthlyPayment(monthlyPayment)
                .totalInterest(totalInterest)
                .remainingBalance(request.getPrincipal())
                .disbursementAccountId(request.getDisbursementAccountId())
                .status(LoanStatus.PENDING)
                .build();

        loan = loanRepository.save(loan);
        buildAmortizationSchedule(loan, monthlyPayment, monthlyRate);
        // The confirmation that closes the loop. application-service asked for
        // this loan by publishing an approval and has been waiting at
        // PROVISIONING ever since; this is what lets it record the real id.
        eventProducer.publishLoanCreated(loan.getId(), loan.getUserId(), loan.getApplicationId(),
                loan.getLoanType().name(), loan.getPrincipal(), loan.getInterestRate(),
                loan.getTermMonths());
        log.info("Created loan id={} for userId={}", loan.getId(), loan.getUserId());
        return loanMapper.toResponse(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public LoanResponse getLoan(Long loanId) {
        return loanMapper.toResponse(findLoan(loanId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanResponse> getLoansByUser(Long userId) {
        return loanRepository.findByUserId(userId).stream().map(loanMapper::toResponse).toList();
    }

    @Override
    public LoanResponse disburseLoan(Long loanId, DisburseRequest request) {
        Loan loan = findLoanForUpdate(loanId);
        if (loan.getStatus() != LoanStatus.PENDING) {
            throw new LoanNotActiveException("Loan not in PENDING state: " + loan.getStatus());
        }

        // Keyed by the loan, because a loan is disbursed once. A retry of the
        // same disbursement carries the same key; there is no second
        // disbursement of one loan for it to collide with.
        accountClient.credit(request.getDisbursementAccountId(),
                "loan-disburse-" + loanId, loan.getPrincipal(),
                "Loan disbursement — loanId=" + loanId);

        loan.setDisbursementAccountId(request.getDisbursementAccountId());
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setDisbursedAt(LocalDateTime.now());
        loan.setNextPaymentDate(LocalDate.now().plusMonths(1));

        loan = loanRepository.save(loan);
        eventProducer.publishLoanDisbursed(loan.getId(), loan.getUserId(),
                loan.getPrincipal(), request.getDisbursementAccountId());
        return loanMapper.toResponse(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AmortizationScheduleResponse> getAmortizationSchedule(Long loanId) {
        findLoan(loanId);
        return scheduleRepository.findByLoanIdOrderByPaymentNumber(loanId).stream()
                .map(amortizationMapper::toResponse)
                .toList();
    }

    @Override
    public LoanRepaymentResponse makeRepayment(Long loanId, LoanRepaymentRequest request) {
        Loan loan = findLoanForUpdate(loanId);
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw new LoanNotActiveException("Loan is not ACTIVE: " + loan.getStatus());
        }

        // The next unpaid instalment, in payment order. The ordering used to
        // be whatever PostgreSQL returned, which decided the interest and
        // principal split and the payment number on the repayment record.
        List<AmortizationSchedule> pending =
                scheduleRepository.findByLoanIdAndStatusOrderByPaymentNumberAsc(
                        loanId, ScheduleStatus.PENDING);
        if (pending.isEmpty()) {
            throw new IllegalStateException("No pending payments found");
        }
        AmortizationSchedule nextDue = pending.get(0);

        BigDecimal interestDue = nextDue.getInterestPortion();
        BigDecimal principalDue = nextDue.getPrincipalPortion();
        BigDecimal payAmount = request.getAmount().min(loan.getRemainingBalance().add(interestDue));

        // Allocate: interest first, remainder to principal
        BigDecimal interestPaid = payAmount.min(interestDue);
        BigDecimal principalPaid = payAmount.subtract(interestPaid);

        // Minted before the debit so it names this repayment, and reused as
        // the repayment's own reference below.
        String paymentRef = generateRef();

        if (request.getSourceAccountId() != null) {
            accountClient.debit(request.getSourceAccountId(), "loan-" + paymentRef,
                    payAmount, "Loan repayment — loanId=" + loanId);
        }

        loan.setRemainingBalance(loan.getRemainingBalance().subtract(principalPaid).max(BigDecimal.ZERO));
        loan.setPaymentsMade(loan.getPaymentsMade() + 1);

        // Mark schedule entry
        nextDue.setStatus(payAmount.compareTo(nextDue.getScheduledPayment()) >= 0
                ? ScheduleStatus.PAID : ScheduleStatus.PARTIAL);
        nextDue.setPaidAt(LocalDateTime.now());
        scheduleRepository.save(nextDue);

        // Advance next payment date
        List<AmortizationSchedule> remaining =
                scheduleRepository.findByLoanIdAndStatusOrderByPaymentNumberAsc(
                        loanId, ScheduleStatus.PENDING);
        if (remaining.isEmpty()) {
            loan.setStatus(LoanStatus.PAID_OFF);
            loan.setNextPaymentDate(null);
        } else {
            loan.setNextPaymentDate(remaining.get(0).getDueDate());
        }
        loanRepository.save(loan);

        LoanRepayment repayment = repaymentRepository.save(LoanRepayment.builder()
                .loan(loan)
                .paymentRef(paymentRef)
                .amount(payAmount)
                .principalPaid(principalPaid)
                .interestPaid(interestPaid)
                .sourceAccountId(request.getSourceAccountId())
                .paymentNumber(nextDue.getPaymentNumber())
                .isEarlyPayoff(false)
                .build());

        eventProducer.publishRepaymentMade(loanId, loan.getUserId(), repayment.getPaymentRef(),
                payAmount, loan.getRemainingBalance());

        if (loan.getStatus() == LoanStatus.PAID_OFF) {
            eventProducer.publishLoanPaidOff(loanId, loan.getUserId());
        }
        return repaymentMapper.toResponse(repayment);
    }

    @Override
    public LoanRepaymentResponse earlyPayoff(Long loanId, LoanRepaymentRequest request) {
        Loan loan = findLoanForUpdate(loanId);
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw new LoanNotActiveException("Loan is not ACTIVE: " + loan.getStatus());
        }

        // Capture the outstanding principal before the balance is cleared below,
        // so the repayment record reports what was actually settled.
        BigDecimal principalPaid = loan.getRemainingBalance();

        // Accrued interest for current period
        BigDecimal monthlyRate = loan.getInterestRate().divide(new BigDecimal("1200"), 10, RoundingMode.HALF_UP);
        BigDecimal accruedInterest = principalPaid.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal payoffAmount = principalPaid.add(accruedInterest);

        String payoffRef = generateRef();

        if (request.getSourceAccountId() != null) {
            accountClient.debit(request.getSourceAccountId(), "loan-" + payoffRef,
                    payoffAmount, "Early loan payoff — loanId=" + loanId);
        }

        // Mark all remaining schedule entries PAID
        scheduleRepository.findByLoanIdAndStatusOrderByPaymentNumberAsc(loanId, ScheduleStatus.PENDING).forEach(s -> {
            s.setStatus(ScheduleStatus.PAID);
            s.setPaidAt(LocalDateTime.now());
        });

        loan.setRemainingBalance(BigDecimal.ZERO);
        loan.setStatus(LoanStatus.PAID_OFF);
        loan.setNextPaymentDate(null);
        loanRepository.save(loan);

        LoanRepayment repayment = repaymentRepository.save(LoanRepayment.builder()
                .loan(loan)
                .paymentRef(payoffRef)
                .amount(payoffAmount)
                .principalPaid(principalPaid)
                .interestPaid(accruedInterest)
                .sourceAccountId(request.getSourceAccountId())
                .isEarlyPayoff(true)
                .build());

        eventProducer.publishRepaymentMade(loanId, loan.getUserId(), repayment.getPaymentRef(),
                payoffAmount, BigDecimal.ZERO);
        eventProducer.publishLoanPaidOff(loanId, loan.getUserId());
        return repaymentMapper.toResponse(repayment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanRepaymentResponse> getRepayments(Long loanId) {
        findLoan(loanId);
        return repaymentRepository.findByLoanIdOrderByCreatedAtDesc(loanId).stream()
                .map(repaymentMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PayoffQuoteResponse getPayoffQuote(Long loanId) {
        Loan loan = findLoan(loanId);
        BigDecimal monthlyRate = loan.getInterestRate().divide(new BigDecimal("1200"), 10, RoundingMode.HALF_UP);
        BigDecimal accruedInterest = loan.getRemainingBalance().multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal payoffAmount = loan.getRemainingBalance().add(accruedInterest);

        int remaining = (int) scheduleRepository.findByLoanIdAndStatusOrderByPaymentNumberAsc(loanId, ScheduleStatus.PENDING).size();

        PayoffQuoteResponse quote = new PayoffQuoteResponse();
        quote.setLoanId(loanId);
        quote.setRemainingBalance(loan.getRemainingBalance());
        quote.setAccruedInterest(accruedInterest);
        quote.setTotalPayoffAmount(payoffAmount);
        quote.setQuoteDate(LocalDate.now());
        quote.setPaymentsRemaining(remaining);
        return quote;
    }

    @Override
    public void markMissedPayments() {
        List<AmortizationSchedule> due = scheduleRepository.findDuePayments(LocalDate.now().minusDays(1));
        for (AmortizationSchedule schedule : due) {
            schedule.setStatus(ScheduleStatus.MISSED);
            scheduleRepository.save(schedule);
            Loan loan = schedule.getLoan();
            if (loan.getStatus() == LoanStatus.ACTIVE) {
                log.warn("Missed payment on loanId={}, paymentNumber={}", loan.getId(), schedule.getPaymentNumber());
            }
        }
    }

    // --- helpers ---

    private Loan findLoan(Long loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found: " + loanId));
    }

    /**
     * The loan, locked for the rest of the transaction.
     *
     * <p>Used by every path that changes a balance or an instalment. Picking
     * the next unpaid instalment, doing the arithmetic and writing both back
     * have to be one atomic act; an unlocked read makes them three.
     */
    private Loan findLoanForUpdate(Long loanId) {
        return loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found: " + loanId));
    }

    private BigDecimal calculateMonthlyPayment(BigDecimal principal, BigDecimal monthlyRate, int termMonths) {
        // M = P * [r(1+r)^n] / [(1+r)^n - 1]
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal onePlusRPowN = onePlusR.pow(termMonths, new MathContext(15));
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(onePlusRPowN);
        BigDecimal denominator = onePlusRPowN.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private void buildAmortizationSchedule(Loan loan, BigDecimal monthlyPayment, BigDecimal monthlyRate) {
        List<AmortizationSchedule> schedules = new ArrayList<>();
        BigDecimal balance = loan.getPrincipal();
        LocalDate dueDate = LocalDate.now().plusMonths(1);

        for (int i = 1; i <= loan.getTermMonths(); i++) {
            BigDecimal interest = balance.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal payment = (i == loan.getTermMonths())
                    ? balance.add(interest)   // last payment clears remaining balance
                    : monthlyPayment;
            BigDecimal principal = payment.subtract(interest);
            balance = balance.subtract(principal).max(BigDecimal.ZERO);

            schedules.add(AmortizationSchedule.builder()
                    .loan(loan)
                    .paymentNumber(i)
                    .dueDate(dueDate)
                    .scheduledPayment(payment.setScale(2, RoundingMode.HALF_UP))
                    .principalPortion(principal.setScale(2, RoundingMode.HALF_UP))
                    .interestPortion(interest)
                    .remainingBalance(balance.setScale(2, RoundingMode.HALF_UP))
                    .status(ScheduleStatus.PENDING)
                    .build());
            dueDate = dueDate.plusMonths(1);
        }
        scheduleRepository.saveAll(schedules);
    }

    private String generateRef() {
        String ref;
        do {
            ref = UUID.randomUUID().toString();
        } while (repaymentRepository.existsByPaymentRef(ref));
        return ref;
    }
}

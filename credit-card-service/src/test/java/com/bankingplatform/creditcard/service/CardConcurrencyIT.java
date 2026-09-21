package com.bankingplatform.creditcard.service;

import com.bankingplatform.creditcard.client.AccountClient;
import com.bankingplatform.creditcard.dto.request.PurchaseRequest;
import com.bankingplatform.creditcard.exception.InsufficientCreditException;
import com.bankingplatform.creditcard.kafka.producer.CreditCardEventProducer;
import com.bankingplatform.creditcard.mapper.CreditCardMapper;
import com.bankingplatform.creditcard.mapper.CreditCardStatementMapper;
import com.bankingplatform.creditcard.mapper.CreditCardTransactionMapper;
import com.bankingplatform.creditcard.model.CardStatus;
import com.bankingplatform.creditcard.model.CardType;
import com.bankingplatform.creditcard.model.CreditCard;
import com.bankingplatform.creditcard.repository.CreditCardRepository;
import com.bankingplatform.creditcard.repository.CreditCardStatementRepository;
import com.bankingplatform.creditcard.repository.CreditCardTransactionRepository;
import com.bankingplatform.creditcard.service.impl.CreditCardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A credit limit is only a limit if concurrent purchases cannot both pass it.
 *
 * <p>Against a real PostgreSQL, because the control is a row lock. Every card
 * money path reads the card, checks the amount against the available credit,
 * and writes a balance derived from what it read. Those three steps have to be
 * one act. Unlocked they are three, and two purchases arriving together both
 * read the same available credit, both pass the check, and both write —
 * leaving the card over its limit by the smaller amount, with nothing
 * recording that it happened.
 *
 * <p>A mocked repository cannot show this. It would show that the service
 * called {@code save}.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The threads below have to see each other's commits, so this test must not
// run inside a transaction that rolls back at the end.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Credit card concurrency — PostgreSQL integration")
class CardConcurrencyIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credit_card_db")
            .withUsername("bankingadmin")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("1000.00");
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("100.00");
    private static final int THREADS = 16;

    @Autowired private CreditCardRepository cardRepository;
    @Autowired private CreditCardTransactionRepository txRepository;
    @Autowired private CreditCardStatementRepository statementRepository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private CreditCardServiceImpl service;
    private TransactionTemplate inTransaction;
    private Long cardId;

    @BeforeEach
    void setUp() {
        // Built by hand rather than pulled from a context: what is under test
        // is the repository lock and the arithmetic around it, and the rest of
        // the service's collaborators only need to not get in the way.
        service = new CreditCardServiceImpl(
                cardRepository, txRepository, statementRepository,
                Mockito.mock(AccountClient.class),
                Mockito.mock(CreditCardEventProducer.class),
                Mockito.mock(CreditCardMapper.class),
                Mockito.mock(CreditCardTransactionMapper.class),
                Mockito.mock(CreditCardStatementMapper.class));
        inTransaction = new TransactionTemplate(transactions);

        jdbc.update("DELETE FROM credit_card_transactions");
        jdbc.update("DELETE FROM credit_cards");
        cardId = inTransaction.execute(status -> cardRepository.save(CreditCard.builder()
                .cardNumber("4000000000000000")
                .userId(42L)
                .cardType(CardType.STANDARD)
                .creditLimit(CREDIT_LIMIT)
                .availableCredit(CREDIT_LIMIT)
                .currentBalance(BigDecimal.ZERO)
                .statementBalance(BigDecimal.ZERO)
                .minimumPaymentDue(BigDecimal.ZERO)
                .paymentDueDate(LocalDate.now().plusDays(21))
                .apr(new BigDecimal("19.99"))
                .dailyRate(new BigDecimal("0.000548"))
                .billingCycleDay(1)
                .status(CardStatus.ACTIVE)
                .currency("USD")
                .rewardsPoints(0)
                .build()).getId());
    }

    private PurchaseRequest purchase() {
        PurchaseRequest request = new PurchaseRequest();
        request.setAmount(PURCHASE_AMOUNT);
        request.setDescription("Concurrent purchase");
        request.setMerchantName("Test Merchant");
        request.setMerchantCategory("RETAIL");
        return request;
    }

    private CreditCard reread() {
        return cardRepository.findById(cardId).orElseThrow();
    }

    @Test
    @DisplayName("concurrent purchases cannot take the card past its credit limit")
    void concurrentPurchasesRespectTheCreditLimit() throws Exception {
        // Sixteen threads, ten of which can fit: the limit is 1000 and each
        // purchase is 100. Without the lock more than ten get through, because
        // they all read an available credit that is already spent.
        CyclicBarrier start = new CyclicBarrier(THREADS);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                try {
                    inTransaction.executeWithoutResult(s -> service.purchase(cardId, purchase()));
                    accepted.incrementAndGet();
                } catch (InsufficientCreditException expected) {
                    refused.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> results = pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        for (Future<Void> result : results) {
            // Anything other than a clean accept or a clean refusal — a
            // deadlock, an optimistic-lock failure — surfaces here rather than
            // being hidden by the counters.
            result.get();
        }

        CreditCard card = reread();

        assertThat(accepted.get() + refused.get()).isEqualTo(THREADS);
        assertThat(accepted.get())
                .as("ten purchases of 100 fit inside a limit of 1000")
                .isEqualTo(10);
        assertThat(card.getCurrentBalance())
                .as("the balance is exactly what was accepted, not what was attempted")
                .isEqualByComparingTo(PURCHASE_AMOUNT.multiply(BigDecimal.valueOf(accepted.get())));
        assertThat(card.getCurrentBalance())
                .as("never past the credit limit")
                .isLessThanOrEqualTo(CREDIT_LIMIT);
        assertThat(card.getAvailableCredit())
                .isEqualByComparingTo(CREDIT_LIMIT.subtract(card.getCurrentBalance()));
    }

    @Test
    @DisplayName("every accepted purchase leaves a transaction, and no refused one does")
    void theHistoryMatchesTheBalance() throws Exception {
        // The balance and the history are written in the same transaction, so
        // a lost update would show up as a balance that no set of recorded
        // transactions adds up to.
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                try {
                    inTransaction.executeWithoutResult(s -> service.purchase(cardId, purchase()));
                } catch (InsufficientCreditException expected) {
                    // Counted by the balance assertion below, not here.
                }
                return null;
            });
        }
        pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        BigDecimal recorded = jdbc.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM credit_card_transactions "
                        + "WHERE credit_card_id = ? AND type = 'PURCHASE'",
                BigDecimal.class, cardId);

        assertThat(reread().getCurrentBalance())
                .as("the balance is the sum of the purchases that were recorded")
                .isEqualByComparingTo(recorded);
    }

    @Test
    @DisplayName("available credit and balance always add up to the limit")
    void theTwoHalvesOfTheLimitStayConsistent() throws Exception {
        // These two columns are maintained separately in Java, so a lost
        // update can leave them disagreeing even when neither is obviously
        // wrong on its own.
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                try {
                    inTransaction.executeWithoutResult(s -> service.purchase(cardId, purchase()));
                } catch (InsufficientCreditException expected) {
                    // Expected once the limit is reached.
                }
                return null;
            });
        }
        pool.invokeAll(attempts);
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        CreditCard card = reread();
        assertThat(card.getCurrentBalance().add(card.getAvailableCredit()))
                .isEqualByComparingTo(CREDIT_LIMIT);
    }
}

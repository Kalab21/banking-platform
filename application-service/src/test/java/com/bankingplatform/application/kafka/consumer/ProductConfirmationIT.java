package com.bankingplatform.application.kafka.consumer;

import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.common.events.LoanCreated;
import com.bankingplatform.common.kafka.inbox.JdbcProcessedEventGuard;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirming provisioning against a real PostgreSQL.
 *
 * <p>This exists because of a defect a unit test could not have caught. The
 * confirmation consumer claims each event through {@code ProcessedEventGuard},
 * which writes to a {@code processed_event} table — and this service had never
 * had one, because until it started confirming products it consumed nothing at
 * all. With the guard mocked, every unit test passed. Against a real database
 * the first confirmation failed with
 * {@code relation "processed_event" does not exist}, retried four times, went
 * to the dead letter topic, and left the application at {@code PROVISIONING}
 * for ever — the exact state the confirmation exists to move it out of.
 *
 * <p>So the schema is part of the contract here, and the only way to test it is
 * to run the migrations.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductConfirmationIT.GuardConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Provisioning confirmation — PostgreSQL integration")
class ProductConfirmationIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("application_db")
            .withUsername("bankingadmin")
            .withPassword("bankingpass");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ProcessedEventGuard processedEvents;

    @Autowired
    private PlatformTransactionManager transactions;

    private ProductCreatedConsumer consumer;
    private TransactionTemplate inTransaction;

    @BeforeEach
    void setUp() {
        consumer = new ProductCreatedConsumer(applicationRepository, processedEvents);
        inTransaction = new TransactionTemplate(transactions);
    }

    private Long provisioningApplication() {
        return inTransaction.execute(status -> applicationRepository.save(Application.builder()
                .userId(7L)
                .applicationType(ApplicationType.PERSONAL_LOAN)
                .requestedAmount(new BigDecimal("10000.00"))
                .termMonths(36)
                .currency("USD")
                .status(ApplicationStatus.PROVISIONING)
                .build()).getId());
    }

    private LoanCreated confirmation(Long applicationId) {
        return LoanCreated.of(555L, 7L, applicationId, "PERSONAL_LOAN",
                new BigDecimal("8000.00"), new BigDecimal("10.99"), 36);
    }

    @Test
    @DisplayName("a confirmation records the real product id and completes provisioning")
    void confirmationCompletesProvisioning() {
        Long applicationId = provisioningApplication();

        inTransaction.executeWithoutResult(status -> consumer.onLoanEvent(confirmation(applicationId)));

        Application confirmed = inTransaction.execute(status ->
                applicationRepository.findById(applicationId).orElseThrow());
        assertThat(confirmed.getStatus()).isEqualTo(ApplicationStatus.PROVISIONED);
        assertThat(confirmed.getProductId()).isEqualTo(555L);
    }

    @Test
    @DisplayName("the same confirmation delivered twice provisions once")
    void redeliveryProvisionsOnce() {
        Long applicationId = provisioningApplication();
        LoanCreated event = confirmation(applicationId);

        inTransaction.executeWithoutResult(status -> consumer.onLoanEvent(event));
        // Same event id: the guard's primary key is what refuses the second.
        inTransaction.executeWithoutResult(status -> consumer.onLoanEvent(event));

        Application confirmed = inTransaction.execute(status ->
                applicationRepository.findById(applicationId).orElseThrow());
        assertThat(confirmed.getStatus()).isEqualTo(ApplicationStatus.PROVISIONED);
        assertThat(confirmed.getProductId()).isEqualTo(555L);
    }

    /** @DataJpaTest does not load the guard's auto-configuration. */
    @TestConfiguration
    static class GuardConfig {

        @Bean
        ProcessedEventGuard processedEventGuard(JdbcTemplate jdbc) {
            return new JdbcProcessedEventGuard(jdbc);
        }
    }

    @Test
    @DisplayName("the guard's table exists, which is the whole point of this test")
    void guardTableExists() {
        // Named explicitly so a failure reads as "the migration is missing"
        // rather than as a mysterious listener error four retries later. The
        // claim is @Transactional(MANDATORY) by design — it has to be in the
        // same transaction as the work it guards — so it is called inside one.
        Boolean claimed = inTransaction.execute(status ->
                processedEvents.claim("application-service:test", "event-" + System.nanoTime()));
        assertThat(claimed).isTrue();
    }
}

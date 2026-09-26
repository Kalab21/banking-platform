package com.bankingplatform.application.service.impl;

import com.bankingplatform.application.exception.OfferException;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.model.Offer;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.OfferRepository;
import com.bankingplatform.application.service.OfferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Offer expiry against a real PostgreSQL and the real transaction proxy.
 *
 * <p>Accepting a lapsed offer used to mark it EXPIRED, save it, and then throw
 * inside the same transaction — so the throw rolled the save back and the
 * offer stayed OFFERED in storage for ever. A unit test with a mocked
 * repository saw the save call and passed. Only a committed transaction shows
 * whether the expiry was actually recorded.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OfferServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Offer expiry — PostgreSQL integration")
class OfferExpiryIT {

    private static final long OWNER = 7L;

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
    private OfferService offerService;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private OfferRepository offerRepository;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockBean
    private ApplicationEventProducer eventProducer;

    /** An OFFERED application whose offer lapsed yesterday, committed. */
    private Long lapsedOffer() {
        return new TransactionTemplate(transactions).execute(status -> {
            Application application = applicationRepository.save(Application.builder()
                    .userId(OWNER)
                    .applicationType(ApplicationType.PERSONAL_LOAN)
                    .requestedAmount(new BigDecimal("10000.00"))
                    .termMonths(36)
                    .currency("USD")
                    .status(ApplicationStatus.OFFERED)
                    .build());
            offerRepository.save(Offer.builder()
                    .applicationId(application.getId())
                    .userId(OWNER)
                    .productType(ApplicationType.PERSONAL_LOAN)
                    .approvedAmount(new BigDecimal("10000.00"))
                    .apr(new BigDecimal("9.9900"))
                    .termMonths(36)
                    .monthlyPayment(new BigDecimal("322.63"))
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build());
            return application.getId();
        });
    }

    private Offer storedOffer(Long applicationId) {
        return offerRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).get(0);
    }

    private void refusedAsExpired(Consumer<Long> act) {
        Long applicationId = lapsedOffer();

        assertThatThrownBy(() -> act.accept(applicationId))
                .isInstanceOf(OfferException.class)
                .hasMessageContaining("expired");

        assertThat(storedOffer(applicationId).getStatus()).isEqualTo(Offer.Status.EXPIRED);
        assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.OFFERED);
        verify(eventProducer, never()).publishApplicationApproved(anyLong(), anyLong(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("accepting a lapsed offer is refused, and the expiry is committed")
    void acceptRecordsExpiry() {
        refusedAsExpired(id -> offerService.accept(id, OWNER));
    }

    @Test
    @DisplayName("declining a lapsed offer is refused the same way")
    void declineRecordsExpiry() {
        refusedAsExpired(id -> offerService.decline(id, OWNER));
    }

    @Test
    @DisplayName("once recorded, an expired offer stays closed to both actions")
    void expiredStaysClosed() {
        Long applicationId = lapsedOffer();
        assertThatThrownBy(() -> offerService.accept(applicationId, OWNER))
                .isInstanceOf(OfferException.class);

        assertThatThrownBy(() -> offerService.decline(applicationId, OWNER))
                .isInstanceOf(OfferException.class)
                .hasMessageContaining("expired");
        assertThat(storedOffer(applicationId).getStatus()).isEqualTo(Offer.Status.EXPIRED);
    }
}

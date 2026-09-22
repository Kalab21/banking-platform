package com.bankingplatform.application.service.impl;

import com.bankingplatform.application.exception.OfferException;
import com.bankingplatform.application.exception.ResourceNotFoundException;
import com.bankingplatform.application.kafka.producer.ApplicationEventProducer;
import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.model.Offer;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.application.repository.OfferRepository;
import com.bankingplatform.application.underwriting.OfferedTerms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An offer, and what may be done with it.
 *
 * <p>The accepted offer is the source of truth for the product. Before it
 * existed, {@code loan-service} chose the term from a switch on product type, so
 * a customer who asked for twelve months was written forty-eight and nothing
 * recorded that they had been offered anything else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Offer lifecycle")
class OfferLifecycleTest {

    private static final long APPLICATION_ID = 100L;
    private static final long OWNER = 7L;
    private static final long SOMEONE_ELSE = 8L;

    @Mock private OfferRepository offerRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private ApplicationEventProducer eventProducer;

    @InjectMocks private OfferServiceImpl offerService;

    private Offer.OfferBuilder loanOffer() {
        return Offer.builder()
                .id(1L)
                .applicationId(APPLICATION_ID)
                .userId(OWNER)
                .productType(ApplicationType.PERSONAL_LOAN)
                .status(Offer.Status.OFFERED)
                .approvedAmount(new BigDecimal("8000.00"))
                .apr(new BigDecimal("10.99"))
                .termMonths(36)
                .monthlyPayment(new BigDecimal("261.96"))
                .currency("USD")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30));
    }

    private void storedOffer(Offer offer) {
        when(offerRepository.findByApplicationIdOrderByCreatedAtDesc(APPLICATION_ID))
                .thenReturn(List.of(offer));
        when(offerRepository.save(any(Offer.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void storedApplication(ApplicationStatus status) {
        Application application = Application.builder()
                .id(APPLICATION_ID)
                .userId(OWNER)
                .applicationType(ApplicationType.PERSONAL_LOAN)
                .requestedAmount(new BigDecimal("10000.00"))
                .termMonths(12)
                .currency("USD")
                .creditScoreAtApply(780)
                .status(status)
                .build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(Application.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    @DisplayName("accepting")
    class Accepting {

        @Test
        @DisplayName("the accepted terms are what goes downstream, not what was requested")
        void acceptedTermsArePublished() {
            // Asked for 10,000 over 12 months. Offered 8,000 over 36 at 10.99%.
            storedOffer(loanOffer().build());
            storedApplication(ApplicationStatus.OFFERED);

            offerService.accept(APPLICATION_ID, OWNER);

            ArgumentCaptor<BigDecimal> apr = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<Integer> term = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<BigDecimal> approved = ArgumentCaptor.forClass(BigDecimal.class);
            verify(eventProducer).publishApplicationApproved(
                    eq(APPLICATION_ID), eq(OWNER), eq("PERSONAL_LOAN"), any(), any(),
                    any(), approved.capture(), apr.capture(), term.capture(), any(), any());

            assertThat(approved.getValue()).isEqualByComparingTo(new BigDecimal("8000.00"));
            assertThat(apr.getValue()).isEqualByComparingTo(new BigDecimal("10.99"));
            assertThat(term.getValue()).isEqualTo(36);
        }

        @Test
        @DisplayName("the application moves to provisioning, not straight to provisioned")
        void movesToProvisioning() {
            storedOffer(loanOffer().build());
            storedApplication(ApplicationStatus.OFFERED);

            offerService.accept(APPLICATION_ID, OWNER);

            ArgumentCaptor<Application> saved = ArgumentCaptor.forClass(Application.class);
            verify(applicationRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(ApplicationStatus.PROVISIONING);
            assertThat(saved.getValue().getProductId()).isNull();
        }

        @Test
        @DisplayName("accepting twice provisions once")
        void acceptingTwiceIsIdempotent() {
            // A customer who double-clicks has not asked for two loans.
            Offer already = loanOffer()
                    .status(Offer.Status.ACCEPTED)
                    .acceptedAt(LocalDateTime.now())
                    .build();
            storedOffer(already);

            var response = offerService.accept(APPLICATION_ID, OWNER);

            assertThat(response.status()).isEqualTo("ACCEPTED");
            verify(eventProducer, never()).publishApplicationApproved(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a declined offer cannot later be accepted")
        void declinedCannotBeAccepted() {
            storedOffer(loanOffer()
                    .status(Offer.Status.DECLINED)
                    .declinedAt(LocalDateTime.now())
                    .build());

            assertThatThrownBy(() -> offerService.accept(APPLICATION_ID, OWNER))
                    .isInstanceOf(OfferException.class)
                    .hasMessageContaining("declined");
        }

        @Test
        @DisplayName("an expired offer is refused and recorded as expired")
        void expiredIsRefused() {
            storedOffer(loanOffer()
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build());

            assertThatThrownBy(() -> offerService.accept(APPLICATION_ID, OWNER))
                    .isInstanceOf(OfferException.class)
                    .hasMessageContaining("expired");

            ArgumentCaptor<Offer> saved = ArgumentCaptor.forClass(Offer.class);
            verify(offerRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(Offer.Status.EXPIRED);
        }

        @Test
        @DisplayName("another customer's offer is not found rather than forbidden")
        void anotherCustomersOfferIsNotVisible() {
            // Telling a stranger that an offer exists but is not theirs still
            // tells them it exists.
            storedOffer(loanOffer().build());

            assertThatThrownBy(() -> offerService.accept(APPLICATION_ID, SOMEONE_ELSE))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(eventProducer, never()).publishApplicationApproved(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an application with no offer cannot be accepted")
        void noOfferIsNotFound() {
            when(offerRepository.findByApplicationIdOrderByCreatedAtDesc(APPLICATION_ID))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> offerService.accept(APPLICATION_ID, OWNER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("declining")
    class Declining {

        @Test
        @DisplayName("declining creates no product and publishes nothing")
        void decliningCreatesNothing() {
            storedOffer(loanOffer().build());
            storedApplication(ApplicationStatus.OFFERED);

            offerService.decline(APPLICATION_ID, OWNER);

            ArgumentCaptor<Application> saved = ArgumentCaptor.forClass(Application.class);
            verify(applicationRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(ApplicationStatus.DECLINED);
            verify(eventProducer, never()).publishApplicationApproved(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("declining twice is the same as declining once")
        void decliningTwiceIsIdempotent() {
            storedOffer(loanOffer()
                    .status(Offer.Status.DECLINED)
                    .declinedAt(LocalDateTime.now())
                    .build());

            assertThat(offerService.decline(APPLICATION_ID, OWNER).status()).isEqualTo("DECLINED");
        }

        @Test
        @DisplayName("an accepted offer cannot then be declined")
        void acceptedCannotBeDeclined() {
            storedOffer(loanOffer()
                    .status(Offer.Status.ACCEPTED)
                    .acceptedAt(LocalDateTime.now())
                    .build());

            assertThatThrownBy(() -> offerService.decline(APPLICATION_ID, OWNER))
                    .isInstanceOf(OfferException.class)
                    .hasMessageContaining("accepted");
        }
    }

    @Nested
    @DisplayName("making an offer")
    class Making {

        @Test
        @DisplayName("a card offer carries a tier and a limit, and no term")
        void cardOfferShape() {
            Application application = Application.builder()
                    .id(APPLICATION_ID)
                    .userId(OWNER)
                    .applicationType(ApplicationType.CREDIT_CARD)
                    .currency("USD")
                    .build();
            when(offerRepository.save(any(Offer.class))).thenAnswer(i -> i.getArgument(0));

            Offer offer = offerService.offer(application,
                    new OfferedTerms(new BigDecimal("18.99"), null, null,
                            new BigDecimal("5000"), "GOLD"), 42L);

            assertThat(offer.getCardTier()).isEqualTo("GOLD");
            assertThat(offer.getCreditLimit()).isEqualByComparingTo(new BigDecimal("5000"));
            assertThat(offer.getTermMonths()).isNull();
            assertThat(offer.getDecisionSnapshotId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("an approved application that was never priced cannot be offered")
        void unpricedCannotBeOffered() {
            Application application = Application.builder()
                    .id(APPLICATION_ID)
                    .userId(OWNER)
                    .applicationType(ApplicationType.PERSONAL_LOAN)
                    .build();

            assertThatThrownBy(() -> offerService.offer(application, null, 1L))
                    .isInstanceOf(OfferException.class)
                    .hasMessageContaining("priced");
        }
    }
}

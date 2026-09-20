package com.bankingplatform.creditcard.kafka.consumer;

import com.bankingplatform.common.events.ApplicationApproved;
import com.bankingplatform.common.events.ApplicationRejected;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.creditcard.dto.request.CreateCreditCardRequest;
import com.bankingplatform.creditcard.model.CardType;
import com.bankingplatform.creditcard.service.CreditCardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issuing a card from an approval.
 *
 * <p>The credit score in the event decides the card tier, the limit and the
 * APR, so this consumer reading the wrong field would issue the wrong product.
 */
@DisplayName("Card issuance event contract")
class ApplicationEventContractTest {

    private ObjectMapper mapper;
    private CreditCardService creditCardService;
    private ApplicationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        creditCardService = Mockito.mock(CreditCardService.class);
        consumer = new ApplicationEventConsumer(creditCardService);
    }

    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    @Test
    @DisplayName("an approved card application issues a card matching the score")
    void approvedIssuesCard() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "CREDIT_CARD", null, 810, new BigDecimal("5000"))));

        ArgumentCaptor<CreateCreditCardRequest> captor =
                ArgumentCaptor.forClass(CreateCreditCardRequest.class);
        verify(creditCardService).createCard(captor.capture());

        CreateCreditCardRequest request = captor.getValue();
        assertThat(request.getUserId()).isEqualTo(42L);
        assertThat(request.getApplicationId()).isEqualTo(7L);
        assertThat(request.getCardType()).isEqualTo(CardType.PLATINUM);
        assertThat(request.getCreditLimit()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(request.getApr()).isEqualByComparingTo(new BigDecimal("14.99"));
    }

    @Test
    @DisplayName("a loan approval belongs to another service")
    void loanApprovalIgnored() throws Exception {
        consumer.onApplicationEvent(overTheWire(ApplicationApproved.of(
                7L, 42L, "PERSONAL_LOAN", null, 810, new BigDecimal("10000"))));

        verifyNoInteractions(creditCardService);
    }

    @Test
    @DisplayName("a rejection issues nothing")
    void rejectionIssuesNothing() throws Exception {
        consumer.onApplicationEvent(overTheWire(
                ApplicationRejected.of(7L, 42L, "CREDIT_CARD", "Score too low")));

        verifyNoInteractions(creditCardService);
    }

    @Test
    @DisplayName("an event type this service does not know is ignored")
    void unknownTypeIgnored() throws Exception {
        consumer.onApplicationEvent(mapper.readValue("""
                {"eventId":"abc","eventType":"SOMETHING_ADDED_LATER","eventVersion":1,
                 "occurredAt":"2026-01-01T00:00:00Z"}
                """, DomainEvent.class));

        verifyNoInteractions(creditCardService);
    }
}

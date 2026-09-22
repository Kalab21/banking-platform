package com.bankingplatform.application.kafka.consumer;

import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import com.bankingplatform.application.repository.ApplicationRepository;
import com.bankingplatform.common.events.CreditCardCreated;
import com.bankingplatform.common.events.DomainEvent;
import com.bankingplatform.common.events.LoanCreated;
import com.bankingplatform.common.kafka.inbox.ProcessedEventGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Closing the loop: an application says a product exists only once the service
 * that owns it says so.
 *
 * <p>The version before this wrote {@code PROVISIONED} the moment the request
 * was published — claiming a product existed on the strength of a message being
 * sent — and stored {@code product_id = -1} because there was no real id to
 * store. 153 rows on this machine said that.
 */
@DisplayName("Product creation confirmation")
class ProductCreatedConsumerTest {

    private static final long APPLICATION_ID = 100L;

    private ApplicationRepository applicationRepository;
    private ProcessedEventGuard processedEvents;
    private ProductCreatedConsumer consumer;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        applicationRepository = Mockito.mock(ApplicationRepository.class);
        processedEvents = Mockito.mock(ProcessedEventGuard.class);
        when(processedEvents.claim(anyString(), anyString())).thenReturn(true);
        consumer = new ProductCreatedConsumer(applicationRepository, processedEvents);
    }

    /** Over the wire and back, so the payload is exercised as a consumer sees it. */
    private DomainEvent overTheWire(DomainEvent published) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(published), DomainEvent.class);
    }

    private Application provisioning(ApplicationType type) {
        return Application.builder()
                .id(APPLICATION_ID)
                .userId(7L)
                .applicationType(type)
                .status(ApplicationStatus.PROVISIONING)
                .build();
    }

    private void stored(Application application) {
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(Application.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Application saved() {
        ArgumentCaptor<Application> captor = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a created loan records its real id and completes provisioning")
    void loanConfirmsProvisioning() throws Exception {
        stored(provisioning(ApplicationType.PERSONAL_LOAN));

        consumer.onLoanEvent(overTheWire(LoanCreated.of(555L, 7L, APPLICATION_ID, "PERSONAL_LOAN",
                new BigDecimal("8000"), new BigDecimal("10.99"), 36)));

        Application application = saved();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PROVISIONED);
        assertThat(application.getProductId()).isEqualTo(555L);
    }

    @Test
    @DisplayName("a created card records its real id and completes provisioning")
    void cardConfirmsProvisioning() throws Exception {
        stored(provisioning(ApplicationType.CREDIT_CARD));

        consumer.onCardEvent(overTheWire(
                CreditCardCreated.of(777L, 7L, "GOLD", "4242", APPLICATION_ID)));

        Application application = saved();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PROVISIONED);
        assertThat(application.getProductId()).isEqualTo(777L);
    }

    @Test
    @DisplayName("a redelivered confirmation provisions once")
    void redeliveryIsIgnored() throws Exception {
        stored(provisioning(ApplicationType.PERSONAL_LOAN));
        when(processedEvents.claim(anyString(), anyString())).thenReturn(false);

        consumer.onLoanEvent(overTheWire(LoanCreated.of(555L, 7L, APPLICATION_ID, "PERSONAL_LOAN",
                new BigDecimal("8000"), new BigDecimal("10.99"), 36)));

        verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a confirmation for an application that was never provisioning is not recorded")
    void wrongStateIsNotRecorded() throws Exception {
        // Cancelled while the product was being created, or a product created
        // by some other route. Recording the id would be the wrong kind of tidy.
        Application cancelled = provisioning(ApplicationType.PERSONAL_LOAN);
        cancelled.setStatus(ApplicationStatus.CANCELLED);
        stored(cancelled);

        consumer.onLoanEvent(overTheWire(LoanCreated.of(555L, 7L, APPLICATION_ID, "PERSONAL_LOAN",
                new BigDecimal("8000"), new BigDecimal("10.99"), 36)));

        verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("an already provisioned application is left alone")
    void alreadyProvisionedIsLeftAlone() throws Exception {
        Application done = provisioning(ApplicationType.PERSONAL_LOAN);
        done.setStatus(ApplicationStatus.PROVISIONED);
        done.setProductId(555L);
        stored(done);

        consumer.onLoanEvent(overTheWire(LoanCreated.of(555L, 7L, APPLICATION_ID, "PERSONAL_LOAN",
                new BigDecimal("8000"), new BigDecimal("10.99"), 36)));

        verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a product with no application behind it is ignored rather than dead-lettered")
    void noApplicationIdIsIgnored() throws Exception {
        // A card issued outside the application lifecycle is a legitimate thing
        // for this service not to care about. Throwing would turn indifference
        // into an incident on the dead-letter topic.
        consumer.onCardEvent(overTheWire(CreditCardCreated.of(777L, 7L, "GOLD", "4242")));

        verify(applicationRepository, never()).findById(any());
        verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a confirmation naming an application that does not exist is ignored")
    void unknownApplicationIsIgnored() throws Exception {
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.empty());

        consumer.onLoanEvent(overTheWire(LoanCreated.of(555L, 7L, APPLICATION_ID, "PERSONAL_LOAN",
                new BigDecimal("8000"), new BigDecimal("10.99"), 36)));

        verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unrelated event on the same topic is not treated as a confirmation")
    void unrelatedEventIgnored() throws Exception {
        consumer.onLoanEvent(overTheWire(
                com.bankingplatform.common.events.LoanPaidOff.of(555L, 7L)));

        verify(applicationRepository, never()).save(any());
    }
}

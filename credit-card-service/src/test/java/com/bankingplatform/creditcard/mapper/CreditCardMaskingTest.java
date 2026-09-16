package com.bankingplatform.creditcard.mapper;

import com.bankingplatform.creditcard.dto.response.CreditCardResponse;
import com.bankingplatform.creditcard.model.CardStatus;
import com.bankingplatform.creditcard.model.CardType;
import com.bankingplatform.creditcard.model.CreditCard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the service boundary against leaking a full card number.
 *
 * <p>The database stores the whole PAN because the service needs it for
 * uniqueness checks. What must never happen is that number reaching a response
 * body, so these tests assert on the serialised JSON as well as on the mapped
 * object.
 */
@DisplayName("Credit card number masking")
class CreditCardMaskingTest {

    private static final String FULL_PAN = "4111111111111234";

    private final CreditCardMapper mapper = new CreditCardMapperImpl();
    /** `findAndRegisterModules` mirrors the JSR-310 support Spring Boot configures at runtime. */
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private static CreditCard card(String cardNumber) {
        return CreditCard.builder()
                .id(1L)
                .cardNumber(cardNumber)
                .userId(7L)
                .cardType(CardType.PLATINUM)
                .creditLimit(new BigDecimal("5000.00"))
                .availableCredit(new BigDecimal("4200.00"))
                .currentBalance(new BigDecimal("800.00"))
                .statementBalance(BigDecimal.ZERO)
                .minimumPaymentDue(new BigDecimal("25.00"))
                .paymentDueDate(LocalDate.of(2026, 2, 1))
                .apr(new BigDecimal("19.99"))
                .billingCycleDay(1)
                .status(CardStatus.ACTIVE)
                .currency("USD")
                .rewardsPoints(120)
                .build();
    }

    // ------------------------------------------------------------ the boundary

    @Test
    @DisplayName("the mapped response exposes only a mask and the last four digits")
    void responseCarriesOnlyMaskedForm() {
        CreditCardResponse response = mapper.toResponse(card(FULL_PAN));

        assertThat(response.getMaskedCardNumber()).isEqualTo("•••• •••• •••• 1234");
        assertThat(response.getLast4()).isEqualTo("1234");
    }

    @Test
    @DisplayName("the response type has no property that could carry a full card number")
    void responseTypeHasNoCardNumberProperty() throws Exception {
        String[] names = Arrays.stream(
                        java.beans.Introspector.getBeanInfo(CreditCardResponse.class)
                                .getPropertyDescriptors())
                .map(PropertyDescriptor::getName)
                .toArray(String[]::new);

        assertThat(names).doesNotContain("cardNumber");
        assertThat(names).contains("maskedCardNumber", "last4");
    }

    @Test
    @DisplayName("the serialised JSON does not contain the full card number anywhere")
    void serialisedJsonNeverContainsTheFullPan() throws Exception {
        String body = json.writeValueAsString(mapper.toResponse(card(FULL_PAN)));

        assertThat(body).doesNotContain(FULL_PAN);
        // Not even the leading digits — a partial prefix is still card data.
        assertThat(body).doesNotContain("411111");
        assertThat(body).contains("1234");
    }

    // ----------------------------------------------------------- masking rules

    @Test
    @DisplayName("masking keeps exactly the final four digits")
    void maskKeepsFinalFourDigits() {
        assertThat(CardNumberMasker.mask("4111111111111234")).isEqualTo("•••• •••• •••• 1234");
        assertThat(CardNumberMasker.last4("4111111111111234")).isEqualTo("1234");
    }

    @Test
    @DisplayName("separators in the stored value do not change the result")
    void ignoresNonDigits() {
        assertThat(CardNumberMasker.mask("4111-1111-1111-1234")).isEqualTo("•••• •••• •••• 1234");
    }

    @ParameterizedTest(name = "an unusable value \"{0}\" is fully masked rather than echoed")
    @NullAndEmptySource
    @ValueSource(strings = {"12", "abc", "  "})
    void unusableValuesAreFullyMasked(String value) {
        assertThat(CardNumberMasker.mask(value)).isEqualTo("•••• •••• •••• ••••");
        assertThat(CardNumberMasker.last4(value)).isNull();
    }

    @Test
    @DisplayName("a card with no stored number still maps without leaking or failing")
    void handlesMissingCardNumber() {
        CreditCardResponse response = mapper.toResponse(card(null));

        assertThat(response.getMaskedCardNumber()).isEqualTo("•••• •••• •••• ••••");
        assertThat(response.getLast4()).isNull();
    }
}

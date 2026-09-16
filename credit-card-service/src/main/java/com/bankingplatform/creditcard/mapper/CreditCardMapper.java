package com.bankingplatform.creditcard.mapper;

import com.bankingplatform.creditcard.dto.response.CreditCardResponse;
import com.bankingplatform.creditcard.model.CreditCard;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Entity to response mapping.
 *
 * <p>The card number is masked here, at the edge of the service, so there is no
 * path by which the full PAN reaches a response body.
 */
@Mapper(componentModel = "spring", imports = CardNumberMasker.class)
public interface CreditCardMapper {

    @Mapping(
            target = "maskedCardNumber",
            expression = "java(CardNumberMasker.mask(card.getCardNumber()))")
    @Mapping(
            target = "last4",
            expression = "java(CardNumberMasker.last4(card.getCardNumber()))")
    CreditCardResponse toResponse(CreditCard card);
}

package com.bankingplatform.creditcard.mapper;

import com.bankingplatform.creditcard.dto.response.CreditCardResponse;
import com.bankingplatform.creditcard.model.CreditCard;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CreditCardMapper {
    CreditCardResponse toResponse(CreditCard card);
}

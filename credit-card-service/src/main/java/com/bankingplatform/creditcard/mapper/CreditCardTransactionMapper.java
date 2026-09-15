package com.bankingplatform.creditcard.mapper;

import com.bankingplatform.creditcard.dto.response.CreditCardTransactionResponse;
import com.bankingplatform.creditcard.model.CreditCardTransaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CreditCardTransactionMapper {

    @Mapping(target = "creditCardId", source = "creditCard.id")
    CreditCardTransactionResponse toResponse(CreditCardTransaction tx);
}

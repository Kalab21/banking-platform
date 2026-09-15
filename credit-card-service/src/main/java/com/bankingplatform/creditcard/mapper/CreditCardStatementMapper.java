package com.bankingplatform.creditcard.mapper;

import com.bankingplatform.creditcard.dto.response.CreditCardStatementResponse;
import com.bankingplatform.creditcard.model.CreditCardStatement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CreditCardStatementMapper {

    @Mapping(target = "creditCardId", source = "creditCard.id")
    CreditCardStatementResponse toResponse(CreditCardStatement statement);
}

package com.bankingplatform.transaction.mapper;

import com.bankingplatform.transaction.dto.TransactionResponse;
import com.bankingplatform.transaction.model.Transaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface TransactionMapper {

    @Mapping(target = "type", expression = "java(tx.getType().name())")
    @Mapping(target = "status", expression = "java(tx.getStatus().name())")
    TransactionResponse toResponse(Transaction tx);
}

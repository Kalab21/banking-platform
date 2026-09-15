package com.bankingplatform.account.mapper;

import com.bankingplatform.account.dto.AccountResponse;
import com.bankingplatform.account.model.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.math.BigDecimal;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = BigDecimal.class)
public interface AccountMapper {

    @Mapping(target = "accountType", expression = "java(account.getAccountType().name())")
    @Mapping(target = "status", expression = "java(account.getStatus().name())")
    @Mapping(target = "availableBalance",
        expression = "java(account.getBalance().add(account.getOverdraftLimit()).subtract(account.getOverdraftBalance()))")
    AccountResponse toResponse(Account account);
}

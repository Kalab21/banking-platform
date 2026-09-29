package com.bankingplatform.payment.mapper;

import com.bankingplatform.payment.dto.BeneficiaryResponse;
import com.bankingplatform.payment.model.Beneficiary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface BeneficiaryMapper {

    /*
     * Account, routing and IBAN numbers are returned as their last four
     * characters. They were returned in full, so every payee list carried the
     * complete external account details of everyone the customer pays.
     */
    @Mapping(target = "beneficiaryType", expression = "java(b.getBeneficiaryType().name())")
    @Mapping(target = "accountNumber", expression = "java(BeneficiaryMapper.mask(b.getAccountNumber()))")
    @Mapping(target = "routingNumber", expression = "java(BeneficiaryMapper.mask(b.getRoutingNumber()))")
    @Mapping(target = "iban", expression = "java(BeneficiaryMapper.mask(b.getIban()))")
    BeneficiaryResponse toResponse(Beneficiary b);

    static String mask(String value) {
        if (value == null || value.isBlank()) return value;
        return value.length() <= 4 ? value : "\u2022\u2022\u2022\u2022" + value.substring(value.length() - 4);
    }
}

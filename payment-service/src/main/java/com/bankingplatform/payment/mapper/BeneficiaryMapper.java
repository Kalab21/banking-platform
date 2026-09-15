package com.bankingplatform.payment.mapper;

import com.bankingplatform.payment.dto.BeneficiaryResponse;
import com.bankingplatform.payment.model.Beneficiary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface BeneficiaryMapper {

    @Mapping(target = "beneficiaryType", expression = "java(b.getBeneficiaryType().name())")
    BeneficiaryResponse toResponse(Beneficiary b);
}

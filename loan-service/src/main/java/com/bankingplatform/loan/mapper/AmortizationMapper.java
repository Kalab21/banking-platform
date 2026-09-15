package com.bankingplatform.loan.mapper;

import com.bankingplatform.loan.dto.response.AmortizationScheduleResponse;
import com.bankingplatform.loan.model.AmortizationSchedule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AmortizationMapper {

    @Mapping(target = "loanId", source = "loan.id")
    AmortizationScheduleResponse toResponse(AmortizationSchedule schedule);
}

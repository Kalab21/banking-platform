package com.bankingplatform.loan.mapper;

import com.bankingplatform.loan.dto.response.LoanRepaymentResponse;
import com.bankingplatform.loan.model.LoanRepayment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface LoanRepaymentMapper {

    @Mapping(target = "loanId", source = "loan.id")
    LoanRepaymentResponse toResponse(LoanRepayment repayment);
}

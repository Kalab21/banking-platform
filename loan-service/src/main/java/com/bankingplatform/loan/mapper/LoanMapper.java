package com.bankingplatform.loan.mapper;

import com.bankingplatform.loan.dto.response.LoanResponse;
import com.bankingplatform.loan.model.Loan;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface LoanMapper {
    LoanResponse toResponse(Loan loan);
}

package com.bankingplatform.payment.mapper;

import com.bankingplatform.payment.dto.PaymentResponse;
import com.bankingplatform.payment.model.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface PaymentMapper {

    @Mapping(target = "paymentType", expression = "java(p.getPaymentType().name())")
    @Mapping(target = "status", expression = "java(p.getStatus().name())")
    @Mapping(target = "recurrencePattern",
             expression = "java(p.getRecurrencePattern() != null ? p.getRecurrencePattern().name() : null)")
    PaymentResponse toResponse(Payment p);
}

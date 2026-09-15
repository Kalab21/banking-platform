package com.bankingplatform.application.mapper;

import com.bankingplatform.application.dto.ApplicationResponse;
import com.bankingplatform.application.model.Application;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ApplicationMapper {

    @Mapping(target = "applicationType", expression = "java(app.getApplicationType().name())")
    @Mapping(target = "status", expression = "java(app.getStatus().name())")
    ApplicationResponse toResponse(Application app);
}

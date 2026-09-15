package com.bankingplatform.user.mapper;

import com.bankingplatform.user.dto.RegisterRequest;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.user.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {

    @Mapping(target = "role", expression = "java(user.getRole().name())")
    @Mapping(target = "kycStatus", expression = "java(user.getKycStatus().name())")
    UserResponse toResponse(User user);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "enabled", ignore = true)
    @Mapping(target = "creditScore", ignore = true)
    @Mapping(target = "creditScoreUpdatedAt", ignore = true)
    @Mapping(target = "kycStatus", ignore = true)
    @Mapping(target = "kycCompletedAt", ignore = true)
    @Mapping(target = "twoFactorEnabled", ignore = true)
    @Mapping(target = "twoFactorSecret", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    User toEntity(RegisterRequest request);
}

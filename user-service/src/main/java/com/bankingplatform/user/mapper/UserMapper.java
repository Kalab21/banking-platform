package com.bankingplatform.user.mapper;

import com.bankingplatform.user.dto.RegisterRequest;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.user.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {

    /**
     * The identity fields are not on {@link User} and cannot be filled in here.
     * They are set by the service from the separate identity record, which is
     * the point of keeping them in a different table: there is nothing on the
     * user row for a mapping to leak.
     */
    @Mapping(target = "role", expression = "java(user.getRole().name())")
    @Mapping(target = "kycStatus", expression = "java(user.getKycStatus().name())")
    @Mapping(target = "ssnLast4", ignore = true)
    @Mapping(target = "identityStatus", ignore = true)
    UserResponse toResponse(User user);

    /**
     * {@code ssn} is not a property of {@link User} and is deliberately absent
     * from the entity, so there is nothing to map it onto. The service reduces
     * it to four digits and writes those to the identity record instead.
     *
     * <p>Normalised values — phone, state, postal code — are applied by the
     * service after mapping, so that what reaches the database is canonical
     * rather than however the customer typed it.
     */
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

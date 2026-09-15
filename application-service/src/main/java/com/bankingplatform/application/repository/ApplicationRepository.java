package com.bankingplatform.application.repository;

import com.bankingplatform.application.model.Application;
import com.bankingplatform.application.model.ApplicationStatus;
import com.bankingplatform.application.model.ApplicationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    List<Application> findByUserId(Long userId);
    List<Application> findByUserIdAndStatus(Long userId, ApplicationStatus status);
    List<Application> findByStatus(ApplicationStatus status);
    List<Application> findByUserIdAndApplicationType(Long userId, ApplicationType type);
}

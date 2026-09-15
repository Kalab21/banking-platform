package com.bankingplatform.application.service;

import com.bankingplatform.application.dto.ApplicationResponse;
import com.bankingplatform.application.dto.CreateApplicationRequest;
import com.bankingplatform.application.dto.ReviewRequest;
import com.bankingplatform.application.model.ApplicationStatus;

import java.util.List;

public interface ApplicationService {

    ApplicationResponse submitApplication(CreateApplicationRequest request);

    ApplicationResponse getById(Long id);

    List<ApplicationResponse> getByUserId(Long userId);

    List<ApplicationResponse> getByStatus(ApplicationStatus status);

    ApplicationResponse review(Long id, ReviewRequest request);

    ApplicationResponse cancel(Long id, Long userId);
}

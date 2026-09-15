package com.bankingplatform.user.service;

import com.bankingplatform.user.dto.KycDocumentRequest;
import com.bankingplatform.user.dto.KycDocumentResponse;
import com.bankingplatform.user.dto.ReviewDocumentRequest;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.user.model.KycStatus;

import java.util.List;

public interface KycService {

    KycDocumentResponse submitDocument(Long userId, KycDocumentRequest request);

    KycDocumentResponse reviewDocument(Long documentId, ReviewDocumentRequest request);

    UserResponse updateKycStatus(Long userId, KycStatus status);

    List<KycDocumentResponse> getUserDocuments(Long userId);
}

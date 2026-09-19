package com.bankingplatform.user.service.impl;

import com.bankingplatform.user.dto.KycDocumentRequest;
import com.bankingplatform.user.dto.KycDocumentResponse;
import com.bankingplatform.user.dto.ReviewDocumentRequest;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.user.exception.ResourceNotFoundException;
import com.bankingplatform.user.mapper.UserMapper;
import com.bankingplatform.user.model.DocumentStatus;
import com.bankingplatform.user.model.KycDocument;
import com.bankingplatform.user.model.KycStatus;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.KycDocumentRepository;
import com.bankingplatform.user.kafka.producer.UserEventProducer;
import com.bankingplatform.user.repository.UserRepository;
import com.bankingplatform.user.service.KycService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycServiceImpl implements KycService {

    private final KycDocumentRepository kycDocumentRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final UserEventProducer eventProducer;

    @Override
    @Transactional
    public KycDocumentResponse submitDocument(Long userId, KycDocumentRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        KycDocument doc = new KycDocument();
        doc.setUserId(userId);
        doc.setDocumentType(request.getDocumentType());
        doc.setDocumentRef(request.getDocumentRef());
        doc.setStatus(DocumentStatus.SUBMITTED);

        KycDocument saved = kycDocumentRepository.save(doc);

        if (user.getKycStatus() == KycStatus.PENDING) {
            user.setKycStatus(KycStatus.IN_REVIEW);
            userRepository.save(user);
        }

        log.info("KYC document submitted: userId={}, type={}", userId, request.getDocumentType());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public KycDocumentResponse reviewDocument(Long documentId, ReviewDocumentRequest request) {
        KycDocument doc = kycDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("KYC document not found: " + documentId));

        doc.setStatus(request.getStatus());
        doc.setReviewedBy(request.getReviewedBy());
        doc.setReviewedAt(LocalDateTime.now());

        if (request.getStatus() == DocumentStatus.REJECTED) {
            doc.setRejectionReason(request.getRejectionReason());
        }

        KycDocument saved = kycDocumentRepository.save(doc);
        log.info("KYC document reviewed: documentId={}, status={}", documentId, request.getStatus());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public UserResponse updateKycStatus(Long userId, KycStatus status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        user.setKycStatus(status);
        if (status == KycStatus.APPROVED || status == KycStatus.REJECTED) {
            user.setKycCompletedAt(LocalDateTime.now());
        }

        User saved = userRepository.save(user);
        log.info("KYC status updated: userId={}, status={}", userId, status);

        // notification-service has always listened for these two and nothing
        // had ever published them, so the customer was never told the outcome
        // of their own KYC review.
        if (status == KycStatus.APPROVED) {
            eventProducer.publishKycApproved(userId);
        } else if (status == KycStatus.REJECTED) {
            eventProducer.publishKycRejected(userId);
        }
        return userMapper.toResponse(saved);
    }

    @Override
    public List<KycDocumentResponse> getUserDocuments(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        return kycDocumentRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private KycDocumentResponse toResponse(KycDocument doc) {
        KycDocumentResponse response = new KycDocumentResponse();
        response.setId(doc.getId());
        response.setUserId(doc.getUserId());
        response.setDocumentType(doc.getDocumentType().name());
        response.setDocumentRef(doc.getDocumentRef());
        response.setStatus(doc.getStatus().name());
        response.setRejectionReason(doc.getRejectionReason());
        response.setReviewedBy(doc.getReviewedBy());
        response.setReviewedAt(doc.getReviewedAt());
        response.setCreatedAt(doc.getCreatedAt());
        return response;
    }
}

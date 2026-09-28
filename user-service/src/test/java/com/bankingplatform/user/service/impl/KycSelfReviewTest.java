package com.bankingplatform.user.service.impl;

import com.bankingplatform.common.security.AccessDeniedException;
import com.bankingplatform.user.dto.ReviewDocumentRequest;
import com.bankingplatform.user.model.DocumentStatus;
import com.bankingplatform.user.model.KycDocument;
import com.bankingplatform.user.repository.KycDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("KYC document review")
class KycSelfReviewTest {

    @Test
    @DisplayName("a member of staff cannot review their own identity document")
    void selfReviewRefused() {
        KycDocumentRepository documents = Mockito.mock(KycDocumentRepository.class);
        KycDocument own = new KycDocument();
        own.setId(5L);
        own.setUserId(99L);
        when(documents.findById(5L)).thenReturn(Optional.of(own));
        KycServiceImpl service = new KycServiceImpl(documents,
                Mockito.mock(com.bankingplatform.user.repository.UserRepository.class),
                Mockito.mock(com.bankingplatform.user.mapper.UserMapper.class),
                Mockito.mock(com.bankingplatform.user.kafka.producer.UserEventProducer.class));

        ReviewDocumentRequest request = new ReviewDocumentRequest();
        request.setStatus(DocumentStatus.APPROVED);

        assertThatThrownBy(() -> service.reviewDocument(5L, request, 99L))
                .isInstanceOf(AccessDeniedException.class);
        verify(documents, never()).save(any());
    }
}

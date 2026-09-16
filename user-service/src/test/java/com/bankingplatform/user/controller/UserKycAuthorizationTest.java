package com.bankingplatform.user.controller;

import com.bankingplatform.common.security.CallerIdentityArgumentResolver;
import com.bankingplatform.common.security.CallerIdentityExceptionHandler;
import com.bankingplatform.common.security.CallerIdentityHeaders;
import com.bankingplatform.user.dto.KycDocumentResponse;
import com.bankingplatform.user.dto.UserResponse;
import com.bankingplatform.user.service.KycService;
import com.bankingplatform.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership authorization on profiles, KYC and credit scores.
 *
 * <p>user-service had Spring Security and role checks on the staff operations,
 * but being authenticated is not the same as being the subject: every
 * per-user route took a {@code userId} from the path and used it unchecked, so
 * any customer could read or edit another customer's profile and list their KYC
 * documents.
 */
@DisplayName("User and KYC authorization")
class UserKycAuthorizationTest {

    private static final long CUSTOMER_A = 10L;
    private static final long CUSTOMER_B = 20L;
    private static final long STAFF = 99L;

    private UserService userService;
    private KycService kycService;
    private MockMvc users;
    private MockMvc kyc;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        kycService = Mockito.mock(KycService.class);

        users = build(new UserController(userService));
        kyc = build(new KycController(kycService));

        UserResponse a = new UserResponse();
        a.setId(CUSTOMER_A);
        a.setUsername("userA");
        UserResponse b = new UserResponse();
        b.setId(CUSTOMER_B);
        b.setUsername("userB");

        when(userService.getUserById(anyLong())).thenReturn(a);
        when(userService.getUserByUsername("userA")).thenReturn(a);
        when(userService.getUserByUsername("userB")).thenReturn(b);
        when(userService.updateUser(anyLong(), any())).thenReturn(a);
        when(kycService.getUserDocuments(anyLong())).thenReturn(List.of());
        when(kycService.submitDocument(anyLong(), any())).thenReturn(new KycDocumentResponse());
    }

    private static MockMvc build(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CallerIdentityArgumentResolver())
                .setControllerAdvice(new CallerIdentityExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, long userId, String role) {
        return builder
                .header(CallerIdentityHeaders.USER_ID, String.valueOf(userId))
                .header(CallerIdentityHeaders.USERNAME, "user" + userId)
                .header(CallerIdentityHeaders.USER_ROLE, role);
    }

    @Nested
    @DisplayName("profiles")
    class Profiles {

        @Test
        @DisplayName("a customer may read their own profile")
        void ownProfileAllowed() throws Exception {
            users.perform(as(get("/api/users/{id}", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot read another customer's profile")
        void foreignProfileDenied() throws Exception {
            users.perform(as(get("/api/users/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(userService, never()).getUserById(CUSTOMER_B);
        }

        @Test
        @DisplayName("a customer cannot update another customer's profile")
        void foreignUpdateDenied() throws Exception {
            users.perform(as(put("/api/users/{id}", CUSTOMER_B), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"firstName\":\"Mallory\"}"))
                    .andExpect(status().isForbidden());

            verify(userService, never()).updateUser(anyLong(), any());
        }

        @Test
        @DisplayName("a username lookup is not a way around the ownership rule")
        void foreignUsernameLookupDenied() throws Exception {
            // A username is guessable, so this route would otherwise be an
            // easier path to the same data than the id route.
            users.perform(as(get("/api/users/username/{u}", "userB"), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("staff may read any profile")
        void staffReadsAnyProfile() throws Exception {
            users.perform(as(get("/api/users/{id}", CUSTOMER_B), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("KYC")
    class Kyc {

        @Test
        @DisplayName("a customer may list their own documents")
        void ownDocumentsAllowed() throws Exception {
            kyc.perform(as(get("/api/users/{userId}/kyc/documents", CUSTOMER_A), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a customer cannot list another customer's documents")
        void foreignDocumentsDenied() throws Exception {
            // These are identity documents, so the leak would be worse than a
            // balance disclosure.
            kyc.perform(as(get("/api/users/{userId}/kyc/documents", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());

            verify(kycService, never()).getUserDocuments(CUSTOMER_B);
        }

        @Test
        @DisplayName("a customer cannot submit a document against another customer")
        void foreignSubmitDenied() throws Exception {
            kyc.perform(as(post("/api/users/{userId}/kyc/documents", CUSTOMER_B), CUSTOMER_A, "CUSTOMER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"documentType\":\"PASSPORT\",\"documentRef\":\"X\"}"))
                    .andExpect(status().isForbidden());

            verify(kycService, never()).submitDocument(anyLong(), any());
        }

        @Test
        @DisplayName("a customer cannot read another customer's KYC status")
        void foreignStatusDenied() throws Exception {
            kyc.perform(as(get("/api/users/{userId}/kyc", CUSTOMER_B), CUSTOMER_A, "CUSTOMER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("staff may list any customer's documents, which the review queue needs")
        void staffReadsAnyDocuments() throws Exception {
            kyc.perform(as(get("/api/users/{userId}/kyc/documents", CUSTOMER_B), STAFF, "EMPLOYEE"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("fail closed")
    class FailClosed {

        @Test
        @DisplayName("a profile read with no gateway identity is rejected")
        void noIdentityRejected() throws Exception {
            users.perform(get("/api/users/{id}", CUSTOMER_A))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a KYC read with no gateway identity is rejected")
        void noIdentityKycRejected() throws Exception {
            kyc.perform(get("/api/users/{userId}/kyc/documents", CUSTOMER_A))
                    .andExpect(status().isUnauthorized());
        }
    }
}

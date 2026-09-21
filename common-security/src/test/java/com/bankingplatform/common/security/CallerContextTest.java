package com.bankingplatform.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who the audit log says did something.
 *
 * <p>The field being filled is easy. What these cover is the two ways it can
 * be filled in wrongly: attributing the platform's own work to a person, and
 * attributing one person's work to whoever used the thread before them.
 */
@DisplayName("Caller context")
class CallerContextTest {

    private final CallerContextFilter filter = new CallerContextFilter();

    private MockHttpServletRequest requestFrom(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader(CallerIdentityHeaders.USER_ID, userId);
        }
        if (role != null) {
            request.addHeader(CallerIdentityHeaders.USER_ROLE, role);
        }
        request.addHeader(CallerIdentityHeaders.USERNAME, "someone");
        return request;
    }

    @Nested
    @DisplayName("with no caller")
    class WithoutACaller {

        @Test
        @DisplayName("the platform is named as the actor rather than left blank")
        void systemIsAStatement() {
            // A scheduled job and a Kafka listener have no caller, and that is
            // correct rather than missing. Recording it as SYSTEM is what
            // makes a null performedBy beside it mean "no user was involved"
            // instead of "we lost the attribution".
            assertThat(CallerContext.current()).isEmpty();
            assertThat(CallerContext.userId()).isEmpty();
            assertThat(CallerContext.actor()).isEqualTo("SYSTEM");
        }
    }

    @Nested
    @DisplayName("on a request")
    class OnARequest {

        @Test
        @DisplayName("the caller is published for the duration of the request")
        void callerIsVisibleDuringTheRequest() throws Exception {
            AtomicReference<String> actorDuring = new AtomicReference<>();
            AtomicReference<Long> userDuring = new AtomicReference<>();

            FilterChain chain = (req, res) -> {
                actorDuring.set(CallerContext.actor());
                userDuring.set(CallerContext.userId().orElse(null));
            };

            filter.doFilter(requestFrom("42", "CUSTOMER"), new MockHttpServletResponse(), chain);

            assertThat(userDuring.get()).isEqualTo(42L);
            assertThat(actorDuring.get()).isEqualTo("CUSTOMER");
        }

        @Test
        @DisplayName("staff acting on a customer are recorded as staff")
        void staffAreNotRecordedAsTheCustomer() {
            // The bug this replaces: several call sites passed the subject of
            // the change as performedBy, so an employee approving someone
            // else's application produced a row saying the customer had
            // approved it themselves.
            CallerContext.runAs(new CallerIdentity(7L, "reviewer", Role.EMPLOYEE), () -> {
                assertThat(CallerContext.userId()).contains(7L);
                assertThat(CallerContext.actor()).isEqualTo("EMPLOYEE");
            });
        }

        @Test
        @DisplayName("the context is cleared even when the request fails")
        void clearedOnFailure() {
            FilterChain explodes = (req, res) -> {
                throw new IllegalStateException("handler failed");
            };

            assertThatThrownBy(() -> filter.doFilter(
                    requestFrom("42", "CUSTOMER"), new MockHttpServletResponse(), explodes))
                    .isInstanceOf(IllegalStateException.class);

            // Servlet threads are pooled. An identity left behind is not a
            // leak into the void — it is the next request on that thread
            // being attributed to the previous caller.
            assertThat(CallerContext.current()).isEmpty();
        }

        @Test
        @DisplayName("a request with no identity headers leaves no attribution, and is not refused")
        void internalRequestsAreNotRejected() throws Exception {
            // The internal endpoints carry no identity by design. Refusing
            // them here would break service-to-service calls for the sake of
            // a log field.
            AtomicReference<String> actorDuring = new AtomicReference<>();
            filter.doFilter(requestFrom(null, null), new MockHttpServletResponse(),
                    (req, res) -> actorDuring.set(CallerContext.actor()));

            assertThat(actorDuring.get()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("an unusable user id is no attribution rather than a rejected request")
        void malformedHeadersDoNotFailTheRequest() throws Exception {
            AtomicReference<String> actorDuring = new AtomicReference<>();
            filter.doFilter(requestFrom("not-a-number", "CUSTOMER"), new MockHttpServletResponse(),
                    (req, res) -> actorDuring.set(CallerContext.actor()));

            assertThat(actorDuring.get()).isEqualTo("SYSTEM");
        }
    }

    @Nested
    @DisplayName("across threads")
    class AcrossThreads {

        @Test
        @DisplayName("the caller does not follow work handed to another thread")
        void identityDoesNotLeakIntoOtherThreads() throws Exception {
            // Work handed to another thread is no longer the request. An
            // identity that followed it would attribute a scheduled job or an
            // async task to whoever happened to be online at the time.
            AtomicReference<String> actorElsewhere = new AtomicReference<>();
            ExecutorService pool = Executors.newSingleThreadExecutor();

            CallerContext.runAs(new CallerIdentity(42L, "customer", Role.CUSTOMER), () -> {
                try {
                    pool.submit(() -> actorElsewhere.set(CallerContext.actor())).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertThat(CallerContext.actor())
                        .as("and the original thread still has its caller")
                        .isEqualTo("CUSTOMER");
            });
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(actorElsewhere.get()).isEqualTo("SYSTEM");
        }
    }
}

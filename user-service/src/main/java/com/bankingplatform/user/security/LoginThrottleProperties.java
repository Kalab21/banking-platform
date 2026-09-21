package com.bankingplatform.user.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Policy for per-account sign-in throttling.
 *
 * <p>Deliberately configuration rather than constants: the numbers are a policy
 * decision, and an operator changing them should not need a rebuild.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "security.login-throttle")
public class LoginThrottleProperties {

    /** Failed attempts allowed against one username inside {@link #window}. */
    private int maxAttempts = 5;

    /** How long failures are remembered, and therefore how long a block lasts. */
    private Duration window = Duration.ofMinutes(15);
}

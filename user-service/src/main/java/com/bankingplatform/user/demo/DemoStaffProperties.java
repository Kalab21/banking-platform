package com.bankingplatform.user.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The demo staff account's details, so they are configuration rather than
 * constants in a class body — a local stack can change them without a rebuild,
 * and nothing is compiled into the jar.
 */
@ConfigurationProperties(prefix = "northbank.demo.staff")
public class DemoStaffProperties {

    /** Off unless a local stack turns it on. */
    private boolean enabled = false;

    private String username = "northbank.reviewer";
    private String email = "reviewer@northbank.example";

    /**
     * Supplied by the local Compose file. There is deliberately no usable
     * default: a stack that enables the account without saying what its
     * password is should fail loudly rather than quietly ship a known one.
     */
    private String password;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "northbank.demo.staff.enabled is true but no password was configured");
        }
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

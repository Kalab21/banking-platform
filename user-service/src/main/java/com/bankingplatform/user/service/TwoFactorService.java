package com.bankingplatform.user.service;

import com.bankingplatform.user.dto.TwoFactorSetupResponse;

public interface TwoFactorService {

    TwoFactorSetupResponse generateSecret(Long userId);

    void verifyAndEnable(Long userId, String code);

    void disable(Long userId, String code);

    boolean verifyCode(Long userId, String code);
}

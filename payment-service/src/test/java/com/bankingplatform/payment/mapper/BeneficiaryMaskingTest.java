package com.bankingplatform.payment.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Beneficiary masking")
class BeneficiaryMaskingTest {

    @Test
    @DisplayName("external account numbers leave the service as their last four characters")
    void numbersAreMasked() {
        assertThat(BeneficiaryMapper.mask("9876543210")).isEqualTo("\u2022\u2022\u2022\u20223210");
        assertThat(BeneficiaryMapper.mask("GB29EXMP60161331926819")).isEqualTo("\u2022\u2022\u2022\u20226819");
        assertThat(BeneficiaryMapper.mask("990000001")).doesNotContain("99000");
        assertThat(BeneficiaryMapper.mask(null)).isNull();
    }
}

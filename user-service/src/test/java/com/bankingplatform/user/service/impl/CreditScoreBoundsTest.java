package com.bankingplatform.user.service.impl;

import com.bankingplatform.user.dto.UpdateCreditScoreRequest;
import com.bankingplatform.user.model.User;
import com.bankingplatform.user.repository.CreditScoreHistoryRepository;
import com.bankingplatform.user.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("Credit score bounds")
class CreditScoreBoundsTest {

    private User userWithScore(int score) {
        User user = new User();
        user.setId(7L);
        user.setCreditScore(score);
        return user;
    }

    private CreditScoreServiceImpl serviceFor(User user) {
        UserRepository users = Mockito.mock(UserRepository.class);
        CreditScoreHistoryRepository history = Mockito.mock(CreditScoreHistoryRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(history.save(any())).thenAnswer(i -> i.getArgument(0));
        return new CreditScoreServiceImpl(users, history);
    }

    @Test
    @DisplayName("a huge increase reaches the ceiling rather than wrapping to the floor")
    void hugeIncreaseDoesNotWrap() {
        // 700 + Integer.MAX_VALUE wrapped negative and was clamped to 300.
        User user = userWithScore(700);
        serviceFor(user).updateScore(7L, Integer.MAX_VALUE, "overflow check");
        assertThat(user.getCreditScore()).isEqualTo(850);
    }

    @Test
    @DisplayName("a huge decrease reaches the floor")
    void hugeDecreaseReachesTheFloor() {
        User user = userWithScore(700);
        serviceFor(user).updateScore(7L, Integer.MIN_VALUE, "overflow check");
        assertThat(user.getCreditScore()).isEqualTo(300);
    }

    @ParameterizedTest(name = "a delta of {0} is refused")
    @ValueSource(ints = {551, -551, Integer.MAX_VALUE})
    void deltaOutsideTheRangeIsRefused(int delta) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        UpdateCreditScoreRequest request = new UpdateCreditScoreRequest();
        request.setDelta(delta);
        request.setReason("Manual review");
        assertThat(validator.validate(request)).isNotEmpty();
    }
}

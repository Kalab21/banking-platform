package com.bankingplatform.user.service;

import com.bankingplatform.user.dto.*;

import java.util.List;

public interface UserService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    UserResponse getUserById(Long id);

    UserResponse getUserByUsername(String username);

    UserResponse updateUser(Long id, UpdateUserRequest request);

    List<UserResponse> getAllUsers();
}

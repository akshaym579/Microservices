package com.oneenterprise.userservice.dto;

import com.oneenterprise.userservice.model.User;

public record UserResponse(Long id, String name, String email) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}

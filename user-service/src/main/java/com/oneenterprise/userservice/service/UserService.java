package com.oneenterprise.userservice.service;

import com.oneenterprise.userservice.exception.UserNotFoundException;
import com.oneenterprise.userservice.model.User;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;


@Service
public class UserService {

    private final Map<Long, User> users = new LinkedHashMap<>();

    public UserService() {
        seed(new User(1L, "Akshay", "akshay@example.com", "VIP account"));
        seed(new User(2L, "Pranav", "pranav@example.com", "internal test user"));
        seed(new User(3L, "Mazil", "mazil@example.com", "prefers email contact"));
    }

    private void seed(User user) {
        users.put(user.getId(), user);
    }


    public User getUserById(Long id) {
        User user = users.get(id);
        if (user == null) {
            throw new UserNotFoundException(id);
        }
        return user;
    }
}

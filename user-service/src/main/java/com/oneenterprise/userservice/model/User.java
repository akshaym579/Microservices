package com.oneenterprise.userservice.model;

public class User {

    private final Long id;
    private final String name;
    private final String email;
    private final String internalNote;

    public User(Long id, String name, String email, String internalNote) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.internalNote = internalNote;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getInternalNote() {
        return internalNote;
    }
}

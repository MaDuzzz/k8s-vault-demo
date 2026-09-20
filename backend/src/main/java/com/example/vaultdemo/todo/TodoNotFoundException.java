package com.example.vaultdemo.todo;

public class TodoNotFoundException extends RuntimeException {

    public TodoNotFoundException(long id) {
        super("Todo %d was not found".formatted(id));
    }
}

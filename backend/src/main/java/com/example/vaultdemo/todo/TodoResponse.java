package com.example.vaultdemo.todo;

import java.time.Instant;

public record TodoResponse(
        Long id,
        String title,
        boolean completed,
        Instant createdAt,
        Instant updatedAt) {

    static TodoResponse from(Todo todo) {
        return new TodoResponse(
                todo.getId(),
                todo.getTitle(),
                todo.isCompleted(),
                todo.getCreatedAt(),
                todo.getUpdatedAt());
    }
}

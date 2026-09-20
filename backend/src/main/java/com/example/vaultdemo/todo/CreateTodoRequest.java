package com.example.vaultdemo.todo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTodoRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must contain at most 255 characters")
        String title) {
}

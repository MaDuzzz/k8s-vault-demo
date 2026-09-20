package com.example.vaultdemo.todo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTodoRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must contain at most 255 characters")
        String title,
        @NotNull(message = "completed is required") Boolean completed) {
}

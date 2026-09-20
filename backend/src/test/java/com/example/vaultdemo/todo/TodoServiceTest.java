package com.example.vaultdemo.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TodoRepository repository;

    @InjectMocks
    private TodoService service;

    @Test
    void createsTodoAndNormalizesTitle() {
        when(repository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TodoResponse response = service.create(new CreateTodoRequest("  renew credentials  "));

        assertThat(response.title()).isEqualTo("renew credentials");
        assertThat(response.completed()).isFalse();
    }

    @Test
    void updatesExistingTodo() {
        Todo todo = new Todo("old");
        when(repository.findById(7L)).thenReturn(Optional.of(todo));
        when(repository.save(todo)).thenReturn(todo);

        TodoResponse response = service.update(7L, new UpdateTodoRequest("new", true));

        assertThat(response.title()).isEqualTo("new");
        assertThat(response.completed()).isTrue();
    }

    @Test
    void deleteRejectsUnknownTodo() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(TodoNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void deletesKnownTodo() {
        Todo todo = new Todo("done");
        when(repository.findById(3L)).thenReturn(Optional.of(todo));

        service.delete(3L);

        verify(repository).delete(todo);
    }
}

package com.example.vaultdemo.todo;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TodoService {

    private final TodoRepository repository;

    public TodoService(TodoRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<TodoResponse> findAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(TodoResponse::from)
                .toList();
    }

    @Transactional
    public TodoResponse create(CreateTodoRequest request) {
        Todo todo = new Todo(request.title().trim());
        return TodoResponse.from(repository.save(todo));
    }

    @Transactional
    public TodoResponse update(long id, UpdateTodoRequest request) {
        Todo todo = repository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException(id));
        todo.update(request.title().trim(), request.completed());
        return TodoResponse.from(repository.save(todo));
    }

    @Transactional
    public void delete(long id) {
        Todo todo = repository.findById(id)
                .orElseThrow(() -> new TodoNotFoundException(id));
        repository.delete(todo);
    }
}

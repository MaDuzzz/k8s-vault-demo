package com.example.vaultdemo.todo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TodoController.class)
class TodoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TodoService service;

    @Test
    void createsTodo() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(service.create(any())).thenReturn(new TodoResponse(12L, "demo", false, now, now));

        mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"demo"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/todos/12"))
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.title").value("demo"));
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value("title must not be blank"));
    }

    @Test
    void updatesTodo() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(service.update(anyLong(), any()))
                .thenReturn(new TodoResponse(4L, "updated", true, now, now));

        mockMvc.perform(put("/api/todos/4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"updated","completed":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void deletesTodo() throws Exception {
        doNothing().when(service).delete(4L);

        mockMvc.perform(delete("/api/todos/4"))
                .andExpect(status().isNoContent());
    }
}

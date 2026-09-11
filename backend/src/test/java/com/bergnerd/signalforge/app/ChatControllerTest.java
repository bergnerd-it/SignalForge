package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.chat.ChatActionExecution;
import com.bergnerd.signalforge.app.chat.ChatController;
import com.bergnerd.signalforge.app.chat.ChatMessageRecord;
import com.bergnerd.signalforge.app.chat.ChatRequest;
import com.bergnerd.signalforge.app.chat.ChatResponse;
import com.bergnerd.signalforge.app.chat.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldSendMessage() throws Exception {
        ChatRequest req = new ChatRequest("Buy 10 AAPL");
        ChatResponse res = new ChatResponse(
                "Bought 10 AAPL",
                List.of(new ChatActionExecution("trade", "AAPL", "BUY 10.00 shares", true, null)),
                Instant.now().toString()
        );

        when(chatService.processUserMessage(eq("default"), eq("Buy 10 AAPL"))).thenReturn(res);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Bought 10 AAPL"))
                .andExpect(jsonPath("$.actions[0].ticker").value("AAPL"));
    }

    @Test
    void shouldGetChatHistory() throws Exception {
        List<ChatMessageRecord> history = List.of(
                new ChatMessageRecord("m-1", "user", "Hello", null, Instant.now().toString())
        );
        when(chatService.getChatHistory("default")).thenReturn(history);

        mockMvc.perform(get("/api/chat/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Hello"));
    }

    @Test
    void shouldClearChatHistory() throws Exception {
        mockMvc.perform(delete("/api/chat/history"))
                .andExpect(status().isNoContent());

        verify(chatService).clearChatHistory("default");
    }
}

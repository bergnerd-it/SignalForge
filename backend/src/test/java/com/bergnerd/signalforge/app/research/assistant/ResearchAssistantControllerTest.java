package com.bergnerd.signalforge.app.research.assistant;

import com.bergnerd.signalforge.app.research.assistant.ResearchAssistantDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchAssistantController.class)
class ResearchAssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchAssistantService assistantService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void chat_withOwnerScoping_returnsStructuredAssistantResponse() throws Exception {
        ChatRequest request = new ChatRequest(
                "What is my current cash?",
                new ResearchContextDto("PORTFOLIO", "port-1")
        );

        ChatResponse response = new ChatResponse(
                "The portfolio has EUR 10000.00 cash.",
                List.of(new FactCardDto("Cash Balance", "EUR 10000.00", "Available cash balance", "FINANCIAL")),
                List.of(new EvidenceReferenceDto("PORTFOLIO", "port-1", "2026-09-15T09:00:00Z", "Portfolio state cashBalance"))
        );

        when(assistantService.processQuery(eq("user-456"), any(), any(ChatRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/research/assistant/chat")
                        .header("X-User-Id", "user-456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("The portfolio has EUR 10000.00 cash."))
                .andExpect(jsonPath("$.factCards[0].title").value("Cash Balance"))
                .andExpect(jsonPath("$.factCards[0].value").value("EUR 10000.00"))
                .andExpect(jsonPath("$.evidenceReferences[0].id").value("port-1"));
    }
}

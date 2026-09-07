package com.financeally.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeally.app.watchlist.WatchlistAddRequest;
import com.financeally.app.watchlist.WatchlistController;
import com.financeally.app.watchlist.WatchlistEntryDto;
import com.financeally.app.watchlist.WatchlistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WatchlistController.class)
class WatchlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WatchlistService watchlistService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldGetWatchlist() throws Exception {
        List<WatchlistEntryDto> list = List.of(
                new WatchlistEntryDto("w-1", "AAPL", 190.0, 189.0, 1.0, 0.53, "up", Instant.now().toString())
        );
        when(watchlistService.getWatchlist("default")).thenReturn(list);

        mockMvc.perform(get("/api/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].price").value(190.0));
    }

    @Test
    void shouldAddTicker() throws Exception {
        WatchlistAddRequest req = new WatchlistAddRequest("TSLA");
        WatchlistEntryDto dto = new WatchlistEntryDto("w-2", "TSLA", 215.0, 215.0, 0.0, 0.0, "flat", Instant.now().toString());
        when(watchlistService.addTicker(eq("default"), eq("TSLA"))).thenReturn(dto);

        mockMvc.perform(post("/api/watchlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("TSLA"));
    }

    @Test
    void shouldRemoveTicker() throws Exception {
        when(watchlistService.removeTicker(eq("default"), eq("TSLA"))).thenReturn(true);

        mockMvc.perform(delete("/api/watchlist/TSLA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));
    }
}

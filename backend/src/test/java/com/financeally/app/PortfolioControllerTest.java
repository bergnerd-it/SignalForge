package com.financeally.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeally.app.portfolio.PortfolioController;
import com.financeally.app.portfolio.PortfolioResponse;
import com.financeally.app.portfolio.PortfolioService;
import com.financeally.app.portfolio.TradeRequest;
import com.financeally.app.portfolio.TradeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PortfolioController.class)
class PortfolioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PortfolioService portfolioService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnPortfolio() throws Exception {
        PortfolioResponse mockPortfolio = new PortfolioResponse(
                "default", 10000.0, 0.0, 10000.0, 0.0, 0.0, List.of()
        );
        when(portfolioService.getPortfolio("default")).thenReturn(mockPortfolio);

        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashBalance").value(10000.0))
                .andExpect(jsonPath("$.totalPortfolioValue").value(10000.0));
    }

    @Test
    void shouldExecuteTradeEndpoint() throws Exception {
        TradeRequest request = new TradeRequest("AAPL", 5.0, "buy");
        PortfolioResponse updatedPortfolio = new PortfolioResponse(
                "default", 9050.0, 950.0, 10000.0, 0.0, 0.0, List.of()
        );
        TradeResponse mockResponse = new TradeResponse(
                "trade-123", "AAPL", "buy", 5.0, 190.0, 950.0, Instant.now().toString(), updatedPortfolio
        );

        when(portfolioService.executeTrade(eq("default"), any(TradeRequest.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/portfolio/trade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeId").value("trade-123"))
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.side").value("buy"))
                .andExpect(jsonPath("$.quantity").value(5.0));
    }

    @Test
    void shouldAcceptTradePayloadWithPriceAndExtraFields() throws Exception {
        PortfolioResponse updatedPortfolio = new PortfolioResponse(
                "default", 6546.25, 3453.75, 10000.0, 0.0, 0.0, List.of()
        );
        TradeResponse mockResponse = new TradeResponse(
                "trade-tsla", "TSLA", "buy", 15.0, 230.25, 3453.75, Instant.now().toString(), updatedPortfolio
        );

        when(portfolioService.executeTrade(eq("default"), any(TradeRequest.class))).thenReturn(mockResponse);

        String payloadWithPrice = """
                {
                  "ticker": "TSLA",
                  "side": "buy",
                  "quantity": 15.0,
                  "price": 230.25
                }
                """;

        mockMvc.perform(post("/api/portfolio/trade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithPrice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("TSLA"))
                .andExpect(jsonPath("$.quantity").value(15.0));
    }

    @Test
    void shouldReturnBadRequestWhenMissingTickerOrInvalidQuantity() throws Exception {
        String invalidPayload = """
                {
                  "ticker": "",
                  "side": "buy",
                  "quantity": -5.0
                }
                """;

        mockMvc.perform(post("/api/portfolio/trade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void shouldReturnBadRequestWithClearMessageOnInsufficientFunds() throws Exception {
        when(portfolioService.executeTrade(eq("default"), any(TradeRequest.class)))
                .thenThrow(new com.financeally.app.portfolio.TradeExceptions.InsufficientFundsException(
                        "Insufficient funds: required $6512.60, available $5292.85"
                ));

        String payload = """
                {
                  "ticker": "NFLX",
                  "side": "buy",
                  "quantity": 10.0,
                  "price": 651.26
                }
                """;

        mockMvc.perform(post("/api/portfolio/trade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient funds: required $6512.60, available $5292.85"));
    }

    @Test
    void shouldReturnBadRequestWithClearMessageOnInsufficientShares() throws Exception {
        when(portfolioService.executeTrade(eq("default"), any(TradeRequest.class)))
                .thenThrow(new com.financeally.app.portfolio.TradeExceptions.InsufficientSharesException(
                        "Insufficient shares: attempted to sell 10.00 NFLX, owned 2.00"
                ));

        String payload = """
                {
                  "ticker": "NFLX",
                  "side": "sell",
                  "quantity": 10.0
                }
                """;

        mockMvc.perform(post("/api/portfolio/trade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient shares: attempted to sell 10.00 NFLX, owned 2.00"));
    }
}

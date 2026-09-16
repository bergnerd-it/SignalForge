package com.bergnerd.signalforge.app.research;

import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.research.ResearchDtos.*;
import com.bergnerd.signalforge.app.research.paper.PaperDataReadinessService;
import com.bergnerd.signalforge.app.research.paper.PaperExportService;
import com.bergnerd.signalforge.app.research.paper.PaperPortfolioService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResearchPortfolioController.class)
class ResearchPortfolioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OperationService operationService;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PaperPortfolioService paperPortfolioService;

    @MockBean
    private PaperDataReadinessService dataReadinessService;

    @MockBean
    private PaperExportService paperExportService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createPortfolio_withOwnerHeaderAndIdempotencyKey_returnsCreated() throws Exception {
        CreatePortfolioRequest request = new CreatePortfolioRequest(
                "Alpha EUR Paper",
                "PAPER",
                "EUR",
                "10000.00",
                "idemp-key-1"
        );

        OperationService.PortfolioCreationResult creationResult = new OperationService.PortfolioCreationResult(
                "port-1",
                "user-123",
                "Alpha EUR Paper",
                "PAPER",
                "EUR",
                "10000.00",
                "2026-09-15T09:00:00Z",
                false
        );

        OperationService.PortfolioView view = new OperationService.PortfolioView(
                "port-1",
                "user-123",
                "Alpha EUR Paper",
                "PAPER",
                "EUR",
                "10000.00",
                1,
                List.of()
        );

        when(operationService.createPortfolio(eq("user-123"), eq("Alpha EUR Paper"), eq("PAPER"), eq("EUR"), any(BigDecimal.class), eq("idemp-key-1")))
                .thenReturn(creationResult);
        when(operationService.getPortfolioView(eq("port-1"))).thenReturn(view);
        when(jdbcTemplate.queryForObject(contains("paper_started_at"), eq(String.class), eq("port-1"))).thenReturn(null);
        when(jdbcTemplate.queryForList(contains("paper_portfolio_segments"), eq("port-1"))).thenReturn(List.of());

        mockMvc.perform(post("/api/research/portfolios")
                        .header("X-User-Id", "user-123")
                        .header("Idempotency-Key", "idemp-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("port-1"))
                .andExpect(jsonPath("$.ownerId").value("user-123"))
                .andExpect(jsonPath("$.baseCurrency").value("EUR"))
                .andExpect(jsonPath("$.cashBalance").value("10000.00"));
    }

    @Test
    void acceptProposal_callsPaperPortfolioService() throws Exception {
        PaperProposalDto acceptedDto = new PaperProposalDto(
                "prop-1",
                "port-1",
                "cycle-1",
                "ETF_BUY_HOLD_V1",
                "1.0.0",
                "ds-1",
                "chk-1",
                "XAMS",
                "1.0",
                "2026-09-15",
                "2026-09-15T08:00:00Z",
                "2026-09-15T08:30:00Z",
                "2026-09-15",
                "2026-09-15T09:00:00Z",
                "DAILY_REBALANCE",
                1,
                "ACCEPTED",
                "2026-09-15T09:05:00Z",
                null,
                null,
                null,
                null,
                "2026-09-15T09:00:00Z",
                List.of(),
                List.of()
        );

        when(paperPortfolioService.acceptProposal(eq("port-1"), eq("prop-1"), eq("user-123"), any(), any(AcceptProposalRequest.class)))
                .thenReturn(acceptedDto);

        mockMvc.perform(post("/api/research/portfolios/port-1/proposals/prop-1/accept")
                        .header("X-User-Id", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AcceptProposalRequest("Accept manual"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("prop-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void exportAuditZip_streamsZipFile() throws Exception {
        byte[] zipBytes = new byte[]{0x50, 0x4b, 0x03, 0x04}; // ZIP magic header
        when(paperExportService.createPaperAuditZip(eq("port-1"), eq("user-123")))
                .thenReturn(zipBytes);

        mockMvc.perform(get("/api/research/portfolios/port-1/export.zip")
                        .header("X-User-Id", "user-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"paper-portfolio-port-1-audit.zip\""))
                .andExpect(content().bytes(zipBytes));
    }
}

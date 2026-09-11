package com.bergnerd.signalforge.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TemporarySqliteInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SignalForgeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldServeHealthEndpoint() {
        ResponseEntity<Map> response = restTemplate.getForEntity("http://localhost:" + port + "/api/health", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    void shouldServePortfolioEndpoint() {
        ResponseEntity<Map> response = restTemplate.getForEntity("http://localhost:" + port + "/api/portfolio", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10000.0, ((Number) response.getBody().get("cashBalance")).doubleValue());
    }

    @Test
    void shouldServeWatchlistEndpoint() {
        ResponseEntity<Object[]> response = restTemplate.getForEntity("http://localhost:" + port + "/api/watchlist", Object[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getBody().length);
    }

    @Test
    void independentPoolConnectionsShareSchemaAndEnforceForeignKeys() throws Exception {
        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
            assertNotSame(first, second);
            int firstCount = scalar(first, "SELECT COUNT(*) FROM watchlist");
            assertTrue(firstCount >= 10);
            assertEquals(firstCount, scalar(second, "SELECT COUNT(*) FROM watchlist"));
            assertEquals(1, scalar(first, "PRAGMA foreign_keys"));
            assertEquals(1, scalar(second, "PRAGMA foreign_keys"));
        }
    }

    @Test
    void allowsLocalDevelopmentOriginAndRejectsCrossOriginMutationWithoutSideEffect() throws Exception {
        int before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM watchlist", Integer.class);

        mockMvc.perform(post("/api/watchlist")
                        .header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/watchlist")
                        .header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"PYPL\"}"))
                .andExpect(status().isForbidden());
        assertEquals(before, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM watchlist", Integer.class));
    }

    private int scalar(Connection connection, String sql) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

}

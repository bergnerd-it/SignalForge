package com.bergnerd.signalforge.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "signalforge.llm.mock=true"
})
class SignalForgeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

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
}

package com.bergnerd.signalforge.app.research.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/research/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public List<BacktestDtos.StrategyVersionDto> listStrategies() {
        return jdbcTemplate.query(
                "SELECT strategy_id, strategy_version, name, description, parameters_schema_json, " +
                        "calculation_policy_version, decision_schedule, created_at FROM strategy_versions " +
                        "ORDER BY strategy_id ASC, strategy_version ASC",
                (rs, rowNum) -> new BacktestDtos.StrategyVersionDto(
                        rs.getString("strategy_id"),
                        rs.getString("strategy_version"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("parameters_schema_json"),
                        rs.getString("calculation_policy_version"),
                        rs.getString("decision_schedule"),
                        rs.getString("created_at")
                )
        );
    }
}

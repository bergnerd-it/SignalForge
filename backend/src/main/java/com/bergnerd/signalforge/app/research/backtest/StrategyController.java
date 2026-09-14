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
                (rs, rowNum) -> mapStrategyRow(rs)
        );
    }

    @GetMapping("/{id}")
    public BacktestDtos.StrategyVersionDto getStrategy(@org.springframework.web.bind.annotation.PathVariable("id") String id) {
        List<BacktestDtos.StrategyVersionDto> results = jdbcTemplate.query(
                "SELECT strategy_id, strategy_version, name, description, parameters_schema_json, " +
                        "calculation_policy_version, decision_schedule, created_at FROM strategy_versions " +
                        "WHERE strategy_id = ? ORDER BY strategy_version DESC LIMIT 1",
                (rs, rowNum) -> mapStrategyRow(rs),
                id
        );
        if (results.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Strategy not found: " + id
            );
        }
        return results.get(0);
    }

    private BacktestDtos.StrategyVersionDto mapStrategyRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String stratId = rs.getString("strategy_id");
        String stratVer = rs.getString("strategy_version");
        String name = rs.getString("name");
        String desc = rs.getString("description");
        String schemaJson = rs.getString("parameters_schema_json");
        String calcVer = rs.getString("calculation_policy_version");
        String sched = rs.getString("decision_schedule");
        String createdAt = rs.getString("created_at");

        String family;
        String rebal;
        String execModel;
        java.util.List<BacktestDtos.StrategyParameterDefinitionDto> params = new java.util.ArrayList<>();

        if (stratId != null && stratId.contains("MOMENTUM")) {
            family = "CROSS_SECTIONAL_MOMENTUM";
            rebal = "MONTHLY";
            execModel = "TARGET_WEIGHT_REBALANCE";
            params.add(new BacktestDtos.StrategyParameterDefinitionDto(
                    "k", "integer", 3, true, "Number of top momentum assets to select"
            ));
        } else if (stratId != null && (stratId.contains("TREND") || stratId.contains("CASH-FILTER"))) {
            family = "TIME_SERIES_TREND";
            rebal = "MONTHLY";
            execModel = "ALLOCATION_SWITCH";
            if ("1.0.0".equals(stratVer)) {
                params.add(new BacktestDtos.StrategyParameterDefinitionDto(
                        "lookbackMonths", "integer", 10, false, "Legacy fixed 10-month metadata"
                ));
            }
        } else {
            family = "BUY_AND_HOLD";
            rebal = "BUY_AND_HOLD";
            execModel = "OPEN_AUCTION_REINVEST";
        }

        return new BacktestDtos.StrategyVersionDto(
                stratId,
                stratVer,
                name,
                family,
                "ACTIVE",
                desc,
                rebal,
                execModel,
                schemaJson,
                params,
                java.util.List.of("XETR"),
                java.util.List.of("EUR"),
                calcVer,
                sched,
                createdAt
        );
    }
}

#!/usr/bin/env python3
import argparse
import json
import os
import sqlite3
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path


def request(base_url, path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json", "Origin": base_url} if body is not None else {}
    with urllib.request.urlopen(
            urllib.request.Request(base_url + path, data=data, headers=headers, method=method), timeout=5) as response:
        return json.load(response)


def wait_ready(base_url, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            request(base_url, "/api/health")
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError("Application did not become ready within 60 seconds")


def stable_api_state(base_url):
    portfolio = request(base_url, "/api/portfolio")
    watchlist = request(base_url, "/api/watchlist")
    return {
        "cashBalance": portfolio["cashBalance"],
        "positions": sorted(
            (position["ticker"], position["quantity"], position["avgCost"])
            for position in portfolio["positions"]
        ),
        "watchlist": sorted(entry["ticker"] for entry in watchlist),
    }


def database_state(compose, project):
    with tempfile.TemporaryDirectory(prefix="signalforge-restart-") as directory:
        subprocess.run(
            compose + ["-p", project, "cp", "app-test:/app/test-db/.", directory],
            check=True,
        )
        target = Path(directory) / "signalforge-e2e.db"
        with sqlite3.connect(f"file:{target}?mode=ro", uri=True) as connection:
            return connection.execute(
                "SELECT id, ticker, side, quantity, price, executed_at FROM trades ORDER BY executed_at, id"
            ).fetchall()


def main():
    parser = argparse.ArgumentParser(description="Assert SignalForge persistence across a disposable Compose restart")
    parser.add_argument("--project", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--compose-file", default="test/docker-compose.test.yml")
    args = parser.parse_args()
    if not args.project.startswith("signalforge-m1a-closeout-"):
        raise SystemExit("Project must use the unique signalforge-m1a-closeout- prefix")

    base_url = f"http://127.0.0.1:{args.port}"
    os.environ["SIGNALFORGE_TEST_PORT"] = str(args.port)
    compose = ["docker", "compose", "-f", args.compose_file]
    wait_ready(base_url)
    request(base_url, "/api/watchlist", "POST", {"ticker": "ZZZZ"})
    trade = request(base_url, "/api/portfolio/trade", "POST", {"ticker": "AAPL", "quantity": 1, "side": "buy"})
    before_api = stable_api_state(base_url)
    before_trades = database_state(compose, args.project)

    assert "ZZZZ" in before_api["watchlist"]
    assert before_api["cashBalance"] != 10000.0
    assert any(row[0] == trade["tradeId"] for row in before_trades)

    subprocess.run(compose + ["-p", args.project, "restart", "app-test"], check=True)
    wait_ready(base_url)
    after_api = stable_api_state(base_url)
    after_trades = database_state(compose, args.project)

    assert after_api == before_api, (before_api, after_api)
    assert after_trades == before_trades, (before_trades, after_trades)
    print(json.dumps({"before": before_api, "after": after_api, "tradeIds": [row[0] for row in after_trades]}, indent=2))


if __name__ == "__main__":
    main()

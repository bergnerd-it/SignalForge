#!/usr/bin/env python3
import hashlib
import json
import os
import sqlite3
import uuid
from pathlib import Path

FIXTURES_DIR = Path(__file__).resolve().parent
SCHEMA_FILE = FIXTURES_DIR.parent.parent / "backend/src/main/resources/db/schema.sql"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def generate_legacy_fixture():
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    db_path = FIXTURES_DIR / "legacy_multi_owner.db"
    untouched_path = FIXTURES_DIR / "legacy_multi_owner_untouched.db"

    if db_path.exists():
        db_path.unlink()
    if untouched_path.exists():
        untouched_path.unlink()

    with open(SCHEMA_FILE, "r", encoding="utf-8") as f:
        schema_sql = f.read()

    conn = sqlite3.connect(str(db_path))
    cursor = conn.cursor()

    # Execute schema
    for stmt in schema_sql.split(";"):
        stmt = stmt.strip()
        if stmt:
            cursor.execute(stmt)

    now = "2026-09-11T12:00:00Z"
    t1 = "2026-09-11T12:05:00Z"
    t2 = "2026-09-11T12:10:00Z"
    t3 = "2026-09-11T12:15:00Z"

    # 1. Owner: 'default'
    cursor.execute(
        "INSERT INTO users_profile (id, cash_balance, created_at) VALUES (?, ?, ?)",
        ("default", 7500.25, now),
    )
    # Positions (fractional AAPL)
    cursor.execute(
        "INSERT INTO positions (id, user_id, ticker, quantity, avg_cost, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
        (str(uuid.uuid4()), "default", "AAPL", 10.5, 150.25, t2),
    )
    cursor.execute(
        "INSERT INTO positions (id, user_id, ticker, quantity, avg_cost, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
        (str(uuid.uuid4()), "default", "MSFT", 5.0, 310.50, t3),
    )
    # Trades
    cursor.execute(
        "INSERT INTO trades (id, user_id, ticker, side, quantity, price, executed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ("trade-default-1", "default", "AAPL", "buy", 5.5, 145.0, t1),
    )
    cursor.execute(
        "INSERT INTO trades (id, user_id, ticker, side, quantity, price, executed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ("trade-default-2", "default", "AAPL", "buy", 5.0, 156.025, t2),
    )
    cursor.execute(
        "INSERT INTO trades (id, user_id, ticker, side, quantity, price, executed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ("trade-default-3", "default", "MSFT", "buy", 5.0, 310.50, t3),
    )
    # Watchlist (10 standard tickers)
    default_tickers = ["AAPL", "GOOGL", "MSFT", "AMZN", "TSLA", "NVDA", "META", "JPM", "V", "NFLX"]
    for sym in default_tickers:
        cursor.execute(
            "INSERT INTO watchlist (id, user_id, ticker, added_at) VALUES (?, ?, ?, ?)",
            (str(uuid.uuid4()), "default", sym, now),
        )
    # Snapshots
    cursor.execute(
        "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
        (str(uuid.uuid4()), "default", 10000.00, now),
    )
    cursor.execute(
        "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
        (str(uuid.uuid4()), "default", 9950.00, t2),
    )
    cursor.execute(
        "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
        (str(uuid.uuid4()), "default", 10125.50, t3),
    )
    # Chat messages
    cursor.execute(
        "INSERT INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        ("msg-default-1", "default", "user", "buy 5 shares of MSFT", None, t2),
    )
    chat_action = json.dumps([{"type": "trade", "ticker": "MSFT", "description": "BUY 5.00 shares of MSFT", "success": True, "error": None}])
    cursor.execute(
        "INSERT INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        ("msg-default-2", "default", "assistant", "Executed BUY 5 MSFT", chat_action, t3),
    )

    # 2. Owner: 'alice'
    cursor.execute(
        "INSERT INTO users_profile (id, cash_balance, created_at) VALUES (?, ?, ?)",
        ("alice", 4200.00, now),
    )
    cursor.execute(
        "INSERT INTO positions (id, user_id, ticker, quantity, avg_cost, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
        (str(uuid.uuid4()), "alice", "TSLA", 12.25, 210.00, t1),
    )
    cursor.execute(
        "INSERT INTO trades (id, user_id, ticker, side, quantity, price, executed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ("trade-alice-1", "alice", "TSLA", "buy", 12.25, 210.00, t1),
    )
    for sym in ["TSLA", "NVDA", "AMZN"]:
        cursor.execute(
            "INSERT INTO watchlist (id, user_id, ticker, added_at) VALUES (?, ?, ?, ?)",
            (str(uuid.uuid4()), "alice", sym, now),
        )
    cursor.execute(
        "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
        (str(uuid.uuid4()), "alice", 6772.50, t1),
    )

    # 3. Owner: 'bob' (Intentionally EMPTY watchlist, no positions, starting cash 10000)
    cursor.execute(
        "INSERT INTO users_profile (id, cash_balance, created_at) VALUES (?, ?, ?)",
        ("bob", 10000.00, now),
    )
    cursor.execute(
        "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
        (str(uuid.uuid4()), "bob", 10000.00, now),
    )
    # Bob has NO entries in watchlist, positions, trades, or chat_messages.

    conn.commit()

    # Collect manifest counts
    tables = ["users_profile", "watchlist", "positions", "trades", "portfolio_snapshots", "chat_messages"]
    row_counts = {}
    for table in tables:
        cursor.execute(f"SELECT COUNT(*) FROM {table}")
        row_counts[table] = cursor.fetchone()[0]

    # Distinct owners
    cursor.execute("SELECT id, cash_balance FROM users_profile ORDER BY id")
    owners = [{"id": row[0], "cash_balance": row[1]} for row in cursor.fetchall()]

    conn.close()

    # Create untouched copy
    with open(db_path, "rb") as src, open(untouched_path, "wb") as dst:
        dst.write(src.read())

    manifest = {
        "fixture_file": "legacy_multi_owner.db",
        "untouched_file": "legacy_multi_owner_untouched.db",
        "sha256": sha256_file(db_path),
        "untouched_sha256": sha256_file(untouched_path),
        "tables": row_counts,
        "owners": owners,
        "created_at": "2026-09-12T08:52:00Z",
        "notes": "Representative legacy multi-owner SQLite fixture including fractional holdings, snapshots, chat, and empty watchlist"
    }

    manifest_path = FIXTURES_DIR / "legacy_fixtures_manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print("Generated legacy fixtures:")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    generate_legacy_fixture()

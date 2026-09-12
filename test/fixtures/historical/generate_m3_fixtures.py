#!/usr/bin/env python3
"""
Deterministic fixture generator for SignalForge M3 reference scenario and backtest baseline.
Generates:
- m3-reference-baseline.zip
- m3-lookahead-action.zip
- m3-missing-bar.zip
"""

import os
import zipfile
import json

FIXTURES_DIR = os.path.dirname(os.path.abspath(__file__))

def create_m3_fixtures():
    manifest = {
        "schema_version": "1.0",
        "source": "signalforge-m3-baseline-generator",
        "retrieved_at": "2026-09-12T12:00:00Z",
        "coverage": {
            "start_date": "2024-01-31",
            "end_date": "2024-02-07"
        },
        "license_note": "Synthetic test data generated for SignalForge M3 acceptance verification",
        "classification": "SYNTHETIC",
        "price_convention": "RAW",
        "calendar_completeness": "Declared exchange calendar with weekend closure",
        "action_completeness": "Declared 2:1 split and 1.00 EUR distribution",
        "known_limitations": "Deterministic synthetic scenario matching PROMPT-SIGNALFORGE-M3 section 9",
        "availability_assumptions": "End-of-day available_at timestamps set at 18:00:00Z"
    }

    instruments_csv = """instrument_id,listing_id,type,name,isin,venue,symbol,quote_currency,calendar_id,inception_date,termination_date
inst-eur-syn-1,listing-eur-syn-1,EQUITY,Synthetic ETF 1,IE000SYN0001,XETRA,SYNE1,EUR,cal-eur-xetra,2023-01-01,
inst-eur-syn-2,listing-eur-syn-2,EQUITY,Synthetic ETF 2,IE000SYN0002,XETRA,SYNE2,EUR,cal-eur-xetra,2023-01-01,
"""

    sessions_csv = """calendar_id,session_date,open_time,close_time,session_type
cal-eur-xetra,2024-01-31,2024-01-31T08:00:00Z,2024-01-31T16:30:00Z,TRADING
cal-eur-xetra,2024-02-01,2024-02-01T08:00:00Z,2024-02-01T16:30:00Z,TRADING
cal-eur-xetra,2024-02-02,2024-02-02T08:00:00Z,2024-02-02T16:30:00Z,TRADING
cal-eur-xetra,2024-02-03,2024-02-03T08:00:00Z,2024-02-03T16:30:00Z,CLOSED
cal-eur-xetra,2024-02-04,2024-02-04T08:00:00Z,2024-02-04T16:30:00Z,CLOSED
cal-eur-xetra,2024-02-05,2024-02-05T08:00:00Z,2024-02-05T16:30:00Z,TRADING
cal-eur-xetra,2024-02-06,2024-02-06T08:00:00Z,2024-02-06T16:30:00Z,TRADING
cal-eur-xetra,2024-02-07,2024-02-07T08:00:00Z,2024-02-07T16:30:00Z,TRADING
"""

    prices_csv = """listing_id,session_date,open,high,low,close,volume,available_at
listing-eur-syn-1,2024-01-31,100.00,100.00,100.00,100.00,10000,2024-01-31T18:00:00Z
listing-eur-syn-1,2024-02-01,100.00,100.00,100.00,100.00,10000,2024-02-01T18:00:00Z
listing-eur-syn-1,2024-02-02,50.00,50.00,50.00,50.00,20000,2024-02-02T18:00:00Z
listing-eur-syn-1,2024-02-05,49.00,49.00,49.00,49.00,15000,2024-02-05T18:00:00Z
listing-eur-syn-1,2024-02-06,49.00,49.00,49.00,49.00,15000,2024-02-06T18:00:00Z
listing-eur-syn-1,2024-02-07,49.00,50.00,49.00,50.00,25000,2024-02-07T18:00:00Z
listing-eur-syn-2,2024-01-31,100.00,100.00,100.00,100.00,10000,2024-01-31T18:00:00Z
listing-eur-syn-2,2024-02-01,100.00,100.00,100.00,100.00,10000,2024-02-01T18:00:00Z
listing-eur-syn-2,2024-02-02,50.00,50.00,50.00,50.00,20000,2024-02-02T18:00:00Z
listing-eur-syn-2,2024-02-05,49.00,49.00,49.00,49.00,15000,2024-02-05T18:00:00Z
listing-eur-syn-2,2024-02-06,49.00,49.00,49.00,49.00,15000,2024-02-06T18:00:00Z
listing-eur-syn-2,2024-02-07,49.00,50.00,49.00,50.00,25000,2024-02-07T18:00:00Z
"""

    actions_csv = """action_id,listing_id,action_type,effective_date,available_at,split_ratio,distribution_amount,distribution_currency,payment_date,payment_instant
act-m3-split-1,listing-eur-syn-1,SPLIT,2024-02-02,2024-02-01T20:00:00Z,2:1,,,,
act-m3-div-1,listing-eur-syn-1,CASH_DISTRIBUTION,2024-02-05,2024-02-02T20:00:00Z,,1.00,EUR,2024-02-06,
act-m3-split-2,listing-eur-syn-2,SPLIT,2024-02-02,2024-02-01T20:00:00Z,2:1,,,,
act-m3-div-2,listing-eur-syn-2,CASH_DISTRIBUTION,2024-02-05,2024-02-02T20:00:00Z,,1.00,EUR,2024-02-06,
"""

    # 1. m3-reference-baseline.zip
    p_ref = os.path.join(FIXTURES_DIR, "m3-reference-baseline.zip")
    with zipfile.ZipFile(p_ref, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", prices_csv)
        z.writestr("actions.csv", actions_csv)
    print(f"Generated {p_ref}")

    # 2. m3-lookahead-action.zip: action available_at strictly after session open (e.g. 10:00:00Z vs 08:00:00Z)
    lookahead_actions_csv = actions_csv.replace("2024-02-01T20:00:00Z", "2024-02-02T10:00:00Z")
    p_lookahead = os.path.join(FIXTURES_DIR, "m3-lookahead-action.zip")
    with zipfile.ZipFile(p_lookahead, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", prices_csv)
        z.writestr("actions.csv", lookahead_actions_csv)
    print(f"Generated {p_lookahead}")

    # 3. m3-missing-bar.zip: missing bar on trading session 2024-02-05
    missing_prices_csv = prices_csv.replace("listing-eur-syn-1,2024-02-05,49.00,49.00,49.00,49.00,15000,2024-02-05T18:00:00Z\n", "")
    p_missing = os.path.join(FIXTURES_DIR, "m3-missing-bar.zip")
    with zipfile.ZipFile(p_missing, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", missing_prices_csv)
        z.writestr("actions.csv", actions_csv)
    print(f"Generated {p_missing}")

if __name__ == "__main__":
    create_m3_fixtures()

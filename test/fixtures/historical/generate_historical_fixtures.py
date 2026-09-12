#!/usr/bin/env python3
"""
Deterministic fixture generator for SignalForge M2 historical import bundles.
Produces:
- valid-sample-bundle.zip
- invalid-missing-bar.zip
- invalid-adjusted-data.zip
- invalid-duplicate-keys.zip
- invalid-bad-actions.zip
- invalid-traversal.zip
"""

import os
import zipfile
import json

FIXTURES_DIR = os.path.dirname(os.path.abspath(__file__))

def create_valid_bundle():
    manifest = {
        "schema_version": "1.0",
        "source": "signalforge-synthetic-generator-v1",
        "retrieved_at": "2026-09-12T10:00:00Z",
        "coverage": {
            "start_date": "2024-01-01",
            "end_date": "2024-01-10"
        },
        "license_note": "Synthetic test data generated for SignalForge M2 verification",
        "classification": "SYNTHETIC",
        "price_convention": "RAW",
        "calendar_completeness": "Declared exchange calendar with New Year holiday closure",
        "action_completeness": "Explicitly declared corporate actions covering the fixture period",
        "known_limitations": "Generated mathematical series, not real financial evidence",
        "availability_assumptions": "End-of-day available_at timestamps set at 18:00:00Z"
    }

    instruments_csv = """instrument_id,listing_id,type,name,isin,venue,symbol,quote_currency,calendar_id,inception_date,termination_date
inst-eur-syn-1,listing-eur-syn-1,EQUITY,Synthetic Alpha EUR,IE000SYN0001,XETRA,SYNA,EUR,cal-eur-xetra,2023-01-01,
inst-eur-syn-2,listing-eur-syn-2,EQUITY,Synthetic Beta EUR,IE000SYN0002,XETRA,SYNB,EUR,cal-eur-xetra,2023-01-01,
"""

    sessions_csv = """calendar_id,session_date,open_time,close_time,session_type
cal-eur-xetra,2024-01-01,2024-01-01T08:00:00Z,2024-01-01T16:30:00Z,CLOSED
cal-eur-xetra,2024-01-02,2024-01-02T08:00:00Z,2024-01-02T16:30:00Z,TRADING
cal-eur-xetra,2024-01-03,2024-01-03T08:00:00Z,2024-01-03T16:30:00Z,TRADING
cal-eur-xetra,2024-01-04,2024-01-04T08:00:00Z,2024-01-04T16:30:00Z,TRADING
cal-eur-xetra,2024-01-05,2024-01-05T08:00:00Z,2024-01-05T16:30:00Z,TRADING
cal-eur-xetra,2024-01-08,2024-01-08T08:00:00Z,2024-01-08T16:30:00Z,TRADING
cal-eur-xetra,2024-01-09,2024-01-09T08:00:00Z,2024-01-09T16:30:00Z,TRADING
cal-eur-xetra,2024-01-10,2024-01-10T08:00:00Z,2024-01-10T16:30:00Z,TRADING
"""

    prices_csv = """listing_id,session_date,open,high,low,close,volume,available_at
listing-eur-syn-1,2024-01-02,100.00,105.00,98.50,104.00,15000,2024-01-02T18:00:00Z
listing-eur-syn-1,2024-01-03,104.50,106.00,103.00,105.50,12000,2024-01-03T18:00:00Z
listing-eur-syn-1,2024-01-04,105.00,108.00,104.00,107.00,18000,2024-01-04T18:00:00Z
listing-eur-syn-1,2024-01-05,53.50,55.00,52.00,54.00,32000,2024-01-05T18:00:00Z
listing-eur-syn-1,2024-01-08,54.00,56.00,53.50,55.50,21000,2024-01-08T18:00:00Z
listing-eur-syn-1,2024-01-09,55.00,57.50,54.80,56.80,19500,2024-01-09T18:00:00Z
listing-eur-syn-1,2024-01-10,57.00,58.00,56.00,57.20,16000,2024-01-10T18:00:00Z
listing-eur-syn-2,2024-01-02,50.00,52.00,49.00,51.50,8000,2024-01-02T18:00:00Z
listing-eur-syn-2,2024-01-03,51.50,53.00,51.00,52.20,9500,2024-01-03T18:00:00Z
listing-eur-syn-2,2024-01-04,52.00,52.80,50.50,51.00,7200,2024-01-04T18:00:00Z
listing-eur-syn-2,2024-01-05,51.20,53.50,50.80,53.00,11000,2024-01-05T18:00:00Z
listing-eur-syn-2,2024-01-08,52.00,54.00,51.50,53.50,14000,2024-01-08T18:00:00Z
listing-eur-syn-2,2024-01-09,53.50,55.00,53.00,54.20,10500,2024-01-09T18:00:00Z
listing-eur-syn-2,2024-01-10,54.00,55.50,53.80,55.00,9200,2024-01-10T18:00:00Z
"""

    actions_csv = """action_id,listing_id,action_type,effective_date,available_at,split_ratio,distribution_amount,distribution_currency,payment_date,payment_instant
act-syn-split-1,listing-eur-syn-1,SPLIT,2024-01-05,2024-01-04T18:00:00Z,2:1,,,,
act-syn-div-2,listing-eur-syn-2,CASH_DISTRIBUTION,2024-01-08,2024-01-07T18:00:00Z,,1.25,EUR,2024-01-10,2024-01-10T12:00:00Z
"""

    path = os.path.join(FIXTURES_DIR, "valid-sample-bundle.zip")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", prices_csv)
        z.writestr("actions.csv", actions_csv)
    print(f"Generated {path}")

    # 2. invalid-missing-bar.zip: remove 2024-01-04 for listing-eur-syn-1
    missing_prices_csv = prices_csv.replace("listing-eur-syn-1,2024-01-04,105.00,108.00,104.00,107.00,18000,2024-01-04T18:00:00Z\n", "")
    p_missing = os.path.join(FIXTURES_DIR, "invalid-missing-bar.zip")
    with zipfile.ZipFile(p_missing, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", missing_prices_csv)
        z.writestr("actions.csv", actions_csv)
    print(f"Generated {p_missing}")

    # 3. invalid-adjusted-data.zip: price_convention ADJUSTED
    adj_manifest = dict(manifest)
    adj_manifest["price_convention"] = "ADJUSTED"
    p_adj = os.path.join(FIXTURES_DIR, "invalid-adjusted-data.zip")
    with zipfile.ZipFile(p_adj, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(adj_manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", prices_csv)
        z.writestr("actions.csv", actions_csv)
    print(f"Generated {p_adj}")

    # 4. invalid-duplicate-keys.zip: duplicate bar on 2024-01-02
    dup_prices_csv = prices_csv + "listing-eur-syn-1,2024-01-02,100.00,105.00,98.50,104.00,15000,2024-01-02T18:00:00Z\n"
    p_dup = os.path.join(FIXTURES_DIR, "invalid-duplicate-keys.zip")
    with zipfile.ZipFile(p_dup, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", dup_prices_csv)
        z.writestr("actions.csv", actions_csv)
    print(f"Generated {p_dup}")

    # 5. invalid-bad-actions.zip: payment date before effective date
    bad_actions_csv = actions_csv.replace("2024-01-10,2024-01-10T12:00:00Z", "2024-01-01,2024-01-01T12:00:00Z")
    p_act = os.path.join(FIXTURES_DIR, "invalid-bad-actions.zip")
    with zipfile.ZipFile(p_act, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", prices_csv)
        z.writestr("actions.csv", bad_actions_csv)
    print(f"Generated {p_act}")

    # 6. invalid-traversal.zip: path traversal entry
    p_trav = os.path.join(FIXTURES_DIR, "invalid-traversal.zip")
    with zipfile.ZipFile(p_trav, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("instruments.csv", instruments_csv)
        z.writestr("sessions.csv", sessions_csv)
        z.writestr("prices.csv", prices_csv)
        z.writestr("actions.csv", actions_csv)
        z.writestr("../traversal.txt", "malicious payload")
    print(f"Generated {p_trav}")

if __name__ == "__main__":
    os.makedirs(FIXTURES_DIR, exist_ok=True)
    create_valid_bundle()

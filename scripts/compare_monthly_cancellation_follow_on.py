"""Score the frozen 2026 monthly cancellation comparison on paired historical rows."""

import argparse
import hashlib
import json
import sys
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

from build_split_aware_price_returns import STOCKS
from cancellation_event_features import load_events
from compare_monthly_cancellation_features import evaluate_arm, with_inputs
from compare_monthly_return_features import FEATURE_NAMES, build_rows
from compare_price_baselines import load_archive
from compare_ten_year_split_safe_baselines import load_checked_days


def checked_2026_days(folder):
    start, end = date(2026, 1, 1), date(2026, 10, 1)
    archived = {path.name.split("-")[1] for path in folder.glob("stk_bydd_trd-2026*.json")
                if not path.name.endswith(".metadata.json")}
    cursor = start
    while cursor <= end:
        if cursor.strftime("%Y%m%d") not in archived:
            raise ValueError(f"Missing archived calendar date: {cursor}")
        cursor += timedelta(days=1)
    selected = load_archive(folder, start.isoformat(), end.isoformat(), STOCKS)
    if not selected or selected[-1][0] != end.isoformat():
        raise ValueError("Missing first post-September observation")
    days = [(day, raw) for day, raw, _ in selected]
    audit_digest = hashlib.sha256(json.dumps([(day, digest) for day, _, digest in selected],
                                           separators=(",", ":")).encode()).hexdigest()
    return days, audit_digest


def restrict_test_origins(rows, allowed_origins):
    """Exclude prior-December origins whose decision happens in January."""
    return [row for row in rows if not (row["decision_date"].startswith("2026-")
                                        and row["origin_month"] not in allowed_origins)]


def run(protocol_path, registry_path, archive, panel, panel_audit, splits, output):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "model-deps"))
    protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
    if (protocol["policy"] != "EXPLORATORY_MONTHLY_CANCELLATION_FOLLOW_ON_2026_V1"
            or protocol["status"] != "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED"
            or protocol["testOriginMonths"] != [f"2026-{month:02d}" for month in range(1, 9)]
            or protocol["testTargetMonths"] != [f"2026-{month:02d}" for month in range(2, 10)]):
        raise ValueError("Unexpected follow-on protocol")
    historical, prior_audit = load_checked_days(archive, panel, panel_audit, splits)
    current, source_digest = checked_2026_days(archive)
    if historical[-1][0] >= current[0][0]:
        raise ValueError("Historical and current price periods overlap")
    days = historical + current
    events = load_events(registry_path)
    if len(events) != 32:
        raise ValueError("2026 reviewed announcement count changed")
    rows, held = build_rows(days, events, cancellation_lookback_days=120)
    # A December 2025 origin is decided on the first January 2026 observation.
    # The frozen comparison starts with January 2026 origins, not January decisions.
    rows = restrict_test_origins(rows, protocol["testOriginMonths"])
    if not all(len(row["features"]) == len(FEATURE_NAMES) + len(STOCKS) + 3 for row in rows):
        raise ValueError("Unexpected monthly feature width")
    price = evaluate_arm(with_inputs(rows, False), 2026, protocol["candidateOrder"], protocol)
    combined = evaluate_arm(with_inputs(rows, True), 2026, protocol["candidateOrder"], protocol)
    sample = combined["testRows"]
    if ([(row["stock"], row["origin_month"]) for row in price["testRows"]]
            != [(row["stock"], row["origin_month"]) for row in sample]
            or not sample or any(row["origin_month"] not in protocol["testOriginMonths"]
                                  or row["target_month"] not in protocol["testTargetMonths"]
                                  for row in sample)):
        raise ValueError(f"Paired cohort or full-target-month check failed: "
                         f"{sorted({(row['origin_month'], row['target_month']) for row in sample})}")
    paired = [{"stockCode": row["stock"], "originMonth": row["origin_month"],
               "targetMonth": row["target_month"], "actualPct": row["target_pct"],
               "announcementCount": row["features"][-3],
               "priceOnlyPredictedPct": p, "priceAndDisclosurePredictedPct": c}
              for row, p, c in zip(sample, price["selectedPredictions"],
                                   combined["selectedPredictions"], strict=True)]
    report = {
        "status": protocol["status"], "policy": protocol["policy"],
        "protocolSha256": hashlib.sha256(protocol_path.read_bytes()).hexdigest(),
        "registrySha256": hashlib.sha256(registry_path.read_bytes()).hexdigest(),
        "historicalPanelSha256": prior_audit["panelSha256"],
        "selected2026DailyHashesSha256": source_digest,
        "sameRowSample": True, "eligibleStockMonths": len(rows), "held": held,
        "reviewedAnnouncements": len(events), "observed2026PriceDates": len(current),
        "testRows": len(sample), "testRowsByStock": dict(Counter(row["stock"] for row in sample)),
        "testRowsByOriginMonth": dict(Counter(row["origin_month"] for row in sample)),
        "testRowsWithKnownAnnouncement": sum(row["features"][-3] > 0 for row in sample),
        "validationYear": protocol["validationYear"],
        "priceOnly": {key: value for key, value in price.items()
                      if key not in ("selectedPredictions", "testRows")},
        "priceAndDisclosure": {key: value for key, value in combined.items()
                               if key not in ("selectedPredictions", "testRows")},
        "selectedMaeImprovementPctPoints": (price["selectedTestMaePctPoints"]
                                              - combined["selectedTestMaePctPoints"]),
        "pairedMonthly": paired,
        "forecastReady": False, "approvedForDataset": False,
        "limitations": protocol["limitations"],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                      encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(".reports/model-experiments-20261023")
    parser.add_argument("--protocol", type=Path, default=Path("docs/monthly-cancellation-follow-on-protocol-2026-10-23.json"))
    parser.add_argument("--registry", type=Path, default=root / "cancellation-reviewed-registry-through-2026.json")
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--panel", type=Path, default=Path(".reports/price-expansion/split-aware-returns-2016-2025.jsonl"))
    parser.add_argument("--panel-audit", type=Path, default=Path(".reports/price-expansion/split-aware-returns-audit.json"))
    parser.add_argument("--splits", type=Path, default=Path("docs/verified-stock-splits-2018.json"))
    parser.add_argument("--output", type=Path, default=root / "monthly-cancellation-follow-on-2026.json")
    a = parser.parse_args()
    result = run(a.protocol, a.registry, a.archive, a.panel, a.panel_audit, a.splits, a.output)
    print(json.dumps({"testRows": result["testRows"],
                      "priceOnlyMae": result["priceOnly"]["selectedTestMaePctPoints"],
                      "priceAndDisclosureMae": result["priceAndDisclosure"]["selectedTestMaePctPoints"],
                      "improvement": result["selectedMaeImprovementPctPoints"]}, indent=2))

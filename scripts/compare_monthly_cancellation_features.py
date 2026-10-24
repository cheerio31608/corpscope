"""Paired historical price-only vs price-plus-disclosure monthly experiment."""

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

from build_split_aware_price_returns import STOCKS
from cancellation_event_features import load_events
from compare_monthly_return_features import (FEATURE_NAMES, build_rows,
                                             candidate_predictions, training_window)
from compare_price_baselines import metric
from compare_rolling_price_candidates import choose_by_validation
from compare_ten_year_split_safe_baselines import chronological_fold, load_checked_days


def with_inputs(rows, event_inputs):
    width = len(FEATURE_NAMES) + len(STOCKS)
    return [{**row, "features": row["features"] if event_inputs else row["features"][:width]}
            for row in rows]


def evaluate_arm(rows, year, names, protocol):
    train, validation, test = chronological_fold(rows, year)
    validation_scores = {}
    for name in names:
        window = "trailing_36" if name.endswith("trailing_36") else "expanding"
        fit = training_window(train, f"{year - 1}-01-01", window)
        validation_scores[name] = metric(
            validation, candidate_predictions(name, fit, validation, protocol))["mae_pct_points"]
    selected = choose_by_validation(validation_scores)
    test_scores = {}
    predictions = {}
    for name in names:
        window = "trailing_36" if name.endswith("trailing_36") else "expanding"
        fit = training_window(rows, f"{year}-01-01", window)
        pred = candidate_predictions(name, fit, test, protocol)
        predictions[name] = pred
        test_scores[name] = metric(test, pred)["mae_pct_points"]
    return {"selectedFromValidation": selected, "validationMaePctPoints": validation_scores,
            "testMaePctPoints": test_scores, "selectedTestMaePctPoints": test_scores[selected],
            "selectedPredictions": predictions[selected], "testRows": test}


def run(protocol_path, registry_path, archive, panel, panel_audit, splits, output):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "model-deps"))
    protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
    if (protocol["status"] != "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED"
            or protocol["testYears"] != [2022, 2023, 2024, 2025]):
        raise ValueError("Unexpected monthly protocol")
    events = load_events(registry_path)
    days, audit = load_checked_days(archive, panel, panel_audit, splits)
    rows, held = build_rows(days, events, cancellation_lookback_days=120)
    if not all(len(row["features"]) == len(FEATURE_NAMES) + len(STOCKS) + 3 for row in rows):
        raise ValueError("Unexpected feature shape")
    names = protocol["candidateOrder"]
    report = {"status": protocol["status"], "policy": "PAIRED_MONTHLY_CANCELLATION_DISCLOSURE_V1",
              "protocolSha256": hashlib.sha256(protocol_path.read_bytes()).hexdigest(),
              "registrySha256": hashlib.sha256(registry_path.read_bytes()).hexdigest(),
              "sourcePanelSha256": audit["panelSha256"], "sameRowSample": True,
              "eligibleStockMonths": len(rows), "held": held,
              "reviewedAnnouncements": len(events),
              "matchedShareChanges": len({(event["stockCode"], event["observedListingDate"])
                                          for event in events}),
              "eventLookbackDays": 120, "forecastReady": False,
              "approvedForDataset": False, "folds": {}}
    all_actual = []
    all_predictions = {"price": [], "priceAndDisclosure": []}
    all_test_rows = []
    for year in protocol["testYears"]:
        price = evaluate_arm(with_inputs(rows, False), year, names, protocol)
        combined = evaluate_arm(with_inputs(rows, True), year, names, protocol)
        test = combined["testRows"]
        if [(row["stock"], row["origin_month"]) for row in test] != [
                (row["stock"], row["origin_month"]) for row in price["testRows"]]:
            raise ValueError("Paired cohort mismatch")
        positive = sum(row["features"][-3] > 0 for row in test)
        by_stock = dict(Counter(row["stock"] for row in test))
        report["folds"][str(year)] = {
            "testRows": len(test), "testRowsByStock": by_stock,
            "testRowsWithKnownAnnouncement": positive,
            "validationRows": len(chronological_fold(rows, year)[1]),
            "priceOnly": {key: value for key, value in price.items()
                          if key not in ("selectedPredictions", "testRows")},
            "priceAndDisclosure": {key: value for key, value in combined.items()
                                   if key not in ("selectedPredictions", "testRows")},
            "pairedMonthly": [
                {"stockCode": row["stock"], "originMonth": row["origin_month"],
                 "targetMonth": row["target_month"], "actualPct": row["target_pct"],
                 "announcementCount": row["features"][-3],
                 "priceOnlyPredictedPct": p, "priceAndDisclosurePredictedPct": c}
                for row, p, c in zip(test, price["selectedPredictions"],
                                     combined["selectedPredictions"], strict=True)],
        }
        all_actual.extend(row["target_pct"] for row in test)
        all_test_rows.extend(test)
        all_predictions["price"].extend(price["selectedPredictions"])
        all_predictions["priceAndDisclosure"].extend(combined["selectedPredictions"])
    def mae(predictions):
        return sum(abs(actual - predicted) for actual, predicted in zip(all_actual, predictions,
                                                                         strict=True)) / len(all_actual)
    report["pooledSelectedMaePctPoints"] = {name: mae(predictions)
                                            for name, predictions in all_predictions.items()}
    report["maeImprovementPctPoints"] = (report["pooledSelectedMaePctPoints"]["price"]
                                         - report["pooledSelectedMaePctPoints"]["priceAndDisclosure"])
    report["pooledTestRows"] = len(all_test_rows)
    report["pooledKnownAnnouncementRows"] = sum(row["features"][-3] > 0
                                                for row in all_test_rows)
    report["pooledRecentZeroMaePctPoints"] = sum(abs(value) for value in all_actual) / len(all_actual)
    report["pairedSubgroups"] = {}
    for name, predicate in (("knownAnnouncement", lambda row: row["features"][-3] > 0),
                            ("noReviewedAnnouncement", lambda row: row["features"][-3] == 0)):
        indices = [index for index, row in enumerate(all_test_rows) if predicate(row)]
        report["pairedSubgroups"][name] = {
            "rows": len(indices),
            "priceMaePctPoints": sum(abs(all_actual[i] - all_predictions["price"][i])
                                      for i in indices) / len(indices),
            "priceAndDisclosureMaePctPoints":
                sum(abs(all_actual[i] - all_predictions["priceAndDisclosure"][i])
                    for i in indices) / len(indices)}
    report["limitations"] = [
        "All historical target years were inspected before this extension; exploratory, not fresh holdout.",
        "OpenDART receipt dates are treated as usable next KST date; exact publication times are unavailable.",
        "The reviewed registry matches 26 historical listed-share decreases; other corporate actions and dividends remain incompletely reviewed.",
        "No causality claim; eight stocks and a small number of cancellation-positive months.",
        "Raw price changes exclude dividends and other corporate-action adjustment."]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                      encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", type=Path, default=Path("docs/monthly-return-features-protocol-2026-10-23.json"))
    parser.add_argument("--registry", type=Path,
                        default=Path(".reports/model-experiments-20261023/cancellation-reviewed-registry.json"))
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--panel", type=Path, default=Path(".reports/price-expansion/split-aware-returns-2016-2025.jsonl"))
    parser.add_argument("--panel-audit", type=Path, default=Path(".reports/price-expansion/split-aware-returns-audit.json"))
    parser.add_argument("--splits", type=Path, default=Path("docs/verified-stock-splits-2018.json"))
    parser.add_argument("--output", type=Path,
                        default=Path(".reports/model-experiments-20261023/monthly-cancellation-paired.json"))
    a = parser.parse_args()
    result = run(a.protocol, a.registry, a.archive, a.panel, a.panel_audit, a.splits, a.output)
    print(json.dumps({"rows": result["eligibleStockMonths"],
                      "pooledSelectedMaePctPoints": result["pooledSelectedMaePctPoints"],
                      "maeImprovementPctPoints": result["maeImprovementPctPoints"]},
                     ensure_ascii=False, indent=2))

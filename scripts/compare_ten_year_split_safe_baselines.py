"""Fixed exploratory 63-observed-date baseline comparison on 2016-2025 KRX prices.

Two verified stock splits are handled. Other listed-share count changes block
feature and label windows. This does not approve historical point-in-time data.
"""

import argparse
import hashlib
import json
import math
import sys
from collections import Counter
from pathlib import Path
from time import perf_counter

from build_split_aware_price_returns import STOCKS, archive_rows
from compare_price_baselines import HORIZON, metric


FEATURE_NAMES = ("return_1", "return_5", "return_20", "volatility_20", "volume_ratio_20")
TEST_YEARS = (2022, 2023, 2024, 2025)


def load_checked_days(archive, panel_path, audit_path, event_path):
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if hashlib.sha256(event_path.read_bytes()).hexdigest() != audit["eventPolicySha256"]:
        raise ValueError("Verified split event policy hash mismatch")
    if hashlib.sha256(panel_path.read_bytes()).hexdigest() != audit["panelSha256"]:
        raise ValueError("Split-aware panel hash mismatch")
    if not audit["verifiedSplitBoundaries"] or audit["forecastReady"] or audit["approvedForDataset"]:
        raise ValueError("Invalid exploratory split policy")
    panel = {}
    with panel_path.open(encoding="utf-8") as source:
        for line in source:
            item = json.loads(line)
            key = item["date"], item["stockCode"]
            if key in panel:
                raise ValueError(f"Duplicate panel row: {key}")
            panel[key] = item
    days = []
    for day, digest, raw in archive_rows(archive):
        if not raw:
            continue
        iso = f"{day[:4]}-{day[4:6]}-{day[6:]}"
        for stock in STOCKS:
            row = raw[stock]
            item = panel.pop((iso, stock))
            if (item["sourceSha256"] != digest
                    or item["rawOpenKrw"] != int(row["TDD_OPNPRC"].replace(",", ""))
                    or item["rawCloseKrw"] != int(row["TDD_CLSPRC"].replace(",", ""))):
                raise ValueError(f"Panel/raw mismatch: {stock} {iso}")
        days.append((iso, raw))
    if panel or len(days) != audit["counts"]["nonemptyMarketDates"]:
        raise ValueError("Split-aware panel coverage mismatch")
    return days, audit


def build_rows(days, verified_boundaries):
    """Return identical candidate rows for every model plus explicit hold counts."""
    verified = {(item["date"], item["stockCode"]): item["newSharesPerOldShare"]
                for item in verified_boundaries}
    dates = [item[0] for item in days]
    rows = []
    counts = Counter()
    action_rows = []
    for stock in STOCKS:
        stock_rows = [day[1][stock] for day in days]
        closes = [int(row["TDD_CLSPRC"].replace(",", "")) for row in stock_rows]
        opens = [int(row["TDD_OPNPRC"].replace(",", "")) for row in stock_rows]
        volumes = [int(row["ACC_TRDVOL"].replace(",", "")) for row in stock_rows]
        shares = [int(row["LIST_SHRS"].replace(",", "")) for row in stock_rows]
        split_factors = {i: verified[(day, stock)] for i, day in enumerate(dates)
                         if (day, stock) in verified}
        unknown_share_days = {i for i in range(1, len(days)) if shares[i] != shares[i - 1]
                              and i not in split_factors}
        action_rows.extend({"stockCode": stock, "date": dates[i],
                            "listedShareChangePct": (shares[i] / shares[i - 1] - 1) * 100}
                           for i in sorted(unknown_share_days))

        def adjusted_ratio(start, end):
            factor = math.prod(value for i, value in split_factors.items() if start < i <= end)
            return closes[end] * factor / closes[start]

        for entry in range(22, len(days) - HORIZON):
            counts["datePositionCandidates"] += 1
            last = entry - 2
            exit_index = entry + HORIZON - 1
            if (opens[entry] <= 0 or closes[exit_index] <= 0 or closes[last - 20] <= 0
                    or any(opens[j] <= 0 or closes[j] <= 0 for j in range(last - 19, last + 1))):
                counts["heldNonpositivePriceInEntryExitOrFeature"] += 1
                continue
            if any(last - 19 <= index <= last for index in split_factors):
                counts["heldSplitInsideVolumeFeatureWindow"] += 1
                continue
            if any(last - 20 <= index <= exit_index for index in unknown_share_days):
                counts["heldUnclassifiedListedShareChange"] += 1
                continue
            volume_mean = sum(volumes[last - 19:last + 1]) / 20
            if volume_mean <= 0:
                counts["heldZeroMeanVolume"] += 1
                continue
            daily = [adjusted_ratio(j - 1, j) - 1 for j in range(last - 19, last + 1)]
            mean = sum(daily) / len(daily)
            features = [adjusted_ratio(last - 1, last) - 1,
                        adjusted_ratio(last - 5, last) - 1,
                        adjusted_ratio(last - 20, last) - 1,
                        math.sqrt(sum((value - mean) ** 2 for value in daily) / len(daily)),
                        volumes[last] / volume_mean]
            factor = math.prod(value for i, value in split_factors.items() if entry < i <= exit_index)
            label = (closes[exit_index] * factor / opens[entry] - 1) * 100
            rows.append({
                "stock": stock, "decision_date": dates[entry],
                "last_feature_date": dates[last], "entry_date": dates[entry],
                "exit_date": dates[exit_index], "label_available_date": dates[exit_index + 1],
                "features": features + [float(stock == code) for code in STOCKS],
                "target_pct": label, "split_factor_in_label": factor,
                "raw_target_pct": (closes[exit_index] / opens[entry] - 1) * 100,
            })
            counts["eligibleExploratoryRows"] += 1
            if factor != 1:
                counts["labelsCrossingVerifiedSplit"] += 1
    return rows, dict(sorted(counts.items())), action_rows


def chronological_fold(rows, test_year):
    validation_year = test_year - 1
    train = [row for row in rows if row["decision_date"] < f"{validation_year}-01-01"
             and row["label_available_date"] < f"{validation_year}-01-01"]
    validation = [row for row in rows if f"{validation_year}-01-01" <= row["decision_date"] < f"{test_year}-01-01"
                  and row["label_available_date"] < f"{test_year}-01-01"]
    test = [row for row in rows if f"{test_year}-01-01" <= row["decision_date"] < f"{test_year + 1}-01-01"
            and row["label_available_date"] < f"{test_year + 1}-01-01"]
    if not all((train, validation, test)):
        raise ValueError(f"Empty chronological fold: {test_year}")
    if (max(row["label_available_date"] for row in train) >= min(row["decision_date"] for row in validation)
            or max(row["label_available_date"] for row in validation) >= min(row["decision_date"] for row in test)):
        raise ValueError(f"Label leakage at fold boundary: {test_year}")
    return train, validation, test


def run(archive, panel, panel_audit, events, output):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "model-deps"))
    import lightgbm
    import sklearn
    from lightgbm import LGBMRegressor
    from sklearn.linear_model import LinearRegression

    days, audit = load_checked_days(archive, panel, panel_audit, events)
    rows, counts, unknown_actions = build_rows(days, audit["verifiedSplitBoundaries"])
    if len(unknown_actions) != 26:
        raise ValueError("Listed-share change triage changed; review the cohort and policy")
    report = {
        "policy": "EXPLORATORY_10YEAR_SPLIT_AWARE_63_OBSERVED_DATE_PRICE_ONLY_V1",
        "forecastReady": False, "approvedForDataset": False,
        "archivePeriod": "2016-01-01..2025-12-31", "stocks": list(STOCKS),
        "panelSha256": audit["panelSha256"], "eventPolicySha256": audit["eventPolicySha256"],
        "featureNames": list(FEATURE_NAMES) + ["stock_" + stock for stock in STOCKS],
        "horizonObservedMarketDates": HORIZON,
        "counts": counts, "unclassifiedListedShareChangeDates": unknown_actions,
        "fixedModels": {"recent_value_0pct": {}, "linear_regression": {}, "lightgbm": {}},
        "versions": {"scikit_learn": sklearn.__version__, "lightgbm": lightgbm.__version__},
        "folds": {},
        "limitations": [
            "All historical target years were already inspected in earlier project work; these are not untouched holdouts.",
            "KRX historical posting times and original response versions are unavailable; t-2 price availability is an explicit assumption.",
            "Observed market dates are not independently verified trading sessions for every stock.",
            "Two splits are adjusted; 26 unclassified share-count change dates block overlapping windows, but all other corporate actions and dividends are not exhaustively reviewed.",
            "The label is price-only, excludes cash dividends, fees and slippage, and is not an executable investment return.",
            "Many 63-date labels overlap; rows are not independent observations.",
            "Retrospective exclusion of windows crossing later-observed share changes can introduce selection bias.",
        ],
    }
    for year in TEST_YEARS:
        train, validation, test = chronological_fold(rows, year)
        models = {
            "recent_value_0pct": None,
            "linear_regression": LinearRegression(),
            "lightgbm": LGBMRegressor(n_estimators=60, num_leaves=7, min_child_samples=20,
                                       learning_rate=0.05, max_depth=3, random_state=42,
                                       n_jobs=1, verbosity=-1),
        }
        fold = {"trainRows": len(train), "validationRows": len(validation), "testRows": len(test),
                "trainLastLabelAvailable": max(item["label_available_date"] for item in train),
                "validationLastLabelAvailable": max(item["label_available_date"] for item in validation),
                "testDecisionStart": min(item["decision_date"] for item in test),
                "testDecisionEnd": max(item["decision_date"] for item in test),
                "testLastLabelAvailable": max(item["label_available_date"] for item in test),
                "testDecisionDates": len({item["decision_date"] for item in test}),
                "testStocks": sorted({item["stock"] for item in test}),
                "testLabelsCrossingVerifiedSplit": sum(item["split_factor_in_label"] != 1 for item in test),
                "models": {}}
        for name, model in models.items():
            fit_started = perf_counter()
            if model is not None:
                model.fit([item["features"] for item in train], [item["target_pct"] for item in train])
            evaluated = {"fitSeconds": perf_counter() - fit_started}
            for part_name, part in (("validation", validation), ("test", test)):
                predict_started = perf_counter()
                predictions = ([0.0] * len(part) if model is None else
                               model.predict([item["features"] for item in part]).tolist())
                evaluated[part_name] = metric(part, predictions)
                evaluated[part_name]["predictSeconds"] = perf_counter() - predict_started
                evaluated[part_name]["byStock"] = {
                    stock: metric([item for item in part if item["stock"] == stock],
                                  [prediction for item, prediction in zip(part, predictions, strict=True)
                                   if item["stock"] == stock])
                    for stock in STOCKS if any(item["stock"] == stock for item in part)
                }
            fold["models"][name] = evaluated
        report["folds"][str(year)] = fold
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"counts": counts, "folds": {
        year: {"trainRows": fold["trainRows"], "validationRows": fold["validationRows"],
               "testRows": fold["testRows"], "testMAE": {
                   model: round(value["test"]["mae_pct_points"], 4)
                   for model, value in fold["models"].items()}}
        for year, fold in report["folds"].items()}}, ensure_ascii=False, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--panel", type=Path, default=Path(".reports/price-expansion/split-aware-returns-2016-2025.jsonl"))
    parser.add_argument("--panel-audit", type=Path, default=Path(".reports/price-expansion/split-aware-returns-audit.json"))
    parser.add_argument("--events", type=Path, default=Path("docs/verified-stock-splits-2018.json"))
    parser.add_argument("--output", type=Path, default=Path(".reports/model-baselines-20261022/ten-year-split-aware.json"))
    arguments = parser.parse_args()
    run(arguments.archive, arguments.panel, arguments.panel_audit, arguments.events, arguments.output)

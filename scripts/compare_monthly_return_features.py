"""Compare next-month mean-price returns using only earlier monthly price features."""

import argparse
import hashlib
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

from build_split_aware_price_returns import STOCKS
from cancellation_event_features import asof_features, verify_listing_changes
from compare_price_baselines import metric
from compare_rolling_price_candidates import choose_by_validation, make_model
from compare_ten_year_split_safe_baselines import chronological_fold, load_checked_days
from forecast_month_end_retrospective import month_plus


FEATURE_NAMES = ("monthly_return_1", "monthly_return_3", "monthly_return_6",
                 "last_close_over_month_mean", "daily_volatility_20",
                 "monthly_volume_over_prior_3_mean")


def build_rows(days, cancellation_events=None, cancellation_lookback_days=60):
    """The feature cutoff is the origin month; the target is read only for the label."""
    dates = [day[0] for day in days]
    grouped = defaultdict(list)
    for index, date in enumerate(dates):
        grouped[date[:7]].append(index)
    months = sorted(grouped)
    held = Counter()
    rows = []
    reviewed_changes = verify_listing_changes(days, cancellation_events) if cancellation_events else set()
    for stock in STOCKS:
        closes = [int(day[1][stock]["TDD_CLSPRC"].replace(",", "")) for day in days]
        volumes = [int(day[1][stock]["ACC_TRDVOL"].replace(",", "")) for day in days]
        shares = [int(day[1][stock]["LIST_SHRS"].replace(",", "")) for day in days]
        share_changes = {index for index in range(1, len(days)) if shares[index] != shares[index - 1]}
        means = [sum(closes[index] for index in grouped[month]) / len(grouped[month]) for month in months]
        monthly_volumes = [sum(volumes[index] for index in grouped[month]) for month in months]
        for position in range(6, len(months) - 1):
            origin_month = months[position]
            target_month = month_plus(origin_month, 1)
            if months[position + 1] != target_month:
                held["missingTargetMonth"] += 1
                continue
            origin = grouped[origin_month][-1]
            target = grouped[target_month][-1]
            if target + 1 >= len(days):
                held["noNextObservedDateAfterTarget"] += 1
                continue
            start = grouped[months[position - 6]][0]
            if any(closes[index] <= 0 for index in range(start, target + 1)):
                held["nonpositivePrice"] += 1
                continue
            if any(start <= index <= target and (stock, dates[index]) not in reviewed_changes
                   for index in share_changes):
                held["listedShareChange"] += 1
                continue
            if origin < 20 or monthly_volumes[position] <= 0:
                held["insufficientVolumeHistory"] += 1
                continue
            prior_volume = sum(monthly_volumes[position - 3:position]) / 3
            if prior_volume <= 0:
                held["insufficientVolumeHistory"] += 1
                continue
            daily_returns = [closes[index] / closes[index - 1] - 1
                             for index in range(origin - 19, origin + 1)]
            daily_mean = sum(daily_returns) / len(daily_returns)
            volatility = math.sqrt(sum((value - daily_mean) ** 2 for value in daily_returns)
                                   / len(daily_returns))
            features = [means[position] / means[position - lag] - 1 for lag in (1, 3, 6)]
            features += [closes[origin] / means[position] - 1, volatility,
                         monthly_volumes[position] / prior_volume]
            features += [float(stock == code) for code in STOCKS]
            if cancellation_events is not None:
                features += asof_features(stock, dates[origin + 1], shares[origin],
                                          cancellation_events, cancellation_lookback_days)
            rows.append({
                "stock": stock, "origin_month": origin_month, "target_month": target_month,
                "origin_date": dates[origin], "decision_date": dates[origin + 1],
                "target_date": dates[target], "label_available_date": dates[target + 1],
                "origin_mean_krw": means[position], "target_mean_krw": means[position + 1],
                "features": features,
                "target_pct": (means[position + 1] / means[position] - 1) * 100,
            })
    return rows, dict(sorted(held.items()))


def training_window(rows, fold_start, window):
    eligible = [row for row in rows if row["decision_date"] < fold_start
                and row["label_available_date"] < fold_start]
    if window == "trailing_36":
        first_month = month_plus(fold_start[:7], -36)
        eligible = [row for row in eligible if row["origin_month"] >= first_month]
    return eligible


def candidate_predictions(name, train, evaluation, protocol):
    if name == "recent_0pct":
        return [0.0] * len(evaluation)
    if not train or not evaluation:
        raise ValueError(f"Empty model input for {name}")
    kind = "linear" if name.startswith("linear_") else "lightgbm"
    config = {"type": kind} if kind == "linear" else {"type": kind, **protocol["lightgbm"]}
    model = make_model(config)
    model.fit([row["features"] for row in train], [row["target_pct"] for row in train])
    return model.predict([row["features"] for row in evaluation]).tolist()


def score_by_stock(rows, predictions):
    return {stock: metric([row for row in rows if row["stock"] == stock],
                          [value for row, value in zip(rows, predictions, strict=True)
                           if row["stock"] == stock])["mae_pct_points"]
            for stock in STOCKS if any(row["stock"] == stock for row in rows)}


def run(protocol_path, archive, panel, panel_audit, events, output):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "model-deps"))
    raw = protocol_path.read_bytes()
    protocol = json.loads(raw)
    names = protocol["candidateOrder"]
    if (protocol["status"] != "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED"
            or protocol["testYears"] != [2022, 2023, 2024, 2025]
            or names != ["recent_0pct", "linear_expanding", "linear_trailing_36",
                         "lightgbm_expanding", "lightgbm_trailing_36"]):
        raise ValueError("Unexpected experiment protocol")
    days, audit = load_checked_days(archive, panel, panel_audit, events)
    rows, held = build_rows(days)
    report = {"policy": protocol["policy"], "status": protocol["status"],
              "protocolSha256": hashlib.sha256(raw).hexdigest(),
              "sourcePanelSha256": audit["panelSha256"], "sourceObservedDates": len(days),
              "featureNames": list(FEATURE_NAMES) + ["stock_" + stock for stock in STOCKS],
              "eligibleStockMonths": len(rows), "held": held,
              "forecastReady": False, "approvedForDataset": False,
              "folds": {}, "limitations": protocol["limitations"]}
    for year in protocol["testYears"]:
        train, validation, test = chronological_fold(rows, year)
        validation_scores = {}
        for name in names:
            window = "trailing_36" if name.endswith("trailing_36") else "expanding"
            fit = training_window(train, f"{year - 1}-01-01", window)
            validation_scores[name] = metric(
                validation, candidate_predictions(name, fit, validation, protocol))["mae_pct_points"]
        selected = choose_by_validation(validation_scores)
        test_scores = {}
        test_predictions = {}
        fit_sizes = {}
        for name in names:
            window = "trailing_36" if name.endswith("trailing_36") else "expanding"
            fit = training_window(rows, f"{year}-01-01", window)
            fit_sizes[name] = len(fit)
            predictions = candidate_predictions(name, fit, test, protocol)
            test_predictions[name] = predictions
            test_scores[name] = metric(test, predictions)["mae_pct_points"]
        report["folds"][str(year)] = {
            "validationRows": len(validation), "testRows": len(test),
            "testTargetMonths": sorted({row["target_month"] for row in test}),
            "testDecisionStart": min(row["decision_date"] for row in test),
            "testDecisionEnd": max(row["decision_date"] for row in test),
            "selectionTrainRows": len(train), "refitRows": fit_sizes,
            "validationMaePctPoints": validation_scores,
            "selectedFromValidation": selected,
            "testMaePctPoints": test_scores,
            "selectedByStockMaePctPoints": score_by_stock(test, test_predictions[selected]),
            "baselineByStockMaePctPoints": score_by_stock(test, test_predictions["recent_0pct"]),
            "monthly": [{"stockCode": row["stock"], "originMonth": row["origin_month"],
                         "targetMonth": row["target_month"], "actualChangePct": row["target_pct"],
                         "selectedChangePct": selected_prediction,
                         "baselineChangePct": 0.0}
                        for row, selected_prediction in zip(test, test_predictions[selected], strict=True)],
        }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                      encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", type=Path, default=Path("docs/monthly-return-features-protocol-2026-10-23.json"))
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--panel", type=Path, default=Path(".reports/price-expansion/split-aware-returns-2016-2025.jsonl"))
    parser.add_argument("--panel-audit", type=Path, default=Path(".reports/price-expansion/split-aware-returns-audit.json"))
    parser.add_argument("--events", type=Path, default=Path("docs/verified-stock-splits-2018.json"))
    parser.add_argument("--output", type=Path, default=Path(".reports/model-experiments-20261023/monthly-return-features.json"))
    args = parser.parse_args()
    result = run(args.protocol, args.archive, args.panel, args.panel_audit, args.events, args.output)
    print(json.dumps({year: {"rows": fold["testRows"], "selected": fold["selectedFromValidation"],
                             "baseline": fold["testMaePctPoints"]["recent_0pct"],
                             "selectedMae": fold["testMaePctPoints"][fold["selectedFromValidation"]]}
                      for year, fold in result["folds"].items()}, ensure_ascii=False, indent=2))

"""Freeze month-end price forecasts before scoring later archived KRX prices.

This exploratory backtest never changes the approved dataset or forecastReady.
Run `predict` first. `evaluate` consumes its immutable prediction file.
"""

import argparse
import hashlib
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

from compare_price_baselines import FEATURES, features_for, load_archive


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "docs/month-end-retrospective-protocol-2026-10-22.json"
DEFAULT_ARCHIVE = ROOT / ".reports/price-expansion/archive"
DEFAULT_OUTPUT = ROOT / ".reports/month-end-retrospective-20261022"
START = "2023-01-01"
CUTOFF = "2026-06-30"


def month_plus(month, count):
    year, number = map(int, month.split("-"))
    offset = year * 12 + number - 1 + count
    return f"{offset // 12:04d}-{offset % 12 + 1:02d}"


def month_ends(days):
    ends = {}
    for index, (date, _, _) in enumerate(days):
        ends[date[:7]] = index
    return ends


def source_digest(days):
    return hashlib.sha256("\n".join(f"{date} {digest}" for date, _, digest in days).encode()).hexdigest()


def features_at(days, stocks, origin_index):
    if origin_index < 20:
        raise ValueError("Insufficient price history for the origin")
    result = {}
    for stock in stocks:
        closes = [int(day[1][stock]["TDD_CLSPRC"]) for day in days[:origin_index + 1]]
        volumes = [int(day[1][stock]["ACC_TRDVOL"]) for day in days[:origin_index + 1]]
        values = features_for(closes, volumes, origin_index + 2)
        if values is None or any(not math.isfinite(value) for value in values):
            raise ValueError(f"Invalid price feature: {stock}, {days[origin_index][0]}")
        result[stock] = values + [float(stock == code) for code in stocks]
    return result


def training_rows(days, stocks, horizon):
    """Only fully matured labels in the supplied as-of archive can become training rows."""
    ends = month_ends(days)
    rows = []
    for origin_month, origin_index in sorted(ends.items()):
        target_month = month_plus(origin_month, horizon)
        if target_month not in ends or origin_index < 20:
            continue
        target_index = ends[target_month]
        values = features_at(days, stocks, origin_index)
        for stock in stocks:
            origin_close = int(days[origin_index][1][stock]["TDD_CLSPRC"])
            target_close = int(days[target_index][1][stock]["TDD_CLSPRC"])
            rows.append({
                "stock": stock,
                "originDate": days[origin_index][0],
                "targetDate": days[target_index][0],
                "features": values[stock],
                "targetPct": (target_close / origin_close - 1) * 100,
            })
    return rows


def protocol_bytes():
    raw = PROTOCOL.read_bytes()
    protocol = json.loads(raw)
    if protocol["featureCutoff"] != CUTOFF or protocol["targetMonths"] != ["2026-07", "2026-08", "2026-09"]:
        raise ValueError("Unexpected protocol period")
    if protocol["forecastReady"] or protocol["status"] != "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED":
        raise ValueError("Unexpected approval state")
    return protocol, hashlib.sha256(raw).hexdigest()


def asof_days(archive, stocks):
    # Deliberately pass only the cutoff to the archive loader. Prediction never opens later files.
    days = load_archive(archive, START, CUTOFF, stocks)
    if not days or days[-1][0] != CUTOFF:
        raise ValueError("The June 30 origin close is missing")
    return days


def predict(archive, output):
    protocol, protocol_hash = protocol_bytes()
    stocks = tuple(protocol["stocks"])
    days = asof_days(archive, stocks)
    origin_index = len(days) - 1
    latest_features = features_at(days, stocks, origin_index)

    sys.path.insert(0, str(ROOT / ".tools/model-deps"))
    import lightgbm
    import sklearn
    from lightgbm import LGBMRegressor
    from sklearn.linear_model import LinearRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler

    forecasts = []
    training = {}
    for horizon, target_month in enumerate(protocol["targetMonths"], start=1):
        rows = training_rows(days, stocks, horizon)
        if not rows or any(row["targetDate"] > CUTOFF for row in rows):
            raise ValueError("Training label is unavailable at the cutoff")
        x = [row["features"] for row in rows]
        y = [row["targetPct"] for row in rows]
        linear = make_pipeline(StandardScaler(), LinearRegression()).fit(x, y)
        tree = LGBMRegressor(**{key: value for key, value in protocol["models"]["lightgbm"].items()}).fit(x, y)
        inputs = [latest_features[stock] for stock in stocks]
        linear_predictions = linear.predict(inputs)
        tree_predictions = tree.predict(inputs)
        training[target_month] = {
            "rows": len(rows),
            "originMonths": len({row["originDate"][:7] for row in rows}),
            "earliestOrigin": min(row["originDate"] for row in rows),
            "lastLabelDate": max(row["targetDate"] for row in rows),
        }
        for index, stock in enumerate(stocks):
            forecasts.append({
                "stock": stock,
                "originDate": CUTOFF,
                "originCloseKrw": int(days[-1][1][stock]["TDD_CLSPRC"]),
                "targetMonth": target_month,
                "horizonCalendarMonths": horizon,
                "predictedReturnPct": {
                    "recent_value_0pct": 0.0,
                    "linear_regression": float(linear_predictions[index]),
                    "lightgbm": float(tree_predictions[index]),
                },
            })

    result = {
        "policy": protocol["policy"],
        "status": protocol["status"],
        "forecastReady": False,
        "protocolSha256": protocol_hash,
        "archiveThrough": CUTOFF,
        "asofArchiveManifestSha256": source_digest(days),
        "observedSourceDates": len(days),
        "versions": {"scikitLearn": sklearn.__version__, "lightgbm": lightgbm.__version__},
        "featureNames": list(FEATURES) + ["stock_" + code for code in stocks],
        "training": training,
        "forecasts": forecasts,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(f"Frozen predictions already exist: {output}")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"Frozen {len(forecasts)} predictions; source dates {len(days)} through {CUTOFF}; file {output}")


def metric(rows, model):
    errors = [row["predictedReturnPct"][model] - row["actualReturnPct"] for row in rows]
    directions = [(row["predictedReturnPct"][model] > 0) == (row["actualReturnPct"] > 0)
                  for row in rows if row["predictedReturnPct"][model] != 0 and row["actualReturnPct"] != 0]
    return {
        "rows": len(rows),
        "maePctPoints": sum(abs(error) for error in errors) / len(errors),
        "rmsePctPoints": math.sqrt(sum(error * error for error in errors) / len(errors)),
        "directionAccuracyExcludingFlat": sum(directions) / len(directions) if directions else None,
        "directionEvaluatedRows": len(directions),
    }


def evaluate(archive, prediction_file, output):
    protocol, protocol_hash = protocol_bytes()
    raw_predictions = prediction_file.read_bytes()
    frozen = json.loads(raw_predictions)
    stocks = tuple(protocol["stocks"])
    if frozen["protocolSha256"] != protocol_hash or frozen["archiveThrough"] != CUTOFF or frozen["forecastReady"]:
        raise ValueError("Prediction protocol differs from frozen specification")
    earlier = asof_days(archive, stocks)
    if source_digest(earlier) != frozen["asofArchiveManifestSha256"]:
        raise ValueError("As-of archive changed since prediction")
    days = load_archive(archive, START, "2026-09-30", stocks)
    ends = month_ends(days)
    if any(month not in ends for month in protocol["targetMonths"]):
        raise ValueError("A target month has no observed price")
    forecast_rows = frozen["forecasts"]
    expected = {(stock, month) for stock in stocks for month in protocol["targetMonths"]}
    if len(forecast_rows) != len(expected) or {(row["stock"], row["targetMonth"]) for row in forecast_rows} != expected:
        raise ValueError("Prediction cohort differs from protocol")
    scored = []
    for row in forecast_rows:
        stock, month = row["stock"], row["targetMonth"]
        if row["originDate"] != CUTOFF or row["originCloseKrw"] != int(earlier[-1][1][stock]["TDD_CLSPRC"]):
            raise ValueError("Prediction origin changed")
        target_index = ends[month]
        target_close = int(days[target_index][1][stock]["TDD_CLSPRC"])
        scored.append({**row, "targetDate": days[target_index][0], "targetCloseKrw": target_close,
                       "actualReturnPct": (target_close / row["originCloseKrw"] - 1) * 100})
    by_month = defaultdict(list)
    for row in scored:
        by_month[row["targetMonth"]].append(row)
    model_names = tuple(protocol["models"])
    result = {
        "policy": protocol["policy"], "status": protocol["status"], "forecastReady": False,
        "protocolSha256": protocol_hash,
        "frozenPredictionSha256": hashlib.sha256(raw_predictions).hexdigest(),
        "observedSourceDatesThroughTarget": len(days),
        "targetMonths": {month: {"targetDate": days[ends[month]][0],
                                  "models": {name: metric(rows, name) for name in model_names}}
                         for month, rows in sorted(by_month.items())},
        "overall": {name: metric(scored, name) for name in model_names},
        "scored": scored,
        "limitations": [
            "Historical period selected after some later prices and related backtests had been inspected; this is not an untouched prospective test.",
            "Only three correlated target dates and ten selected companies; 30 company-month outcomes are not 30 independent market periods.",
            "Official unadjusted price change excludes dividends, fees, slippage and full corporate-action adjustment.",
            "KRX customary next-business-day posting time is an assumption; exact historical posting and response versions are unavailable.",
            "Observed archive month-end dates are not an independently certified trading calendar; financial features are excluded.",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(f"Evaluation already exists: {output}")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    for name, values in result["overall"].items():
        print(f"{name}: overall MAE {values['maePctPoints']:.3f} percentage points, RMSE {values['rmsePctPoints']:.3f}")
    print(f"Scored {len(scored)} company-month outcomes; file {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("predict", "evaluate"))
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    prediction_path = args.output_dir / "predictions.json"
    if args.action == "predict":
        predict(args.archive, prediction_path)
    else:
        evaluate(args.archive, prediction_path, args.output_dir / "evaluation.json")

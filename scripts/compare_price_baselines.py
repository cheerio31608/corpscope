"""Retrospective, price-only 63-session baseline experiment.

This never updates CorpScope's approved dataset or calls an external API.
The historical KRX availability rule is a documented backtest assumption.
"""

import argparse
import hashlib
import json
import math
import sys
from datetime import datetime
from pathlib import Path
from time import perf_counter


STOCKS = ("000660", "005930", "015760", "035420")
FEATURES = ("return_1", "return_5", "return_20", "volatility_20", "volume_ratio_20")
HORIZON = 63
LOOKBACK = 20
SPLITS = {
    "2025_holdout": {
        "train_end": "2024-08-30", "validation_start": "2024-12-02",
        "validation_end": "2025-03-14", "test_start": "2025-07-01", "test_end": "2025-09-25",
    },
    "2026_holdout": {
        "train_end": "2024-08-30", "validation_start": "2025-01-02",
        "validation_end": "2025-09-25", "test_start": "2026-01-02", "test_end": "2026-07-15",
    },
    "2025_q4_holdout": {
        "train_end": "2024-08-30", "validation_start": "2025-01-02",
        "validation_end": "2025-06-30", "test_start": "2025-10-01", "test_end": "2025-12-30",
    },
}


def load_archive(folder, start, through, stocks=STOCKS, skip_nonpositive=False):
    versions = {}
    for path in sorted(folder.glob("stk_bydd_trd-*.json")):
        if path.name.endswith(".metadata.json"):
            continue
        date = path.name.split("-")[1]
        if not (start.replace("-", "") <= date <= through.replace("-", "")):
            continue
        expected_hash = path.name.split("-")[2].removesuffix(".json")
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != expected_hash:
            raise ValueError(f"Archive hash mismatch: {path.name}")
        metadata_path = path.with_name(path.name + ".metadata.json")
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        if metadata["source"] != "KRX" or metadata["requestedDate"] != date or metadata["sha256"] != expected_hash:
            raise ValueError(f"Archive metadata mismatch: {path.name}")
        raw = json.loads(payload)
        if metadata["rows"] != len(raw["OutBlock_1"]):
            raise ValueError(f"Archive row count mismatch: {path.name}")
        versions.setdefault(date, {})[expected_hash] = (raw["OutBlock_1"], metadata)
    observations = {}
    for path in folder.glob("observation-stk_bydd_trd-*.json"):
        item = json.loads(path.read_text(encoding="utf-8"))
        date = item["requestedDate"]
        if not (start.replace("-", "") <= date <= through.replace("-", "")):
            continue
        digest = item["sha256"]
        if (item["source"] != "KRX" or item["api"] != "stk_bydd_trd"
                or item["file"] != f"stk_bydd_trd-{date}-{digest}.json"
                or digest not in versions.get(date, {})
                or item["rows"] != len(versions[date][digest][0])):
            raise ValueError(f"Archive observation mismatch: {path.name}")
        observations.setdefault(date, []).append((datetime.fromisoformat(item["observedAt"]), digest))
    days = []
    for date, candidates in sorted(versions.items()):
        history = observations.get(date)
        if history:
            history.sort()
            if len(history) > 1 and history[-1][0] == history[-2][0] and history[-1][1] != history[-2][1]:
                raise ValueError(f"Ambiguous latest observation: {date}")
            selected = history[-1][1]
        else:
            # Older archives can predate the immutable per-request observation record.
            dated = sorted((datetime.fromisoformat(meta["fetchedAt"]), digest)
                           for digest, (_, meta) in candidates.items())
            if len(dated) > 1 and dated[-1][0] == dated[-2][0]:
                raise ValueError(f"Ambiguous latest metadata: {date}")
            selected = dated[-1][1]
        nonempty = {digest for digest, (raw, _) in candidates.items() if raw}
        if len(nonempty) > 1:
            raise ValueError(f"Multiple nonempty archived versions for one date: {date}")
        raw = candidates[selected][0]
        if not raw:
            continue  # A later empty response never falls back to an older price.
        rows = {row["ISU_CD"]: row for row in raw if row["ISU_CD"] in stocks}
        if set(rows) != set(stocks):
            raise ValueError(f"Incomplete requested-stock day: {date}")
        if any(int(row["TDD_OPNPRC"]) <= 0 or int(row["TDD_CLSPRC"]) <= 0 for row in rows.values()):
            if skip_nonpositive:
                continue  # A zero-open day is not a usable entry session for this entire fixed cohort.
            raise ValueError(f"Nonpositive open/close: {date}")
        days.append((datetime.strptime(date, "%Y%m%d").date().isoformat(), rows, selected))
    return days


def features_for(closes, volumes, entry_index):
    # A date-start decision uses data no newer than two observed sessions ago.
    last = entry_index - 2
    returns = [closes[j] / closes[j - 1] - 1.0 for j in range(last - 19, last + 1)]
    mean = sum(returns) / len(returns)
    variance = sum((item - mean) ** 2 for item in returns) / len(returns)
    volume_mean = sum(volumes[last - 19:last + 1]) / 20
    if volume_mean <= 0:
        return None
    return [
        closes[last] / closes[last - 1] - 1.0,
        closes[last] / closes[last - 5] - 1.0,
        closes[last] / closes[last - 20] - 1.0,
        math.sqrt(variance),
        volumes[last] / volume_mean,
    ]


def build_rows(days, stocks=STOCKS):
    dates = [day[0] for day in days]
    rows = []
    for stock in stocks:
        closes = [int(day[1][stock]["TDD_CLSPRC"]) for day in days]
        opens = [int(day[1][stock]["TDD_OPNPRC"]) for day in days]
        volumes = [int(day[1][stock]["ACC_TRDVOL"]) for day in days]
        for entry in range(LOOKBACK + 2, len(days) - HORIZON + 1):
            values = features_for(closes, volumes, entry)
            if values is None:
                continue
            exit_index = entry + HORIZON - 1
            if exit_index + 1 >= len(days):
                continue  # the exit close has no observed next-session availability date
            rows.append({
                "stock": stock,
                "decision_date": dates[entry],
                "last_feature_date": dates[entry - 2],
                "entry_date": dates[entry],
                "exit_date": dates[exit_index],
                "label_available_date": dates[exit_index + 1],
                "features": values + [float(stock == code) for code in stocks],
                "target_pct": (closes[exit_index] / opens[entry] - 1.0) * 100.0,
                "entry_open": opens[entry],
                "exit_close": closes[exit_index],
            })
    return rows


def split_rows(rows, profile="2025_holdout"):
    boundaries = SPLITS[profile]
    train = [row for row in rows if row["decision_date"] <= boundaries["train_end"] and row["label_available_date"] < boundaries["validation_start"]]
    validation = [row for row in rows if boundaries["validation_start"] <= row["decision_date"] <= boundaries["validation_end"] and row["label_available_date"] < boundaries["test_start"]]
    test = [row for row in rows if boundaries["test_start"] <= row["decision_date"] <= boundaries["test_end"]]
    if any(not part for part in (train, validation, test)):
        raise ValueError("A chronological split is empty; collect more historical responses")
    if max(row["label_available_date"] for row in train) >= min(row["decision_date"] for row in validation):
        raise ValueError("Training labels overlap validation")
    if max(row["label_available_date"] for row in validation) >= min(row["decision_date"] for row in test):
        raise ValueError("Validation labels overlap test")
    return train, validation, test


def metric(rows, predictions):
    actual = [row["target_pct"] for row in rows]
    errors = [prediction - value for prediction, value in zip(predictions, actual, strict=True)]
    direction = [((prediction > 0) == (value > 0)) for prediction, value in zip(predictions, actual, strict=True) if prediction != 0 and value != 0]
    return {
        "rows": len(rows),
        "decision_dates": len({row["decision_date"] for row in rows}),
        "mae_pct_points": sum(abs(error) for error in errors) / len(errors),
        "rmse_pct_points": math.sqrt(sum(error * error for error in errors) / len(errors)),
        "direction_accuracy_excluding_flat": sum(direction) / len(direction) if direction else None,
        "direction_evaluated_rows": len(direction),
    }


def run(archive, output, profile, start, stocks=STOCKS):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "model-deps"))
    import lightgbm
    import sklearn
    from lightgbm import LGBMRegressor
    from sklearn.linear_model import LinearRegression

    required_end = "2026-10-19" if profile == "2026_holdout" else (
        "2026-04-06" if profile == "2025_q4_holdout" else "2025-12-30")
    days = load_archive(archive, start, required_end, stocks)
    if days[0][0] > start[:7] + "-31" or days[-1][0] < required_end:
        raise ValueError(f"The {start} through {required_end} archive is incomplete")
    rows = build_rows(days, stocks)
    train, validation, test = split_rows(rows, profile)
    x_train = [row["features"] for row in train]
    y_train = [row["target_pct"] for row in train]
    models = {
        "recent_value_0pct": None,
        "linear_regression": LinearRegression(),
        "lightgbm": LGBMRegressor(n_estimators=60, num_leaves=7, min_child_samples=20, learning_rate=0.05,
                                   max_depth=3, random_state=42, n_jobs=1, verbosity=-1),
    }
    manifest_hash = hashlib.sha256("\n".join(f"{date} {digest}" for date, _, digest in days).encode()).hexdigest()
    if profile == "2025_q4_holdout":
        protocol = json.loads(Path("docs/historical-q4-evaluation-protocol-2026-10-21.json").read_text(encoding="utf-8"))
        if (protocol["sourceArchiveManifestSha256"] != manifest_hash
                or tuple(protocol["stockCodes"]) != tuple(stocks)
                or protocol["testDecisionStart"] != SPLITS[profile]["test_start"]
                or protocol["testDecisionEnd"] != SPLITS[profile]["test_end"]
                or protocol["validationDecisionEnd"] != SPLITS[profile]["validation_end"]
                or protocol["horizonObservedSessions"] != HORIZON
                or len({row["decision_date"] for row in test}) != protocol["expectedDecisionDates"]
                or max(row["label_available_date"] for row in test) != protocol["testLastLabelAvailableOn"]):
            raise ValueError("Q4 evaluation differs from its recorded protocol")
    report = {
        "policy": "RETROSPECTIVE_KRX_PRICE_ONLY_63_OBSERVED_SESSIONS_V1",
        "split_profile": profile,
        "split_boundaries": SPLITS[profile],
        "status": "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED",
        "holdout_context": ("Retrospective additional Q4 2025 window; its decision dates were unscored "
                            "in prior reports, but historical prices and overlapping 2026 outcomes were inspected."
                            if profile == "2025_q4_holdout" else None),
        "assumption": "Historical KRX day t-2 close/volume is available by 00:00 KST on entry day t under customary next-business-day update; exact historical posting/version unverified.",
        "source": "Official unadjusted KRX daily KOSPI archive, locally collected in 2026",
        "archive_start": start,
        "feature_names": list(FEATURES) + ["stock_" + code for code in stocks],
        "stock_codes": list(stocks),
        "observed_dates": len(days),
        "archive_manifest_sha256": manifest_hash,
        "candidate_rows": len(rows),
        "splits": {},
        "models": {},
        "versions": {"scikit_learn": sklearn.__version__, "lightgbm": lightgbm.__version__},
        "limitations": ["Observed-date 63-window, not independently verified execution sessions.",
                        "Price-only model: financial disclosures and point-in-time financial features are excluded.",
                        "Unadjusted price return excludes dividends, fees, slippage; corporate-action coverage is incomplete.",
                        "Overlapping 63-session labels and selected firms limit independent sample size and generalization."],
    }
    for name, part in (("train", train), ("validation", validation), ("test", test)):
        report["splits"][name] = {"rows": len(part), "decision_dates": len({row["decision_date"] for row in part}),
                                   "decision_start": min(row["decision_date"] for row in part),
                                   "decision_end": max(row["decision_date"] for row in part),
                                   "last_label_available": max(row["label_available_date"] for row in part)}
    for name, model in models.items():
        fit_started = perf_counter()
        if model is not None:
            model.fit(x_train, y_train)
        report["models"][name] = {"fit_seconds": perf_counter() - fit_started}
        for part_name, part in (("validation", validation), ("test", test)):
            predict_started = perf_counter()
            predictions = [0.0] * len(part) if model is None else model.predict([row["features"] for row in part]).tolist()
            report["models"][name][part_name] = metric(part, predictions)
            report["models"][name][part_name]["predict_seconds"] = perf_counter() - predict_started
            report["models"][name][part_name]["by_stock"] = {
                stock: metric([row for row in part if row["stock"] == stock],
                              [prediction for row, prediction in zip(part, predictions, strict=True) if row["stock"] == stock])
                for stock in stocks if any(row["stock"] == stock for row in part)
            }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name, result in report["models"].items():
        print(f"{name}: validation MAE {result['validation']['mae_pct_points']:.3f}; test MAE {result['test']['mae_pct_points']:.3f}")
    print(f"Rows: train {len(train)}, validation {len(validation)}, test {len(test)}. Report: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--output", type=Path, default=Path(".reports/model-baselines-20261020/report.json"))
    parser.add_argument("--profile", choices=tuple(SPLITS), default="2025_holdout")
    parser.add_argument("--universe", choices=("four", "ten"), default="four")
    parser.add_argument("--start", choices=("2023-01-01", "2024-01-01"), default="2023-01-01")
    args = parser.parse_args()
    if args.universe == "ten":
        from compare_financial_price_baselines import TEN_STOCKS
        stocks = TEN_STOCKS
    else:
        stocks = STOCKS
    run(args.archive, args.output, args.profile, args.start, stocks)

"""Rolling exploratory comparison; choose a configuration from past validation only."""

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

from compare_price_baselines import metric
from compare_ten_year_split_safe_baselines import build_rows, chronological_fold, load_checked_days


def choose_by_validation(scores):
    """Insertion order is the documented tie break; test scores never enter here."""
    return min(scores, key=scores.get)


def make_model(config):
    from lightgbm import LGBMRegressor
    from sklearn.linear_model import LinearRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler

    if config["type"] == "constant":
        return None
    if config["type"] == "linear":
        return make_pipeline(StandardScaler(), LinearRegression())
    if config["type"] == "lightgbm":
        return LGBMRegressor(**{key: value for key, value in config.items()
                                if key not in ("type", "featureIndices")})
    raise ValueError("Unknown candidate type")


def predict(config, train, evaluate):
    model = make_model(config)
    if model is None:
        return [config["predictionPct"]] * len(evaluate)
    indices = config.get("featureIndices")
    if indices is not None and (not indices or len(set(indices)) != len(indices)
                                or any(not isinstance(index, int) or index < 0 or index >= 13
                                       for index in indices)):
        raise ValueError("Invalid feature ablation indices")
    def feature_values(row):
        return row["features"] if indices is None else [row["features"][index] for index in indices]
    model.fit([feature_values(row) for row in train], [row["target_pct"] for row in train])
    return model.predict([feature_values(row) for row in evaluate]).tolist()


def run(protocol_path, archive, panel, panel_audit, events, output):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "model-deps"))
    protocol_bytes = protocol_path.read_bytes()
    protocol = json.loads(protocol_bytes)
    candidates = protocol["candidates"]
    if (protocol["status"] != "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED"
            or protocol["testYears"] != list(range(2019, 2026))
            or list(candidates)[0] != "recent_0pct"
            or candidates["recent_0pct"] != {"type": "constant", "predictionPct": 0}):
        raise ValueError("Unexpected rolling experiment protocol")
    days, audit = load_checked_days(archive, panel, panel_audit, events)
    rows, counts, unknown_actions = build_rows(days, audit["verifiedSplitBoundaries"])
    if len(unknown_actions) != 26 or counts["eligibleExploratoryRows"] != 17085:
        raise ValueError("Source cohort changed; review before rerunning")
    report = {
        "policy": protocol["policy"], "status": protocol["status"],
        "protocolSha256": hashlib.sha256(protocol_bytes).hexdigest(),
        "sourcePanelSha256": audit["panelSha256"],
        "forecastReady": False, "approvedForDataset": False,
        "candidateOrder": list(candidates), "folds": {},
    }
    selection_counts = Counter()
    for year in protocol["testYears"]:
        train, validation, test = chronological_fold(rows, year)
        validation_mae = {
            name: metric(validation, predict(config, train, validation))["mae_pct_points"]
            for name, config in candidates.items()
        }
        selected = choose_by_validation(validation_mae)
        # All preceding labels are known before this test year begins.
        fit_rows = [row for row in rows if row["decision_date"] < f"{year}-01-01"
                    and row["label_available_date"] < f"{year}-01-01"]
        if len(fit_rows) < len(train) + len(validation):
            raise ValueError("Unexpected rolling training coverage")
        selected_metric = metric(test, predict(candidates[selected], fit_rows, test))
        baseline_metric = metric(test, [0.0] * len(test))
        selection_counts[selected] += 1
        report["folds"][str(year)] = {
            "trainRowsForSelection": len(train), "validationRows": len(validation),
            "refitRows": len(fit_rows), "testRows": len(test),
            "testDecisionStart": min(row["decision_date"] for row in test),
            "testDecisionEnd": max(row["decision_date"] for row in test),
            "validationMaepctPoints": validation_mae, "selectedFromValidation": selected,
            "testSelected": selected_metric, "testRecent0pct": baseline_metric,
        }
    report["selectedConfigCounts"] = dict(selection_counts)
    report["limitations"] = protocol["limitations"]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", type=Path, default=Path("docs/rolling-price-candidates-protocol-2026-10-23.json"))
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--panel", type=Path, default=Path(".reports/price-expansion/split-aware-returns-2016-2025.jsonl"))
    parser.add_argument("--panel-audit", type=Path, default=Path(".reports/price-expansion/split-aware-returns-audit.json"))
    parser.add_argument("--events", type=Path, default=Path("docs/verified-stock-splits-2018.json"))
    parser.add_argument("--output", type=Path, default=Path(".reports/model-experiments-20261023/rolling-candidates.json"))
    arguments = parser.parse_args()
    result = run(arguments.protocol, arguments.archive, arguments.panel, arguments.panel_audit,
                 arguments.events, arguments.output)
    print(json.dumps({"protocolSha256": result["protocolSha256"], "folds": {
        year: {"selected": fold["selectedFromValidation"],
               "selectedTestMAE": fold["testSelected"]["mae_pct_points"],
               "recentTestMAE": fold["testRecent0pct"]["mae_pct_points"]}
        for year, fold in result["folds"].items()}}, ensure_ascii=False, indent=2))

"""Exploratory price-only versus as-of financial feature comparison on identical rows."""

import argparse
import collections
import json
import sys
from pathlib import Path

from compare_price_baselines import (FEATURES, SPLITS, STOCKS, build_rows, load_archive,
                                     metric, split_rows)


FINANCIAL = ("revenueGrowth", "operatingMargin", "debtRatio", "roa", "roe")
FIVE_STOCKS = STOCKS + ("373220",)
SIX_STOCKS = FIVE_STOCKS + ("329180",)
TEN_STOCKS = SIX_STOCKS + ("000270", "036460", "005380", "004020")


def join_rows(parts, panel):
    if panel["status"] != "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED" or panel["forecastReady"]:
        raise ValueError("Unexpected financial panel status")
    if panel["requested"] != panel["exported"] + panel["withheldCount"]:
        raise ValueError("Financial panel counts differ")
    expected = {(row["stock"], row["decision_date"]) for part in parts for row in part}
    if len(expected) != sum(map(len, parts)):
        raise ValueError("Duplicate price experiment company-date")
    by_key = {}
    supplied = set()
    for item in panel["rows"]:
        key = (item["stockCode"], item["decisionDate"])
        if key in supplied or item["availableFrom"] > item["decisionDate"]:
            raise ValueError("Duplicate or future financial evidence")
        supplied.add(key)
        by_key[key] = item
    for item in panel["withheld"]:
        key = (item["stockCode"], item["decisionDate"])
        if key in supplied:
            raise ValueError("Duplicate financial request result")
        supplied.add(key)
    if supplied != expected or panel["requested"] != len(expected):
        raise ValueError("Financial panel does not match the price experiment cohort")
    joined = []
    for part in parts:
        included = []
        for row in part:
            financial = by_key.get((row["stock"], row["decision_date"]))
            if financial is None or any(financial["metrics"].get(name) is None for name in FINANCIAL):
                continue  # Keep missing data missing; compare models only on the same complete cohort.
            copy = dict(row)
            copy["financial_features"] = [float(financial["metrics"][name]) for name in FINANCIAL]
            copy["financial_year"] = financial["fiscalYear"]
            copy["financial_receipt"] = financial["receiptNumber"]  # audit only, never a model input
            included.append(copy)
        joined.append(included)
    return joined


def compare(archive, panel_path, output, stocks=STOCKS, profile="2026_holdout"):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".tools" / "model-deps"))
    import lightgbm
    import sklearn
    from lightgbm import LGBMRegressor
    from sklearn.linear_model import LinearRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler

    through = "2026-04-06" if profile == "2025_q4_holdout" else "2026-10-19"
    days = load_archive(archive, "2023-01-01", through, stocks)
    original = split_rows(build_rows(days, stocks), profile)
    if profile == "2025_q4_holdout":
        import hashlib
        protocol = json.loads(Path("docs/historical-q4-evaluation-protocol-2026-10-21.json").read_text(encoding="utf-8"))
        manifest = hashlib.sha256("\n".join(f"{date} {digest}" for date, _, digest in days).encode()).hexdigest()
        if (manifest != protocol["sourceArchiveManifestSha256"] or tuple(stocks) != tuple(protocol["stockCodes"])
                or len({row["decision_date"] for row in original[2]}) != protocol["expectedDecisionDates"]):
            raise ValueError("Q4 financial comparison differs from its recorded protocol")
    panel = json.loads(panel_path.read_text(encoding="utf-8"))
    train, validation, test = join_rows(original, panel)
    if any(not part for part in (train, validation, test)):
        raise ValueError("Financial complete-case intersection has an empty split")
    # Keep the original chronological boundaries and label purges; filtering cannot introduce leakage.
    report = {
        "policy": "RETROSPECTIVE_KRX_PRICE_PLUS_REVIEWED_FINANCIAL_63_OBSERVED_SESSIONS_V1",
        "stock_codes": list(stocks),
        "status": "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED",
        "holdout_reuse": ("Q4 2025 target decision dates were not scored in prior reports, but historical "
                          "prices and overlapping 2026 outcomes were inspected; this is retrospective."
                          if profile == "2025_q4_holdout" else
                          "The 2026 test period was inspected in the earlier price-only run. This is an exploratory ablation, not a fresh confirmatory test."),
        "split_boundaries": SPLITS[profile],
        "price_assumption": "Prices no newer than the second observed session before decision midnight KST; historical API posting/version is assumed, not proved.",
        "financial_selection": "Latest officially filed annual fiscal year first; latest as-of version must be reviewed; no fallback to old year/version. All five metrics must be non-null.",
        "financial_feature_names": list(FINANCIAL),
        "price_feature_names": list(FEATURES) + ["stock_" + stock for stock in stocks],
        "original_rows": dict(zip(("train", "validation", "test"), map(len, original))),
        "panel_counts": {key: panel[key] for key in ("requested", "exported", "withheldCount")},
        "panel_withheld_reasons": dict(collections.Counter(item["reason"] for item in panel["withheld"])),
        "splits": {}, "models": {},
        "versions": {"scikit_learn": sklearn.__version__, "lightgbm": lightgbm.__version__},
        "limitations": [
            "The historical holdout is retrospective and does not establish a pristine future test.",
            "Complete-case selection changes the cohort; all models here use exactly that same cohort.",
            "Annual financial observations repeat across many dates and overlapping 63-session labels are dependent.",
            "Unadjusted KRX price return excludes dividends, fees and slippage; corporate-action review is incomplete.",
            "This selected company set and historical disclosure reviews do not establish generalization to all listed companies.",
            "No approvedForDataset or forecastReady status is granted by this experiment.",
        ],
    }
    for name, part in zip(("train", "validation", "test"), (train, validation, test)):
        report["splits"][name] = {
            "rows": len(part), "decision_dates": len({row["decision_date"] for row in part}),
            "decision_start": min(row["decision_date"] for row in part),
            "decision_end": max(row["decision_date"] for row in part),
            "financial_company_years": len({(row["stock"], row["financial_year"]) for row in part}),
            "by_stock": dict(collections.Counter(row["stock"] for row in part)),
        }
    def make_lightgbm():
        return LGBMRegressor(n_estimators=60, num_leaves=7, min_child_samples=20,
                             learning_rate=0.05, max_depth=3, random_state=42,
                             n_jobs=1, verbosity=-1)
    models = {
        "recent_value_0pct": (None, False),
        "price_linear_regression": (make_pipeline(StandardScaler(), LinearRegression()), False),
        "price_lightgbm": (make_lightgbm(), False),
        "price_financial_linear_regression": (make_pipeline(StandardScaler(), LinearRegression()), True),
        "price_financial_lightgbm": (make_lightgbm(), True),
    }
    for name, (model, augmented) in models.items():
        def inputs(part):
            return [row["features"] + (row["financial_features"] if augmented else []) for row in part]
        if model is not None:
            model.fit(inputs(train), [row["target_pct"] for row in train])
        result = {}
        for split_name, part in (("validation", validation), ("test", test)):
            predictions = [0.0] * len(part) if model is None else model.predict(inputs(part)).tolist()
            result[split_name] = metric(part, predictions)
            result[split_name]["by_stock"] = {
                stock: metric([row for row in part if row["stock"] == stock],
                              [pred for row, pred in zip(part, predictions, strict=True) if row["stock"] == stock])
                for stock in stocks if any(row["stock"] == stock for row in part)
            }
        report["models"][name] = result
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name, result in report["models"].items():
        print(f"{name}: validation MAE {result['validation']['mae_pct_points']:.3f}; test MAE {result['test']['mae_pct_points']:.3f}")
    print(f"Complete-case rows: train {len(train)}, validation {len(validation)}, test {len(test)}. Report: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--panel", type=Path, default=Path(".reports/model-baselines-20261020/financial-panel.json"))
    parser.add_argument("--output", type=Path, default=Path(".reports/model-baselines-20261020/financial-comparison.json"))
    parser.add_argument("--universe", choices=("four", "five", "six", "ten"), default="four")
    parser.add_argument("--profile", choices=("2026_holdout", "2025_q4_holdout"), default="2026_holdout")
    args = parser.parse_args()
    stocks = {"four": STOCKS, "five": FIVE_STOCKS, "six": SIX_STOCKS, "ten": TEN_STOCKS}[args.universe]
    compare(args.archive, args.panel, args.output, stocks, args.profile)

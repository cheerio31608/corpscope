"""Score last known close as next-month mean on frozen, identical monthly cohorts."""

import hashlib
import json
import sys
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

from build_split_aware_price_returns import STOCKS
from compare_monthly_cancellation_follow_on import checked_2026_days
from compare_ten_year_split_safe_baselines import load_checked_days
from forecast_month_end_retrospective import month_plus


D = Decimal
MODELS = ("previousMean0Pct", "originLastClose", "priceSelected", "priceAndDisclosureSelected")


def source_digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def raw_monthly(days):
    grouped = defaultdict(list)
    for day, stocks in days:
        for stock in STOCKS:
            close = D(stocks[stock]["TDD_CLSPRC"].replace(",", ""))
            grouped[(stock, day[:7])].append(close)
    return {key: {"mean": sum(values) / len(values), "last": values[-1], "observedDays": len(values)}
            for key, values in grouped.items()}


def score(rows):
    if not rows:
        raise ValueError("Empty comparison cohort")
    return {name: float(sum(abs(row["predictions"][name] - row["actualPct"]) for row in rows) / len(rows))
            for name in MODELS}


def run():
    sys.path.insert(0, str(Path(".tools/model-deps").resolve()))
    protocol_path = Path("docs/month-end-price-baseline-protocol-2026-10-23.json")
    protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
    if (protocol["policy"] != "EXPLORATORY_MONTH_END_CLOSE_LEVEL_BASELINE_V1"
            or protocol["status"] != "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED"):
        raise ValueError("Unexpected benchmark protocol")
    root = Path(".reports/model-experiments-20261023")
    older_path = root / "monthly-cancellation-paired.json"
    newer_path = root / "monthly-cancellation-follow-on-2026.json"
    older = json.loads(older_path.read_text(encoding="utf-8"))
    newer = json.loads(newer_path.read_text(encoding="utf-8"))
    archive = Path(".reports/price-expansion/archive")
    historical, audit = load_checked_days(archive,
        Path(".reports/price-expansion/split-aware-returns-2016-2025.jsonl"),
        Path(".reports/price-expansion/split-aware-returns-audit.json"),
        Path("docs/verified-stock-splits-2018.json"))
    current, digest = checked_2026_days(archive)
    if (audit["panelSha256"] != older["sourcePanelSha256"]
            or audit["panelSha256"] != newer["historicalPanelSha256"]
            or digest != newer["selected2026DailyHashesSha256"]):
        raise ValueError("Source price archive differs from frozen experiments")
    monthly = raw_monthly(historical + current)
    cohorts = {year: data["pairedMonthly"] for year, data in older["folds"].items()}
    cohorts["2026"] = newer["pairedMonthly"]
    result = {"policy": protocol["policy"], "status": protocol["status"],
              "forecastReady": False, "approvedForDataset": False,
              "protocolSha256": source_digest(protocol_path),
              "olderReportSha256": source_digest(older_path),
              "newerReportSha256": source_digest(newer_path),
              "sameFrozenRows": True, "years": {}, "limitations": protocol["limitations"]}
    all_rows = []
    for year, cohort in cohorts.items():
        expected = 56 if year == "2026" else 88
        if len(cohort) != expected or len({(r["stockCode"], r["originMonth"]) for r in cohort}) != expected:
            raise ValueError(f"Frozen cohort size/uniqueness mismatch: {year}")
        rows = []
        for item in cohort:
            stock, origin, target = item["stockCode"], item["originMonth"], item["targetMonth"]
            allowed_origins = ([f"2026-{m:02d}" for m in range(1, 9)] if year == "2026" else
                               [f"{int(year) - 1}-12"] + [f"{year}-{m:02d}" for m in range(1, 11)])
            if stock not in STOCKS or month_plus(origin, 1) != target or origin not in allowed_origins:
                raise ValueError("Cohort stock or time mismatch")
            before, after = monthly[(stock, origin)], monthly[(stock, target)]
            if before["last"] <= 0 or before["mean"] <= 0 or after["mean"] <= 0:
                raise ValueError("Nonpositive pilot price")
            actual = (after["mean"] / before["mean"] - 1) * 100
            if abs(actual - D(str(item["actualPct"]))) > D("1e-10"):
                raise ValueError("Frozen target does not match KRX monthly prices")
            prediction = (before["last"] / before["mean"] - 1) * 100
            rows.append({"stockCode": stock, "originMonth": origin, "targetMonth": target,
                         "actualPct": actual, "originLastCloseKrw": before["last"],
                         "originMeanCloseKrw": before["mean"], "targetMeanCloseKrw": after["mean"],
                         "predictions": {"previousMean0Pct": D(0), "originLastClose": prediction,
                                         "priceSelected": D(str(item["priceOnlyPredictedPct"])),
                                         "priceAndDisclosureSelected": D(str(item["priceAndDisclosurePredictedPct"]))}})
        counts = defaultdict(list)
        by_stock = defaultdict(list)
        for row in rows:
            counts[row["targetMonth"]].append(row)
            by_stock[row["stockCode"]].append(row)
        result["years"][year] = {"rows": len(rows), "maePctPoints": score(rows),
                                  "byTargetMonth": {key: {"rows": len(value), "maePctPoints": score(value)}
                                                    for key, value in sorted(counts.items())},
                                  "byStock": {key: {"rows": len(value), "maePctPoints": score(value)}
                                              for key, value in sorted(by_stock.items())},
                                  "pairedRows": rows}
        all_rows.extend(rows)
    for year in ("2022", "2023", "2024", "2025"):
        frozen = older["folds"][year]
        for name, arm in (("priceSelected", "priceOnly"),
                          ("priceAndDisclosureSelected", "priceAndDisclosure")):
            if abs(result["years"][year]["maePctPoints"][name]
                   - frozen[arm]["selectedTestMaePctPoints"]) > 1e-10:
                raise ValueError("Older selected predictions differ from frozen MAE")
    for name, arm in (("priceSelected", "priceOnly"),
                      ("priceAndDisclosureSelected", "priceAndDisclosure")):
        if abs(result["years"]["2026"]["maePctPoints"][name]
               - newer[arm]["selectedTestMaePctPoints"]) > 1e-10:
            raise ValueError("2026 selected predictions differ from frozen MAE")
    historical_rows = [row for year in ("2022", "2023", "2024", "2025")
                       for row in result["years"][year]["pairedRows"]]
    result["historicalPooled"] = {"rows": len(historical_rows), "maePctPoints": score(historical_rows)}
    result["pooled"] = {"rows": len(all_rows), "maePctPoints": score(all_rows)}
    output = root / "month-end-close-baseline.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str, allow_nan=False) + "\n",
                      encoding="utf-8")
    print(json.dumps({year: {"rows": data["rows"], "maePctPoints": data["maePctPoints"]}
                      for year, data in result["years"].items()}, indent=2))
    return result


if __name__ == "__main__":
    run()

"""Check 2016-2025 KRX archive coverage for long-listed pilot companies.

Archive coverage is a data-inventory result, not adjusted-price or model approval.
"""

import argparse
import collections
import hashlib
import json
from datetime import date
from pathlib import Path


CANDIDATES = {
    "000270": "Kia", "000660": "SK hynix", "004020": "Hyundai Steel",
    "005380": "Hyundai Motor", "005930": "Samsung Electronics",
    "015760": "KEPCO", "035420": "NAVER", "036460": "KOGAS",
    "329180": "HD Hyundai Heavy Industries", "373220": "LG Energy Solution",
}
YEARS = tuple(range(2016, 2026))


def expected_days(year):
    return (date(year + 1, 1, 1) - date(year, 1, 1)).days


def audit(folder):
    per_year = {str(year): {"archivedCalendarDays": 0, "nonemptyMarketDates": 0,
                            "marketRows": 0, "candidates": {stock: {"observedDates": 0,
                                                                     "positiveCloseDates": 0,
                                                                     "zeroOrMissingOhlcDates": 0,
                                                                     "missingOnNonemptyMarketDate": 0}
                                                        for stock in CANDIDATES}}
                for year in YEARS}
    versions = collections.defaultdict(dict)
    for path in sorted(folder.glob("stk_bydd_trd-*.json")):
        if path.name.endswith(".metadata.json"):
            continue
        day = path.name.split("-")[1]
        if not ("20160101" <= day <= "20251231"):
            continue
        digest = path.name.split("-")[2].removesuffix(".json")
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != digest:
            raise ValueError(f"KRX payload hash mismatch: {path.name}")
        meta_path = path.with_name(path.name + ".metadata.json")
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        rows = json.loads(payload)["OutBlock_1"]
        if (meta["source"] != "KRX" or meta["api"] != "stk_bydd_trd"
                or meta["requestedDate"] != day or meta["sha256"] != digest
                or meta["rows"] != len(rows)):
            raise ValueError(f"KRX metadata mismatch: {path.name}")
        if digest in versions[day]:
            raise ValueError(f"Duplicate archive digest: {day}")
        versions[day][digest] = rows

    last_positive_close = {}
    large_raw_close_jumps = []
    for day in sorted(versions):
        variants = versions[day]
        if len(variants) != 1:
            raise ValueError(f"Multiple source versions require a separate version audit: {day}")
        rows = next(iter(variants.values()))
        year_summary = per_year[day[:4]]
        year_summary["archivedCalendarDays"] += 1
        if not rows:
            continue
        year_summary["nonemptyMarketDates"] += 1
        year_summary["marketRows"] += len(rows)
        by_code = {row["ISU_CD"]: row for row in rows if row["ISU_CD"] in CANDIDATES}
        for stock, counts in year_summary["candidates"].items():
            row = by_code.get(stock)
            if row is None:
                counts["missingOnNonemptyMarketDate"] += 1
                continue
            counts["observedDates"] += 1
            if int(row["TDD_OPNPRC"]) <= 0 or int(row["TDD_CLSPRC"]) <= 0:
                counts["zeroOrMissingOhlcDates"] += 1
            else:
                counts["positiveCloseDates"] += 1
                close = int(row["TDD_CLSPRC"])
                previous = last_positive_close.get(stock)
                if previous is not None:
                    previous_day, previous_close = previous
                    change = close / previous_close - 1
                    if abs(change) >= 0.5:
                        large_raw_close_jumps.append({
                            "stock": stock, "previousDate": previous_day, "date": day,
                            "previousCloseKrw": previous_close, "closeKrw": close,
                            "rawChangePct": round(change * 100, 4),
                        })
                last_positive_close[stock] = day, close

    for year in YEARS:
        item = per_year[str(year)]
        item["expectedCalendarDays"] = expected_days(year)
        item["missingArchiveDays"] = item["expectedCalendarDays"] - item["archivedCalendarDays"]
    complete = all(item["missingArchiveDays"] == 0 for item in per_year.values())
    companies = {}
    for stock, name in CANDIDATES.items():
        years_with_prices = [year for year in YEARS
                             if per_year[str(year)]["candidates"][stock]["positiveCloseDates"] > 0]
        companies[stock] = {
            "name": name, "yearsWithPositivePrices": years_with_prices,
            "has2016Through2025RawCoverage": complete and years_with_prices == list(YEARS),
            "observedDates": sum(per_year[str(year)]["candidates"][stock]["observedDates"] for year in YEARS),
            "zeroOrMissingOhlcDates": sum(per_year[str(year)]["candidates"][stock]["zeroOrMissingOhlcDates"] for year in YEARS),
            "missingOnNonemptyMarketDate": sum(per_year[str(year)]["candidates"][stock]["missingOnNonemptyMarketDate"] for year in YEARS),
        }
    return {
        "status": "PRICE_ARCHIVE_COVERAGE_ONLY_NOT_MODEL_APPROVAL", "forecastReady": False,
        "period": "2016-01-01..2025-12-31", "archiveCalendarComplete": complete,
        "market": "KOSPI", "yearly": per_year, "companies": companies,
        "largeRawCloseJumps": large_raw_close_jumps,
        "limitations": [
            "A year with a positive price is raw data coverage, not verified continuous listing or daily completeness.",
            "An empty market response is not by itself a certified holiday.",
            "A large raw close jump is a review candidate, not automatic proof of a split or an adjustment factor.",
            "Unadjusted prices, corporate actions, dividends and historical publication versions are not approved for model training.",
        ],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--output", type=Path, default=Path(".reports/price-expansion/ten-year-coverage.json"))
    args = parser.parse_args()
    result = audit(args.archive)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Archive calendar complete: {result['archiveCalendarComplete']}")
    for year, item in result["yearly"].items():
        print(f"{year}: archived {item['archivedCalendarDays']}/{item['expectedCalendarDays']}, market dates {item['nonemptyMarketDates']}")
    for code, item in result["companies"].items():
        print(f"{code} {item['name']}: years {len(item['yearsWithPositivePrices'])}, raw coverage {item['has2016Through2025RawCoverage']}")

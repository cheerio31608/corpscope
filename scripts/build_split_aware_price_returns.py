"""Derive split-aware close returns from immutable KRX archive, without model approval.

The output is a retrospective diagnostic. Only the two documented split boundaries
are adjusted; every other positive-OHLC transition retains its raw close return.
"""

import argparse
import hashlib
import json
from collections import Counter
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

from audit_ten_year_price_history import CANDIDATES


STOCKS = tuple(code for code in CANDIDATES if code not in ("329180", "373220"))


def pct(current, previous, factor=1):
    return (Decimal(current * factor) / Decimal(previous) - 1) * 100


def decimal_string(value):
    return format(value.quantize(Decimal("0.000001")), "f")


def load_events(path):
    config = json.loads(path.read_text(encoding="utf-8"))
    events = {}
    for event in config["events"]:
        key = (event["firstPostSplitPriceDate"], event["stockCode"])
        if key in events or event["stockCode"] not in STOCKS or event["newSharesPerOldShare"] <= 1:
            raise ValueError("Invalid or duplicate verified split event")
        events[key] = event
    return events


def interval_close_return(panel_path, stock, start, end):
    """Return an interval percentage across reviewed splits using positive-OHLC rows.

    This is a retrospective price-change diagnostic, not a trading or total-return
    label. Any unreviewed >=50% close discontinuity in the interval blocks it.
    """
    if start >= end or stock not in STOCKS:
        raise ValueError("Invalid stock or interval")
    first = None
    last = None
    factor = 1
    with panel_path.open(encoding="utf-8") as panel:
        for line in panel:
            row = json.loads(line)
            if row["stockCode"] != stock or row["date"] < start or row["date"] > end:
                continue
            if row["date"] == start and row["status"] != "NO_POSITIVE_OPEN_CLOSE":
                first = row
            if row["date"] == end and row["status"] != "NO_POSITIVE_OPEN_CLOSE":
                last = row
            if start < row["date"] <= end:
                if row["status"] == "UNREVIEWED_LARGE_RAW_CHANGE":
                    raise ValueError(f"Unreviewed price discontinuity: {stock} {row['date']}")
                if row["status"] == "VERIFIED_SPLIT_BOUNDARY":
                    factor *= row["appliedNewSharesPerOldShare"]
    if first is None or last is None:
        raise ValueError("Both interval endpoints need positive open/close rows")
    return pct(last["rawCloseKrw"], first["rawCloseKrw"], factor)


def archive_rows(folder, require_complete=True):
    """Yield date, original file hash, selected rows; never mutate the archive."""
    files = {}
    for path in folder.glob("stk_bydd_trd-*.json"):
        if path.name.endswith(".metadata.json"):
            continue
        parts = path.stem.split("-")
        if len(parts) != 3 or len(parts[1]) != 8 or not ("20160101" <= parts[1] <= "20251231"):
            continue
        if parts[1] in files:
            raise ValueError(f"Multiple archive versions need review: {parts[1]}")
        files[parts[1]] = (path, parts[2])
    if require_complete:
        day = date(2016, 1, 1)
        while day <= date(2025, 12, 31):
            if day.strftime("%Y%m%d") not in files:
                raise ValueError(f"Missing archive calendar date: {day}")
            day += timedelta(days=1)
    for day, (path, digest) in sorted(files.items()):
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != digest:
            raise ValueError(f"KRX payload hash mismatch: {path.name}")
        metadata = json.loads(path.with_name(path.name + ".metadata.json").read_text(encoding="utf-8"))
        rows = json.loads(payload)["OutBlock_1"]
        if (metadata["source"] != "KRX" or metadata["api"] != "stk_bydd_trd"
                or metadata["requestedDate"] != day or metadata["sha256"] != digest
                or metadata["rows"] != len(rows)):
            raise ValueError(f"KRX metadata mismatch: {path.name}")
        selected = {}
        for row in rows:
            code = row["ISU_CD"]
            if code in STOCKS:
                if code in selected:
                    raise ValueError(f"Duplicate stock on {day}: {code}")
                selected[code] = row
        if rows and set(selected) != set(STOCKS):
            raise ValueError(f"Missing pilot stock on nonempty market date: {day}")
        yield day, digest, selected


def derive(folder, event_path, panel_path, audit_path, require_complete=True):
    events = load_events(event_path)
    previous = {}
    encountered = set()
    counts = Counter()
    transitions = []
    panel_path.parent.mkdir(parents=True, exist_ok=True)
    audit_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = panel_path.with_suffix(panel_path.suffix + ".tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as output:
            for day, digest, rows in archive_rows(folder, require_complete):
                counts["archivedCalendarDays"] += 1
                if rows:
                    counts["nonemptyMarketDates"] += 1
                for code in STOCKS:
                    if code not in rows:
                        continue
                    row = rows[code]
                    open_ = int(row["TDD_OPNPRC"].replace(",", ""))
                    close = int(row["TDD_CLSPRC"].replace(",", ""))
                    current_day = f"{day[:4]}-{day[4:6]}-{day[6:]}"
                    event = events.get((current_day, code))
                    prior = previous.get(code)
                    result = None
                    raw_return = None
                    status = "NO_POSITIVE_OPEN_CLOSE"
                    factor = 1
                    if open_ > 0 and close > 0:
                        counts["positiveOhlcRows"] += 1
                        status = "FIRST_POSITIVE_OBSERVATION"
                        if prior:
                            raw_return = pct(close, prior[1])
                            result = raw_return
                            status = "RAW_CLOSE_CHANGE_NOT_FULLY_ACTION_REVIEWED"
                            if event:
                                if (prior[0] != event["lastPreSplitPositiveOhlcDate"]
                                        or prior[1] != event["expectedPreCloseKrw"]
                                        or close != event["expectedPostCloseKrw"]):
                                    raise ValueError(f"Verified split boundary no longer matches KRX raw prices: {code} {current_day}")
                                factor = event["newSharesPerOldShare"]
                                result = pct(close, prior[1], factor)
                                status = "VERIFIED_SPLIT_BOUNDARY"
                                encountered.add((current_day, code))
                                transitions.append({
                                    "stockCode": code, "previousDate": prior[0], "date": current_day,
                                    "previousCloseKrw": prior[1], "closeKrw": close,
                                    "newSharesPerOldShare": factor,
                                    "rawChangePct": decimal_string(raw_return),
                                    "splitAwareChangePct": decimal_string(result),
                                })
                            elif abs(raw_return) >= 50:
                                result = None
                                status = "UNREVIEWED_LARGE_RAW_CHANGE"
                                counts["unreviewedLargeRawChanges"] += 1
                        previous[code] = current_day, close
                    elif event:
                        raise ValueError(f"Verified split has no positive open/close: {code} {current_day}")
                    counts["panelRows"] += 1
                    counts[status] += 1
                    output.write(json.dumps({
                        "date": current_day, "stockCode": code, "rawOpenKrw": open_,
                        "rawCloseKrw": close, "sourceSha256": digest,
                        "previousPositiveDate": prior[0] if prior and open_ > 0 and close > 0 else None,
                        "rawCloseChangePct": decimal_string(raw_return) if raw_return is not None else None,
                        "splitAwareCloseChangePct": decimal_string(result) if result is not None else None,
                        "appliedNewSharesPerOldShare": factor if factor != 1 else None,
                        "status": status,
                    }, ensure_ascii=False, separators=(",", ":")) + "\n")
        if encountered != set(events):
            raise ValueError(f"Verified split not observed: {sorted(set(events) - encountered)}")
        temporary.replace(panel_path)
    finally:
        temporary.unlink(missing_ok=True)
    audit = {
        "status": "EXPLORATORY_SPLIT_AWARE_RETURNS_NOT_POINT_IN_TIME_APPROVED",
        "forecastReady": False, "approvedForDataset": False,
        "source": "KRX immutable raw daily-price archive",
        "eventPolicySha256": hashlib.sha256(event_path.read_bytes()).hexdigest(),
        "panelSha256": hashlib.sha256(panel_path.read_bytes()).hexdigest(),
        "counts": {**dict(sorted(counts.items())),
                   "unreviewedLargeRawChanges": counts["unreviewedLargeRawChanges"]},
        "verifiedSplitBoundaries": transitions,
        "limitations": [
            "Only two exact stock-split boundaries were verified; other corporate actions and dividends remain unreviewed.",
            "Returns are close-to-close diagnostics, not executable or dividend-inclusive investment returns.",
            "A positive open/close is a price candidate, not independent proof of an actual trading session.",
            "No historical KRX publication-time or original-response version is inferred from present-day archive files.",
        ],
    }
    audit_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return audit


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(".reports/price-expansion/archive"))
    parser.add_argument("--events", type=Path, default=Path("docs/verified-stock-splits-2018.json"))
    parser.add_argument("--panel", type=Path, default=Path(".reports/price-expansion/split-aware-returns-2016-2025.jsonl"))
    parser.add_argument("--audit", type=Path, default=Path(".reports/price-expansion/split-aware-returns-audit.json"))
    args = parser.parse_args()
    result = derive(args.archive, args.events, args.panel, args.audit)
    print(json.dumps({"counts": result["counts"], "splits": result["verifiedSplitBoundaries"]}, indent=2))

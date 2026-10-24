"""Build date-safe cancellation signals from reviewed, versioned disclosures."""

import json
from datetime import date, timedelta
from pathlib import Path


PILOT = Path("docs/evidence/cancellation-announcements-pilot-2026-10-23.json")


def load_events(path=PILOT):
    registry = json.loads(path.read_text(encoding="utf-8"))
    if registry["coverage"] not in ("PARTIAL_THREE_EVENTS_ONLY", "REVIEWED_2016_2025_MATCHES",
                                    "REVIEWED_2015_2025_MATCHES", "REVIEWED_2015_2026_OCT23"):
        raise ValueError("Unexpected cancellation review coverage")
    events = registry["events"]
    for event in events:
        announced = date.fromisoformat(event["announcementDate"])
        available = date.fromisoformat(event["availableFromDate"])
        listing = (date.fromisoformat(event["observedListingDate"])
                   if event["observedListingDate"] else None)
        versions = event.get("versions", [])
        if (available != announced + timedelta(days=1)
                or (listing is not None and listing <= announced)
                or event["plannedCommonShares"] <= 0
                or any(date.fromisoformat(version["availableFromDate"]) <= announced
                       for version in versions)):
            raise ValueError(f"Invalid cancellation chronology: {event['stockCode']}")
        if versions and any(left["availableFromDate"] >= right["availableFromDate"]
                            for left, right in zip(versions, versions[1:])):
            raise ValueError("Cancellation versions out of order")
    return events


def verify_listing_changes(days, events):
    """Post-event audit. These observed dates must never become model features."""
    by_date = {day: index for index, (day, _) in enumerate(days)}
    grouped = {}
    for event in events:
        if event["observedListingDate"] is None:
            if event["observedShareDelta"] is not None:
                raise ValueError("Unobserved listing change cannot have an observed delta")
            continue
        key = (event["stockCode"], event["observedListingDate"])
        grouped[key] = grouped.get(key, 0) + event["observedShareDelta"]
    for (stock, listing), expected in grouped.items():
        index = by_date[listing]
        previous = int(days[index - 1][1][stock]["LIST_SHRS"].replace(",", ""))
        current = int(days[index][1][stock]["LIST_SHRS"].replace(",", ""))
        if current - previous != expected:
            raise ValueError(f"KRX share change mismatches disclosure: {stock} {days[index][0]}")
    return set(grouped)


def asof_features(stock, decision_date, issued_shares, events, lookback_days=60):
    """Return (reviewed announcement count, announced shares / known shares, recency).

    Zero means no announcement in this *partial reviewed registry*, not proof that
    no announcement existed. Audit-only listing fields are deliberately unused.
    """
    if issued_shares <= 0:
        raise ValueError("Issued shares must be positive")
    decision = date.fromisoformat(decision_date)
    known = [event for event in events if event["stockCode"] == stock
             and 0 <= (decision - date.fromisoformat(event["availableFromDate"])).days <= lookback_days]
    if not known:
        return [0.0, 0.0, 0.0]
    newest = max(date.fromisoformat(event["availableFromDate"]) for event in known)
    def known_shares(event):
        shares = event["plannedCommonShares"]
        for version in event.get("versions", []):
            if date.fromisoformat(version["availableFromDate"]) <= decision:
                shares = version["plannedCommonShares"]
        return shares

    return [float(len(known)), sum(known_shares(event) for event in known) / issued_shares,
            float((decision - newest).days)]

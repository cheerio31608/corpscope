"""Contemporaneous archived-security breadth; deliberately not an official index."""

import hashlib
import json
from decimal import Decimal
from statistics import median

from build_split_aware_price_returns import STOCKS
from compare_price_baselines import load_archive


def daily_breadth(previous, current, excluded=STOCKS, minimum=500):
    returns = []
    for code in sorted(previous.keys() & current.keys() - set(excluded)):
        before, after = previous[code], current[code]
        values = [Decimal(row[key].replace(',', '')) for row in (before, after)
                  for key in ('TDD_CLSPRC', 'ACC_TRDVOL', 'LIST_SHRS')]
        if not all(x.is_finite() and x > 0 for x in values):
            continue
        if values[2] != values[5]:
            continue
        returns.append(values[3] / values[0] - 1)
    if len(returns) < minimum:
        raise ValueError(f'Insufficient paired archive securities: {len(returns)}')
    return {'medianReturn': float(median(returns)),
            'advancingFraction': sum(r > 0 for r in returns) / len(returns),
            'pairedSecurities': len(returns)}


def rolling_features(daily, lookback=20):
    result = {}
    for index in range(lookback - 1, len(daily)):
        window = daily[index - lookback + 1:index + 1]
        result[daily[index][0]] = [sum(item[1][key] for item in window) / lookback
                                   for key in ('medianReturn', 'advancingFraction')]
    return result


def load_breadth(archive):
    # Empty requested-stock set retains all selected dates without the eight-stock OHLC filter.
    selected = load_archive(archive, '2016-01-01', '2026-10-01', stocks=())
    previous = None
    daily, sources, counts = [], [], []
    for day, _, digest in selected:
        path = archive / f"stk_bydd_trd-{day.replace('-', '')}-{digest}.json"
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != digest:
            raise ValueError('Raw archive changed after validation')
        raw = json.loads(payload)['OutBlock_1']
        current = {r['ISU_CD']: r for r in raw}
        if len(current) != len(raw) or any(r['BAS_DD'] != day.replace('-', '') or r['MKT_NM'] != 'KOSPI' for r in raw):
            raise ValueError('Duplicate securities or mixed date/market')
        counts.append(len(current))
        if previous is not None:
            daily.append((day, daily_breadth(previous, current)))
        previous = current
        sources.append((day, digest))
    audit = {'selectedDateHashes': sources, 'observedDates': len(sources),
             'rawSecurityCountMin': min(counts), 'rawSecurityCountMax': max(counts),
             'pairedSecurityCountMin': min(x[1]['pairedSecurities'] for x in daily),
             'pairedSecurityCountMax': max(x[1]['pairedSecurities'] for x in daily),
             'dailyBreadth': daily}
    return rolling_features(daily), audit


def augment(rows, features):
    result = []
    for row in rows:
        if row['origin_date'] >= row['decision_date']:
            raise ValueError('Market feature cutoff is not before decision')
        if row['origin_date'] not in features:
            raise ValueError('Missing breadth: do not silently shrink frozen cohort')
        result.append({**row, 'features': row['features'] + features[row['origin_date']]})
    return result

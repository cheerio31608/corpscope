"""Predict next-month mean relative to the known last close, on frozen cohorts."""

import json
import math
import sys
from pathlib import Path

from cancellation_event_features import load_events
from compare_month_end_close_baseline import raw_monthly, source_digest
from compare_monthly_cancellation_features import with_inputs
from compare_monthly_cancellation_follow_on import checked_2026_days, restrict_test_origins
from compare_monthly_return_features import build_rows, candidate_predictions, training_window
from compare_ten_year_split_safe_baselines import chronological_fold, load_checked_days
from compare_validation_month_end_blend import close, mae


def to_last_close_target(mean_return, last_over_mean):
    if not math.isfinite(last_over_mean) or last_over_mean <= 0:
        raise ValueError('Invalid origin price ratio')
    return 100 * ((1 + mean_return / 100) / last_over_mean - 1)


def to_mean_return(prediction, last_over_mean):
    if not math.isfinite(last_over_mean) or last_over_mean <= 0:
        raise ValueError('Invalid origin price ratio')
    return 100 * ((1 + prediction / 100) * last_over_mean - 1)


def retarget(rows):
    # Copy rows: original targets and all input/availability fields remain intact.
    return [{**r, 'mean_target_pct': r['target_pct'],
             'target_pct': to_last_close_target(r['target_pct'], 1 + r['features'][3])}
            for r in rows]


def converted(rows, predictions):
    return [to_mean_return(p, 1 + r['features'][3])
            for r, p in zip(rows, predictions, strict=True)]


def select_candidate(train, validation, year, protocol):
    scores = {}
    for name in protocol['candidateOrder']:
        window = 'trailing_36' if name.endswith('trailing_36') else 'expanding'
        fit = training_window(train, f'{year - 1}-01-01', window)
        pred = candidate_predictions(name, fit, validation, protocol)
        scores[name] = mae([r['mean_target_pct'] for r in validation], converted(validation, pred))
    return min(scores, key=scores.get), scores


def run():
    sys.path.insert(0, str(Path('.tools/model-deps').resolve()))
    root = Path('.reports/model-experiments-20261023')
    paths = {
        'protocol': Path('docs/last-close-target-protocol-2026-10-23.json'),
        'historical': root / 'monthly-cancellation-paired.json',
        'followOn': root / 'monthly-cancellation-follow-on-2026.json',
        'baseline': root / 'month-end-close-baseline.json',
        'blend': root / 'validation-month-end-blend.json',
        'oldRegistry': root / 'cancellation-reviewed-registry.json',
        'registry': root / 'cancellation-reviewed-registry-through-2026.json',
    }
    data = {k: json.loads(p.read_text(encoding='utf-8')) for k, p in paths.items()}
    protocol, old, new = data['protocol'], data['historical'], data['followOn']
    if (protocol['policy'] != 'MONTHLY_LAST_CLOSE_TARGET_V1'
            or protocol['status'] != 'EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED'
            or protocol['testYears'] != list(range(2022, 2027))):
        raise ValueError('Unexpected protocol')
    if (old['registrySha256'] != source_digest(paths['oldRegistry'])
            or new['registrySha256'] != source_digest(paths['registry'])):
        raise ValueError('Registry changed')
    archive = Path('.reports/price-expansion/archive')
    history, audit = load_checked_days(archive,
        Path('.reports/price-expansion/split-aware-returns-2016-2025.jsonl'),
        Path('.reports/price-expansion/split-aware-returns-audit.json'),
        Path('docs/verified-stock-splits-2018.json'))
    current, digest = checked_2026_days(archive)
    if (audit['panelSha256'] != old['sourcePanelSha256']
            or audit['panelSha256'] != new['historicalPanelSha256']
            or digest != new['selected2026DailyHashesSha256']):
        raise ValueError('Price archive changed')
    historical_rows, _ = build_rows(history, load_events(paths['oldRegistry']), 120)
    current_rows, _ = build_rows(history + current, load_events(paths['registry']), 120)
    current_rows = restrict_test_origins(current_rows, [f'2026-{m:02d}' for m in range(1, 9)])
    monthly = raw_monthly(history + current)
    result = {'policy': protocol['policy'], 'status': protocol['status'],
              'forecastReady': False, 'approvedForDataset': False,
              'sourceHashes': {k: source_digest(p) for k, p in paths.items()},
              'limitations': protocol['limitations'], 'years': {}}
    for year in protocol['testYears']:
        rows = retarget(with_inputs(current_rows if year == 2026 else historical_rows, False))
        # Audit every training/validation/test target against independent raw monthly prices.
        for r in rows:
            before, after = monthly[(r['stock'], r['origin_month'])], monthly[(r['stock'], r['target_month'])]
            close(r['target_pct'], float((after['mean'] / before['last'] - 1) * 100))
        train, validation, test = chronological_fold(rows, year)
        selected, scores = select_candidate(train, validation, year, protocol)
        window = 'trailing_36' if selected.endswith('trailing_36') else 'expanding'
        fit = training_window(rows, f'{year}-01-01', window)
        prediction = candidate_predictions(selected, fit, test, protocol)
        mean_prediction = converted(test, prediction)
        saved = data['baseline']['years'][str(year)]['pairedRows']
        mixed = data['blend']['years'][str(year)]['pairedRows']
        paired = []
        for r, p, mp, b, m in zip(test, prediction, mean_prediction, saved, mixed, strict=True):
            key = (r['stock'], r['origin_month'], r['target_month'])
            for original in (b, m):
                if key != (original['stockCode'], original['originMonth'], original['targetMonth']):
                    raise ValueError('Frozen cohort mismatch')
                close(r['mean_target_pct'], float(original['actualPct']))
            close(r['features'][3] * 100, float(b['predictions']['originLastClose']))
            paired.append({'stockCode': r['stock'], 'originMonth': r['origin_month'],
                           'targetMonth': r['target_month'], 'actualPct': r['mean_target_pct'],
                           'actualLastCloseReturnPct': r['target_pct'],
                           'predictedLastCloseReturnPct': p,
                           'predictions': {'originLastClose': float(b['predictions']['originLastClose']),
                                           'oldPriceModel': float(b['predictions']['priceSelected']),
                                           'oldBlend': m['predictions']['validationBlend'], 'newTargetSelected': mp}})
        result['years'][str(year)] = {
            'rows': len(test), 'selectedModel': selected, 'validationYear': year - 1,
            'validationMaeMeanBasis': scores, 'eligibleTestTrainingRows': len(fit),
            'fittedTrainingRows': 0 if selected == 'recent_0pct' else len(fit),
            'maxTrainingLabelDate': max(r['label_available_date'] for r in fit),
            'maeMeanBasisPctPoints': score(paired),
            'maeLastCloseBasisPctPoints': {'baseline': mae([r['target_pct'] for r in test], [0.] * len(test)),
                                          'selected': mae([r['target_pct'] for r in test], prediction)},
            'pairedRows': paired}
    pooled = [r for y in range(2022, 2026) for r in result['years'][str(y)]['pairedRows']]
    result['historicalPooled'] = {'rows': len(pooled), 'maeMeanBasisPctPoints': score(pooled)}
    (root / 'last-close-target.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    print(json.dumps({y: {k: v for k, v in d.items() if k != 'pairedRows'} for y, d in result['years'].items()}, indent=2))
    print(json.dumps(result['historicalPooled']))


def score(rows):
    return {k: mae([r['actualPct'] for r in rows], [r['predictions'][k] for r in rows])
            for k in rows[0]['predictions']}


if __name__ == '__main__':
    run()

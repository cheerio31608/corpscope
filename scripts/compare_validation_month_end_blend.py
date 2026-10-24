"""Select a fixed-grid price-model blend using preceding-year validation only."""

import json
import math
import sys
from pathlib import Path

from cancellation_event_features import load_events
from compare_month_end_close_baseline import source_digest
from compare_monthly_cancellation_features import with_inputs
from compare_monthly_cancellation_follow_on import checked_2026_days, restrict_test_origins
from compare_monthly_return_features import build_rows, candidate_predictions, training_window
from compare_ten_year_split_safe_baselines import chronological_fold, load_checked_days


def blend(baseline, model, alpha):
    if not 0 <= alpha <= 1:
        raise ValueError("Blend weight outside [0, 1]")
    return [b + alpha * (p - b) for b, p in zip(baseline, model, strict=True)]


def mae(actual, prediction):
    if not actual:
        raise ValueError("Empty cohort")
    pairs = list(zip(actual, prediction, strict=True))
    if not all(math.isfinite(a) and math.isfinite(p) for a, p in pairs):
        raise ValueError("Nonfinite value")
    return sum(abs(a - p) for a, p in pairs) / len(pairs)


def select_alpha(actual, baseline, model, alphas):
    scores = {a: mae(actual, blend(baseline, model, a)) for a in alphas}
    return min(scores, key=lambda a: (scores[a], a)), scores


def close(a, b):
    if not math.isfinite(a) or not math.isfinite(b) or abs(a - b) > 1e-8:
        raise ValueError("Frozen prediction, target or validation score changed")


def run():
    sys.path.insert(0, str(Path('.tools/model-deps').resolve()))
    root = Path('.reports/model-experiments-20261023')
    paths = {
        'protocol': Path('docs/month-end-blend-protocol-2026-10-23.json'),
        'historical': root / 'monthly-cancellation-paired.json',
        'followOn': root / 'monthly-cancellation-follow-on-2026.json',
        'baseline': root / 'month-end-close-baseline.json',
        'registry': root / 'cancellation-reviewed-registry-through-2026.json',
        'oldRegistry': root / 'cancellation-reviewed-registry.json',
        'oldModelProtocol': Path('docs/monthly-return-features-protocol-2026-10-23.json'),
        'newModelProtocol': Path('docs/monthly-cancellation-follow-on-protocol-2026-10-23.json'),
    }
    data = {k: json.loads(p.read_text(encoding='utf-8')) for k, p in paths.items()}
    protocol, old, new = data['protocol'], data['historical'], data['followOn']
    if (protocol['alphas'] != [0, .25, .5, .75, 1]
            or protocol['testYears'] != list(range(2022, 2027))
            or protocol['policy'] != 'VALIDATION_SELECTED_MONTH_END_BLEND_V1'
            or protocol['status'] != 'EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED'):
        raise ValueError('Unexpected experiment protocol')
    for report, registry, model_protocol in ((old, 'oldRegistry', 'oldModelProtocol'),
                                              (new, 'registry', 'newModelProtocol')):
        if (report['registrySha256'] != source_digest(paths[registry])
                or report['protocolSha256'] != source_digest(paths[model_protocol])):
            raise ValueError('Frozen source metadata changed')
    archive = Path('.reports/price-expansion/archive')
    history, audit = load_checked_days(archive,
        Path('.reports/price-expansion/split-aware-returns-2016-2025.jsonl'),
        Path('.reports/price-expansion/split-aware-returns-audit.json'),
        Path('docs/verified-stock-splits-2018.json'))
    current, digest = checked_2026_days(archive)
    if (audit['panelSha256'] != old['sourcePanelSha256']
            or audit['panelSha256'] != new['historicalPanelSha256']
            or digest != new['selected2026DailyHashesSha256']):
        raise ValueError('Frozen price archive changed')
    historical_rows, _ = build_rows(history, load_events(paths['oldRegistry']), 120)
    current_rows, _ = build_rows(history + current, load_events(paths['registry']), 120)
    current_rows = restrict_test_origins(current_rows, [f'2026-{m:02d}' for m in range(1, 9)])
    result = {'policy': protocol['policy'], 'status': protocol['status'],
              'forecastReady': False, 'approvedForDataset': False,
              'sourceHashes': {k: source_digest(p) for k, p in paths.items()},
              'limitations': protocol['limitations'], 'years': {}}
    for year in protocol['testYears']:
        rows = with_inputs(current_rows if year == 2026 else historical_rows, False)
        frozen = new if year == 2026 else old['folds'][str(year)]
        model_protocol = data['newModelProtocol' if year == 2026 else 'oldModelProtocol']
        name = frozen['priceOnly']['selectedFromValidation']
        window = 'trailing_36' if name.endswith('trailing_36') else 'expanding'
        train, validation, test = chronological_fold(rows, year)
        val_fit = training_window(train, f'{year - 1}-01-01', window)
        val_pred = candidate_predictions(name, val_fit, validation, model_protocol)
        val_actual = [r['target_pct'] for r in validation]
        close(mae(val_actual, val_pred), frozen['priceOnly']['validationMaePctPoints'][name])
        alpha, scores = select_alpha(val_actual, [r['features'][3] * 100 for r in validation],
                                     val_pred, protocol['alphas'])
        fit = training_window(rows, f'{year}-01-01', window)
        pred = candidate_predictions(name, fit, test, model_protocol)
        expected = frozen['pairedMonthly']
        baseline_rows = data['baseline']['years'][str(year)]['pairedRows']
        for row, p, original, benchmark in zip(test, pred, expected, baseline_rows, strict=True):
            key = (row['stock'], row['origin_month'], row['target_month'])
            for saved in (original, benchmark):
                if key != (saved['stockCode'], saved['originMonth'], saved['targetMonth']):
                    raise ValueError('Frozen cohort changed')
            close(row['target_pct'], original['actualPct'])
            close(p, original['priceOnlyPredictedPct'])
            close(row['features'][3] * 100, float(benchmark['predictions']['originLastClose']))
        actual = [r['target_pct'] for r in test]
        base = [r['features'][3] * 100 for r in test]
        predictions = {'previousMean0Pct': [0.] * len(test), 'originLastClose': base,
                       'priceSelected': pred, 'validationBlend': blend(base, pred, alpha)}
        result['years'][str(year)] = {
            'rows': len(test), 'selectedModel': name, 'selectedAlpha': alpha,
            'validationYear': year - 1, 'validationRows': len(validation),
            'validationMaeByAlpha': scores, 'validationTrainingRows': len(val_fit),
            'testTrainingRows': len(fit),
            'maxValidationTrainingLabelDate': max(r['label_available_date'] for r in val_fit),
            'maxTestTrainingLabelDate': max(r['label_available_date'] for r in fit),
            'maePctPoints': {k: mae(actual, p) for k, p in predictions.items()},
            'pairedRows': [{'stockCode': r['stock'], 'originMonth': r['origin_month'],
                            'targetMonth': r['target_month'], 'actualPct': r['target_pct'],
                            'predictions': {k: p[i] for k, p in predictions.items()}}
                           for i, r in enumerate(test)]}
    pooled = [r for y in range(2022, 2026) for r in result['years'][str(y)]['pairedRows']]
    result['historicalPooled'] = {'rows': len(pooled), 'maePctPoints': {
        k: mae([r['actualPct'] for r in pooled], [r['predictions'][k] for r in pooled])
        for k in predictions}}
    (root / 'validation-month-end-blend.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    print(json.dumps({y: {k: v for k, v in item.items() if k != 'pairedRows'}
                      for y, item in result['years'].items()}, indent=2))
    print(json.dumps(result['historicalPooled']))


if __name__ == '__main__':
    run()

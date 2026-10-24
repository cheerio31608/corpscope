"""Same-cohort comparison adding two archived cross-sectional market features."""
import json
import sys
from pathlib import Path

from archived_market_breadth import augment, load_breadth
from cancellation_event_features import load_events
from compare_last_close_target import converted, retarget, score
from compare_month_end_close_baseline import source_digest
from compare_monthly_cancellation_features import with_inputs
from compare_monthly_cancellation_follow_on import checked_2026_days, restrict_test_origins
from compare_monthly_return_features import build_rows, candidate_predictions, training_window
from compare_ten_year_split_safe_baselines import chronological_fold, load_checked_days
from compare_three_year_selection import annual_validation, select_three_years
from compare_validation_month_end_blend import close


def run():
    sys.path.insert(0, str(Path('.tools/model-deps').resolve()))
    root = Path('.reports/model-experiments-20261023')
    paths = {'protocol': Path('docs/market-breadth-protocol-2026-10-23.json'),
             'singleYear': root / 'last-close-target.json',
             'priceOnly': root / 'three-year-selection.json',
             'historical': root / 'monthly-cancellation-paired.json',
             'followOn': root / 'monthly-cancellation-follow-on-2026.json',
             'oldRegistry': root / 'cancellation-reviewed-registry.json',
             'registry': root / 'cancellation-reviewed-registry-through-2026.json'}
    data = {k: json.loads(p.read_text(encoding='utf-8')) for k, p in paths.items()}
    protocol, old, new = data['protocol'], data['historical'], data['followOn']
    if (protocol['policy'] != 'ARCHIVED_MARKET_BREADTH_ABLATION_V1' or protocol['validationYears'] != 3
            or protocol['status'] != 'EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED'
            or protocol['testYears'] != list(range(2022, 2027))):
        raise ValueError('Unexpected protocol')
    for report, registry in ((old, 'oldRegistry'), (new, 'registry')):
        if report['registrySha256'] != source_digest(paths[registry]):
            raise ValueError('Frozen registry mismatch')
    archive = Path('.reports/price-expansion/archive')
    history, audit = load_checked_days(archive,
        Path('.reports/price-expansion/split-aware-returns-2016-2025.jsonl'),
        Path('.reports/price-expansion/split-aware-returns-audit.json'),
        Path('docs/verified-stock-splits-2018.json'))
    current, digest = checked_2026_days(archive)
    if (audit['panelSha256'] != old['sourcePanelSha256']
            or audit['panelSha256'] != new['historicalPanelSha256']
            or digest != new['selected2026DailyHashesSha256']):
        raise ValueError('Frozen prices mismatch')
    historical, _ = build_rows(history, load_events(paths['oldRegistry']), 120)
    current_rows, _ = build_rows(history + current, load_events(paths['registry']), 120)
    current_rows = restrict_test_origins(current_rows, [f'2026-{m:02d}' for m in range(1, 9)])
    market_features, market_audit = load_breadth(archive)
    result = {'marketAudit': market_audit, 'policy': protocol['policy'], 'status': protocol['status'],
              'forecastReady': False, 'approvedForDataset': False,
              'sourceHashes': {k: source_digest(p) for k, p in paths.items()},
              'limitations': protocol['limitations'], 'years': {}}
    for year in protocol['testYears']:
        rows = retarget(with_inputs(current_rows if year == 2026 else historical, False))
        price_annual = {str(y): annual_validation(rows, y, protocol) for y in range(year - 3, year)}
        frozen_price = data['priceOnly']['years'][str(year)]
        for y, item in price_annual.items():
            for name in protocol['candidateOrder']:
                close(item['maePctPoints'][name], frozen_price['annualValidation'][y]['maePctPoints'][name])
        rows = augment(rows, market_features)
        annual = {str(y): annual_validation(rows, y, protocol) for y in range(year - 3, year)}
        original = data['singleYear']['years'][str(year)]
        # Reproduce the last validation year's scores from the frozen preceding experiment.
        for name in protocol['candidateOrder']:
            close(price_annual[str(year - 1)]['maePctPoints'][name], original['validationMaeMeanBasis'][name])
        selected, summary = select_three_years(list(annual.values()), protocol['candidateOrder'])
        test = chronological_fold(rows, year)[2]
        window = 'trailing_36' if selected.endswith('trailing_36') else 'expanding'
        fit = training_window(rows, f'{year}-01-01', window)
        predictions = converted(test, candidate_predictions(selected, fit, test, protocol))
        paired = []
        for row, prediction, saved, price_saved in zip(test, predictions, original['pairedRows'], frozen_price['pairedRows'], strict=True):
            if (row['stock'], row['origin_month'], row['target_month']) != (
                    saved['stockCode'], saved['originMonth'], saved['targetMonth']):
                raise ValueError('Frozen cohort changed')
            if (price_saved['stockCode'], price_saved['originMonth']) != (row['stock'], row['origin_month']):
                raise ValueError('Price-only paired cohort mismatch')
            close(row['mean_target_pct'], saved['actualPct'])
            close(row['target_pct'], saved['actualLastCloseReturnPct'])
            paired.append({'stockCode': row['stock'], 'originMonth': row['origin_month'],
                           'targetMonth': row['target_month'], 'actualPct': row['mean_target_pct'],
                           'predictions': {'originLastClose': saved['predictions']['originLastClose'],
                                           'oldBlend': saved['predictions']['oldBlend'],
                                           'priceOnlyThreeYear': price_saved['predictions']['threeYearSelection'],
                                           'priceAndBreadthThreeYear': prediction}})
        result['years'][str(year)] = {'rows': len(test), 'selectedModel': selected,
            'annualValidation': annual, 'candidateSummary': summary,
            'fittedTrainingRows': 0 if selected == 'recent_0pct' else len(fit),
            'maxTrainingLabelDate': max(r['label_available_date'] for r in fit),
            'maePctPoints': score(paired), 'pairedRows': paired}
        print(json.dumps({'year': year, 'selected': selected, 'summary': summary,
                          'testMae': score(paired)}), flush=True)
    pooled = [r for y in range(2022, 2026) for r in result['years'][str(y)]['pairedRows']]
    result['historicalPooled'] = {'rows': len(pooled), 'maePctPoints': score(pooled)}
    (root / 'market-breadth-comparison.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    print(json.dumps(result['historicalPooled']))


if __name__ == '__main__':
    run()

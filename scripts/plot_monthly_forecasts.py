"""Render saved monthly predictions without fitting models or changing source data."""
import hashlib
import html
import json
import os
import sys
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('MPLCONFIGDIR', str(ROOT / '.tools/matplotlib-cache'))
sys.path.insert(0, str(ROOT / '.tools/plot-deps'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

SOURCE = ROOT / '.reports/model-experiments-20261023/month-end-close-baseline.json'
OUT = ROOT / '.reports/forecast-visualizations'
NAMES = {'000270': '기아', '000660': 'SK하이닉스', '004020': '현대제철',
         '005380': '현대자동차', '005930': '삼성전자', '015760': '한국전력',
         '035420': 'NAVER', '036460': '한국가스공사'}
ARMS = [('priceSelected', '가격 모델', '#2563eb'),
        ('originLastClose', '월말 종가 유지', '#d97706'),
        ('priceAndDisclosureSelected', '가격 + 소각 공시', '#059669')]
D = Decimal


def months(start, end):
    y, m = map(int, start.split('-'))
    result = []
    while f'{y:04d}-{m:02d}' <= end:
        result.append(f'{y:04d}-{m:02d}')
        m += 1
        if m == 13:
            y, m = y + 1, 1
    return result


def save(fig, name):
    fig.savefig(OUT / f'{name}.png', dpi=160, facecolor='white')
    fig.savefig(OUT / f'{name}.svg', facecolor='white')
    plt.close(fig)


def main():
    data = json.loads(SOURCE.read_text(encoding='utf-8'))
    assert data['sameFrozenRows'] and not data['approvedForDataset']
    rows = []
    audit = {}
    for year, group in sorted(data['years'].items()):
        cohort = group['pairedRows']
        assert len(cohort) == (56 if year == '2026' else 88)
        assert len({(r['stockCode'], r['targetMonth']) for r in cohort}) == len(cohort)
        for row in cohort:
            actual = (D(row['targetMeanCloseKrw']) / D(row['originMeanCloseKrw']) - 1) * 100
            assert abs(actual - D(row['actualPct'])) < D('1e-9')
            baseline = (D(row['originLastCloseKrw']) / D(row['originMeanCloseKrw']) - 1) * 100
            assert abs(baseline - D(row['predictions']['originLastClose'])) < D('1e-9')
            assert row['stockCode'] in NAMES
        audit[year] = {}
        for key, _, _ in ARMS:
            mae = sum(abs(D(r['predictions'][key]) - D(r['actualPct'])) for r in cohort) / len(cohort)
            assert abs(float(mae) - group['maePctPoints'][key]) < 1e-9
            audit[year][key] = float(mae)
        rows.extend(cohort)
    assert len(rows) == 408
    OUT.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({'font.family': 'Malgun Gothic', 'axes.unicode_minus': False,
                         'font.size': 10, 'axes.spines.top': False, 'axes.spines.right': False})
    pages = []
    for year, group in sorted(data['years'].items()):
        grid = months(f'{year}-01', f'{year}-12' if year != '2026' else '2026-09')
        fig, axes = plt.subplots(4, 2, figsize=(14, 14), layout='constrained')
        for ax, (stock, name) in zip(axes.flat, NAMES.items()):
            found = {r['targetMonth']: r for r in group['pairedRows'] if r['stockCode'] == stock}
            actual = [float(found[m]['targetMeanCloseKrw']) if m in found else np.nan for m in grid]
            ax.plot(range(len(grid)), actual, color='#111827', marker='o', linewidth=2, label='실제 평균 종가')
            for key, label, color in ARMS:
                predicted = [float(D(found[m]['originMeanCloseKrw']) *
                             (1 + D(found[m]['predictions'][key]) / 100)) if m in found else np.nan for m in grid]
                ax.plot(range(len(grid)), predicted, color=color, marker='.', linestyle='--', label=label, alpha=.85)
            ax.set_title(f'{name} · {len(found)}개월', loc='left', fontweight='bold')
            ax.set_xticks(range(len(grid)), [m[5:] for m in grid])
            ax.set_ylabel('평균 종가 (원)')
            ax.ticklabel_format(axis='y', style='plain')
            ax.grid(axis='y', alpha=.15)
        handles, labels = axes.flat[0].get_legend_handles_labels()
        fig.legend(handles, labels, loc='outside lower center', ncol=4, frameon=False)
        fig.suptitle(f'{year}년 예측값과 실제값\n목표 월 기준 · 빈 구간은 평가 표본 없음 · 기업별 세로축 범위가 다름', fontsize=17)
        save(fig, year)
        pages.append((year, f'{year}년 기업별 비교'))

    fig, axes = plt.subplots(1, 2, figsize=(13, 5), layout='constrained')
    for ax, cohort, title in [(axes[0], [r for r in rows if r['targetMonth'] < '2026'], '2022~2025년 · 352개'),
                              (axes[1], [r for r in rows if r['targetMonth'] >= '2026'], '2026년 · 56개')]:
        values = [float(sum(abs(D(r['predictions'][key]) - D(r['actualPct'])) for r in cohort) / len(cohort)) for key, _, _ in ARMS]
        bars = ax.barh([label for _, label, _ in ARMS], values, color=[c for _, _, c in ARMS])
        ax.bar_label(bars, fmt='%.3f', padding=5)
        ax.set_xlim(0, max(values)*1.2)
        ax.invert_yaxis()
        ax.set_xlabel('평균절대오차 (%p) · 낮을수록 좋음')
        ax.set_title(title)
    fig.suptitle('같은 표본에서의 오차 비교', fontsize=17)
    save(fig, 'errors')

    note = '이미 관찰한 과거 기간의 회고적 실험이다. 평균 종가 예측이며 투자 수익률이 아니다. 제외된 월은 보간하지 않았다.'
    sections = '<h2>오차 비교</h2><img src="errors.png" alt="기간별 평균절대오차 비교">'
    for name, title in pages:
        sections += f'<h2>{title}</h2><p><a href="{name}.svg">SVG</a> · <a href="{name}.png">PNG</a></p><img src="{name}.png" alt="{title}">'
    document = '<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>CorpScope 예측 비교</title><style>body{max-width:1200px;margin:40px auto;padding:0 24px;font:16px/1.7 system-ui;color:#172033}img{width:100%;height:auto}h2{margin-top:48px}a{color:#2563eb}</style><h1>예측값과 실제값</h1><p>월말까지의 정보로 다음 달 일별 종가 평균을 예측했다. 가격 모델은 연도별 사전 검증에서 선택한 선형회귀 또는 LightGBM이다. 주황색은 알려진 월말 종가를 다음 달 평균으로 사용하는 기준선이다.</p><p>' + html.escape(note) + '</p>' + sections + '</html>'
    (OUT / 'index.html').write_text(document, encoding='utf-8')
    (OUT / 'audit.json').write_text(json.dumps({'source': str(SOURCE.relative_to(ROOT)),
        'sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(), 'rows': len(rows),
        'maeByYear': audit, 'modelRetrained': False, 'approvedForDataset': False,
        'conversion': 'originMeanCloseKrw * (1 + predictedPct / 100)'}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Created charts for {len(rows)} rows: {OUT}')


if __name__ == '__main__':
    main()

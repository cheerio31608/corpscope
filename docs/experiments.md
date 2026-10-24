# 실험 코드

월별 예측 실험에 사용한 코드와 직접 의존하는 모듈·테스트를 선별했다. 수집 원본과 인증키는 포함하지 않는다. 저장소만으로 단위 테스트와 그래프 열람은 가능하지만, 전체 학습 결과를 재현하려면 아래의 로컬 자료가 필요하다.

## 읽는 순서

| 파일 | 역할 |
| --- | --- |
| `scripts/build_split_aware_price_returns.py` | 가격 원본 해시·수정 정책 검증과 패널 생성 |
| `scripts/compare_monthly_return_features.py` | 월별 입력 생성, 미래 결과 분리, 학습 기간 선택 |
| `scripts/compare_ten_year_split_safe_baselines.py` | `chronological_fold` 시간 분할과 원본·패널 대조 |
| `scripts/compare_rolling_price_candidates.py` | 선형회귀·LightGBM 생성과 검증 점수 기반 선택 |
| `scripts/cancellation_event_features.py` | 공개 당시 소각 예정 수량과 정정 이력 반영 |
| `scripts/compare_monthly_cancellation_features.py` | 같은 표본에서 가격 전용·공시 추가 모델 비교 |
| `scripts/compare_month_end_close_baseline.py` | 월말 종가 유지 기준선 비교 |
| `scripts/compare_three_year_selection.py` | 직전 3년의 반복 검증으로 후보 선택 |
| `scripts/compare_market_breadth.py` | 보관 종목의 시장 공통 움직임 추가 비교 |
| `scripts/plot_monthly_forecasts.py` | 저장된 예측값을 원 단위 평균 가격으로 환산해 시각화 |

함수를 가져오기 위해 포함된 일부 모듈에는 이전 63관측일·재무 결합 실험의 실행 진입점도 남아 있다. 아래 명령은 월별 실험의 실행 순서다. 다른 진입점에는 별도 원본·설정이 필요할 수 있다.

## 실행 환경

Python 3.12를 사용한다. 저장소 루트에서 가상환경을 만든다.

```powershell
py -3.12 -m venv .venv
.venv/Scripts/Activate.ps1
python -m pip install -r scripts/model-requirements.txt
python -m pip install -r scripts/plot-requirements.txt
New-Item -ItemType Directory -Force .reports | Out-Null
python -m unittest discover -s scripts -p 'test_*.py'
```

scikit-learn은 스케일링과 선형회귀, LightGBM은 트리 모델, matplotlib은 PNG·SVG 생성에 사용한다. 모델 후보와 버전은 기존 실험을 유지했다. 테스트 자료는 코드 내부에서 생성한 가상 값 또는 임시 파일이며 실제 기업 수집 DB에 기록하지 않는다.

## 입력 자료

| 경로 | 형식과 용도 |
| --- | --- |
| `.reports/price-expansion/archive/` | 날짜별 KRX 원본 JSON 및 metadata |
| `docs/verified-stock-splits-2018.json` | 검토한 분할 사건 정책. 저장소에 포함 |
| `.reports/price-expansion/split-aware-returns-2016-2025.jsonl` | 원본을 대조해 생성하는 날짜·종목별 패널 |
| `.reports/price-expansion/split-aware-returns-audit.json` | 패널·정책 해시와 검토 범위 |
| `.reports/model-experiments-20261023/cancellation-reviewed-registry.json` | 검토한 소각 원공시·정정 버전 목록 |
| `.reports/model-experiments-20261023/cancellation-reviewed-registry-through-2026.json` | 2026년 후속 검토를 포함한 목록 |

과거 가격 원본 파일명은 `stk_bydd_trd-YYYYMMDD-SHA256.json`이며 옆에 같은 이름의 `.metadata.json`을 둔다. JSON의 `OutBlock_1`은 종목 행 배열이다. 월별 입력에 사용하는 필드는 `ISU_CD`(종목 코드), `TDD_CLSPRC`(종가), `ACC_TRDVOL`(거래량), `LIST_SHRS`(상장주식 수)이며 원본 대조에는 `TDD_OPNPRC`(시가)도 필요하다. 종목 코드는 앞자리 0을 유지한다.

metadata에는 `source: KRX`, `api: stk_bydd_trd`, `requestedDate: YYYYMMDD`, `sha256`, `rows`가 필요하다. 파일명·원본·metadata의 해시가 일치해야 한다. 과거 적재 검증은 2016~2025년 모든 달력 날짜의 응답을 요구하며, 빈 응답도 보관한다. 이를 휴장일의 증명으로 해석하지 않는다. 비어 있지 않은 날짜에는 대상 8개 종목이 모두 있어야 한다.

소각 목록은 `coverage`와 `events`를 가진 JSON이다. 사건별 `stockCode`, `announcementDate`, `availableFromDate`, `plannedCommonShares`, `observedListingDate`, `observedShareDelta`, `versions`를 사용한다. 정정 버전은 `availableFromDate`와 `plannedCommonShares`를 보존한다. 실제 상장주식 수 변화일은 감사용이며 모델 입력으로 넣지 않는다. 단순히 필드를 채워 검토 상태를 만들면 안 된다. 원공시·정정 원문과 수량 대조가 선행되어야 한다.

## 실행 순서

원본과 소각 검토 목록이 준비된 경우 실행한다. 키를 사용해 다운로드하는 수집 과정은 이 공개 패키지에 포함하지 않았다.

```powershell
python scripts/build_split_aware_price_returns.py
python scripts/compare_monthly_cancellation_features.py
python scripts/compare_monthly_cancellation_follow_on.py
python scripts/compare_month_end_close_baseline.py
python scripts/compare_validation_month_end_blend.py
python scripts/compare_last_close_target.py
python scripts/compare_three_year_selection.py
python scripts/compare_market_breadth.py
python scripts/plot_monthly_forecasts.py
```

후속 비교에는 2026년 가격 원본과 후속 소각 목록도 필요하다. 시장 공통 움직임 계산에는 8개 종목만 추린 가격 파일이 아니라 당시 보관한 전체 종목 응답이 필요하다. 각 단계는 앞선 산출물과 해시를 확인한다. 자료가 없거나 다르면 실패로 남기며 검증을 건너뛰지 않는다. 결과는 `.reports`에 저장하며 기존 동명 파일이 있으면 갱신하므로 다른 실험을 보존하려면 별도 작업 사본에서 실행한다.

시각화만 다시 만들 때는 `month-end-close-baseline.json`이 필요하다. `years` 아래 연도별 `pairedRows`에 종목 코드, 출발·목표 월, 실제 변화율, 출발 월평균·월말 종가, 목표 월평균과 모델별 예측 변화율이 들어간다. 스크립트는 352개 과거 표본과 56개 후속 표본, 연도별 MAE를 대조하고 `.reports/forecast-visualizations`에 저장한다. 가격 환산은 `출발 월평균 × (1 + 예측 변화율 / 100)`이다. 새 데이터 규모를 자동 수용하는 범용 도구가 아니라 이번 실험 기록에 고정된 그림 생성기다.

## 결과 보기

[실험 설명](forecast-experiments-portfolio.md) · [오차 비교](figures/errors.png) · [2022년](figures/2022.png) · [2023년](figures/2023.png) · [2024년](figures/2024.png) · [2025년](figures/2025.png) · [2026년](figures/2026.png)

HTML 모음은 `docs/figures/index.html`을 로컬 브라우저에서 열면 된다. GitHub에서는 PNG 링크로 볼 수 있다. 원본 가격 파일과 행별 실험 보고서는 제외하고 결과 그림만 공개했다. 그래프는 기존 결과를 옮긴 것으로 새 미래 평가나 재학습 결과가 아니다.


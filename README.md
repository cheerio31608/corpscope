# CorpScope

공공기관의 기업·재무 데이터를 안정적으로 수집·정제·저장하고 효율적으로 조회하는 백엔드 시스템입니다. 단순 CRUD를 넘어 재시작 가능한 적재, 계정 표준화, 정정 보고서의 이력과 과거 시점 조회를 학습하는 개인 포트폴리오 프로젝트입니다.

## 공개 범위

이 저장소는 실행 가능한 Java 백엔드, 테스트, DB 마이그레이션 및 기본 실행 스크립트를 담은 첫 공개본입니다. 로컬에서 수집한 OpenDART/KRX 원본, DB 덤프, 인증정보, 실험 산출물과 Python 모델 실험 코드는 포함하지 않습니다. 따라서 로컬 연구 데이터나 모델 실험 전체가 재현되는 저장소는 아닙니다. 생성형 AI 호출은 구현하지 않았습니다.

공개 자료의 제한된 검토 규칙과 테스트용 발췌 fixture는 코드 검증을 위해 포함합니다. 검토한 기업·기간 밖으로 승인 범위를 확장하지 않습니다. demo 데이터는 가상 자료이며 실제 기업의 실적으로 해석하면 안 됩니다.

## 기술 스택

- Java 21, Spring Boot 3.5.16, Gradle Wrapper
- Spring Web, Validation, Actuator, Spring Data JPA
- Spring Batch, JDBC batch, PostgreSQL 16, Docker Compose
- Flyway: 스키마 변경 이력을 관리하며 Hibernate는 validate만 수행
- jsoup: 공시 원문의 HTML/XML 구조 파싱
- JUnit 5, Mockito, Testcontainers: PostgreSQL 고유 동작을 실제 DB에서 검증

## 구조와 데이터 흐름

```text
OpenDART → 외부 DTO / 원본 응답 → 정제 / 표준 계정 매핑
        → Spring Batch → JDBC batch → PostgreSQL
        → JPA 조회 → REST 응답 DTO / 재무 지표

공식 공시목록 + 보고서 원문 → 버전별 검토 값 → 기준 날짜 조회
KRX 가격 원본 → staging / 근거 검토 → 데이터셋 준비 상태 점검
```

기능별 패키지를 사용합니다. 계층별로 모든 클래스를 한곳에 모으는 대신 관련 업무 코드를 함께 읽을 수 있습니다.

| 패키지 | 역할 |
| --- | --- |
| company, disclosure | 기업·공시 모델과 조회 |
| external | OpenDART 외부 응답과 클라이언트 |
| financial | 재무 사실, 계정 정제, 버전별 조회 |
| ingestion, batch | 원본 보존, JDBC 적재, Job·스케줄 구성 |
| analysis | 지표 계산 및 비교 가능성 판단 |
| market, dataset | 가격 staging, 검토 근거와 데이터셋 점검 |
| operations | 로컬 운영·점검 명령 |
| common | 공통 예외와 페이지 응답 |

## DB 및 주요 설계

- company, financial_fact, disclosure: 기업과 기간별 계정 금액, 공시를 분리합니다.
- raw_ingestion: 원본 응답을 보존해 정제 규칙 변경 후 재검토할 수 있습니다.
- 보고서 공개 목록·문서·검토 버전과 재무 사실을 분리합니다. 최신 정정값을 과거 공개일에 붙이지 않습니다.
- 가격 staging은 학습 승인 데이터와 다릅니다. 수집 시각과 과거 공개 시각도 구분합니다.
- 기업 코드와 접수번호는 앞자리 0을 보존하는 문자열입니다. 금액은 BigDecimal이며 누락은 0으로 채우지 않습니다.
- UNIQUE 제약과 트랜잭션으로 중복·부분 적재를 방지합니다. 조회에는 JPA, 대량 쓰기에는 JDBC batch를 사용합니다.
- Batch는 제한된 chunk, 영속 실행 이력 및 checkpoint를 유지합니다. 일시적 장애만 재시도하며 인증·정제 오류를 무시하지 않습니다.
- 최신 보고서가 미검토이면 이전 승인본으로 몰래 후퇴하지 않습니다. 분석 불가 사유와 null을 반환합니다.
- 기간·기업 중심의 복합 인덱스를 사용합니다. 추가 인덱스, cursor pagination, 집계 테이블, 파티셔닝은 실제 데이터의 EXPLAIN (ANALYZE, BUFFERS) 결과로 판단합니다. 소규모 결과를 수백만 행 성능 보증으로 해석하지 않습니다.

실제 스키마는 `src/main/resources/db/migration`에서 확인할 수 있습니다. 적용한 Flyway 파일은 수정하지 않고 새 버전을 추가합니다.

## 실행 방법 (Windows PowerShell)

Java 21과 Docker Desktop이 필요합니다. JAVA_HOME이 Java 21 설치 폴더를 가리키도록 설정합니다.

```powershell
Copy-Item .env.example .env
# .env의 DB_PASSWORD를 로컬 비밀번호로 변경
# 실제 수집 시 OPENDART_API_KEY 입력; 키를 커밋하지 마세요.
docker compose up -d --wait
. ./scripts/Load-LocalEnv.ps1
./gradlew.bat build
./gradlew.bat integrationTest
./gradlew.bat bootRun
```

Compose는 `.env`를 읽지만 Spring은 자동으로 읽지 않으므로 같은 PowerShell에서 환경 로더를 실행합니다. DB_NAME, DB_USERNAME, DB_PASSWORD, DB_PORT와 선택적으로 DB_URL을 설정할 수 있습니다. KRX_API_KEY는 OpenDART와 별개이며 서비스 이용 승인도 필요합니다. 기본 서버 주소는 http://127.0.0.1:8080 입니다. 키 없이 서버를 기동하고 demo 적재를 실행할 수 있습니다.

`build`의 기본 test는 단위 테스트입니다. `integrationTest`는 Docker의 PostgreSQL Testcontainers를 사용합니다. 성능 측정 태그는 별도 실행 대상으로 기본 build에 포함되지 않습니다. Docker volume을 삭제하면 로컬 데이터가 사라지므로 초기화 목적으로 임의 삭제하지 마세요.

## Batch 실행

빌드 후 별도 PowerShell에서 실행합니다. 서버와 동시 실행할 필요는 없습니다.

```powershell
# 가상 기업 00000001, API 키 없이 적재
./scripts/Import-Financials.ps1 -Demo -FiscalYear 2024 -RequestId demo-2024-1
# 실제 삼성전자: OpenDART 키 필요
./scripts/Import-Financials.ps1 -CorpCodes 00126380 -FiscalYear 2024 -ReportType ANNUAL -RequestId samsung-2024-1
./scripts/Get-BatchStatus.ps1
```

실패한 Job 재시작은 같은 identifying parameters를 사용합니다. 완료한 Job을 다시 수집할 때는 새로운 RequestId를 사용합니다. 보고서 유형은 Q1, HALF_YEAR, Q3, ANNUAL, 연결/별도는 CFS/OFS입니다. 기본 자동 Job 실행과 스케줄은 비활성화되어 있습니다.

## REST API

먼저 기업 검색으로 DB 내부 id를 확인합니다. URL의 id는 OpenDART corp_code와 다릅니다.

```text
GET /actuator/health
GET /api/companies/search?name=삼성
GET /api/companies/{id}
GET /api/companies/{id}/financials?fiscalYear=2024&reportType=ANNUAL&statementType=CFS
GET /api/companies/{id}/disclosures
GET /api/companies/{id}/analysis?fiscalYear=2024&reportType=ANNUAL&statementType=CFS
```

페이지 조회는 page, size를 받으며 size는 최대 100입니다. Entity를 직접 반환하지 않습니다. 매출 성장률, 영업이익률, 부채비율, ROA/ROE는 계산 로직과 조회를 분리하고, 분모·비교 기준·누락 상태를 검사합니다. 과거 기준일 조회와 분기 시범 API도 포함하지만 검토 자료가 없는 새 DB에서 모든 기간의 조회가 가능하다는 뜻은 아닙니다.

## 검증과 운영 제한

단위 테스트는 계산·변환·예외 정책을 검증하고, PostgreSQL 통합 테스트는 저장·중복·rollback·Batch 재시작 및 시점별 버전 선택을 검증합니다. 실 API 성공이나 모든 기업의 회계 기준 검증을 테스트 성공으로 대신하지 않습니다. 기본 로컬 바인딩을 유지하며 인터넷 공개 서비스로 배포하기 전 인증·접근제어를 별도로 설계해야 합니다.

## 다음 개발 단계

1. 실제 수집 범위와 버전별 검토 근거 확대
2. 대량 초기 적재와 JDBC 처리량·재시작 비용 측정
3. 표준 계정 매핑과 기간 비교 정책 확장
4. 조회 API·인덱스·실행계획 및 수백만 행 성능 실험
5. 시간 순서를 보존한 가격 전용/가격+재무 모델 비교의 별도 공개 패키지 정리
6. 검증된 예측 결과를 설명하는 LLM 리포트 검토

모델 실험은 최근값·Linear Regression·LightGBM을 동일 표본과 시간 경계에서 비교하는 방향입니다. 테스트 기간을 본 뒤 설정을 바꾸면 새 미사용 평가로 주장하지 않습니다. LLM은 예측값을 만드는 역할이 아니라 검증된 숫자를 설명하는 역할로 제한합니다.

## 출처

기업·재무 원천: [금융감독원 OpenDART](https://opendart.fss.or.kr/), 가격 원천: [KRX OPEN API](https://openapi.krx.co.kr/). 원천 자료의 이용 조건은 각 제공기관의 조건을 따릅니다.

Spring Batch 메타데이터 DDL은 [Spring Batch PostgreSQL schema (Apache 2.0)](https://github.com/spring-projects/spring-batch/blob/v5.2.6/spring-batch-core/src/main/resources/org/springframework/batch/core/schema-postgresql.sql)를 기반으로 합니다.

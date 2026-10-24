import unittest

from compare_financial_price_baselines import FINANCIAL, join_rows


def financial(date, available=None, missing=None):
    values = {name: "1.0000" for name in FINANCIAL}
    if missing:
        values[missing] = None
    return {"stockCode": "005930", "decisionDate": date, "availableFrom": available or date,
            "fiscalYear": 2024, "receiptNumber": "20250301000001", "metrics": values}


def panel(rows):
    return {"status": "EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED", "forecastReady": False,
            "requested": len(rows), "exported": len(rows), "withheldCount": 0, "rows": rows,
            "withheld": []}


class FinancialJoinTest(unittest.TestCase):
    def test_future_or_duplicate_financial_evidence_is_rejected(self):
        row = {"stock": "005930", "decision_date": "2025-01-01"}
        with self.assertRaisesRegex(ValueError, "future"):
            join_rows(([row], [], []), panel([financial("2025-01-01", "2025-01-02")]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            join_rows(([row], [], []), panel([financial("2025-01-01"), financial("2025-01-01")]))

    def test_missing_financial_metric_excludes_row_without_zero_filling(self):
        rows = [{"stock": "005930", "decision_date": date, "features": [2.0]}
                for date in ("2025-01-01", "2025-01-02")]
        result = join_rows((rows, [], []), panel([financial("2025-01-01", missing="roe"),
                                                   financial("2025-01-02")]))
        self.assertEqual(1, len(result[0]))
        self.assertEqual("2025-01-02", result[0][0]["decision_date"])
        self.assertEqual([1.0] * 5, result[0][0]["financial_features"])

    def test_missing_panel_date_fails_instead_of_silently_changing_cohort(self):
        row = {"stock": "005930", "decision_date": "2025-01-01"}
        with self.assertRaisesRegex(ValueError, "does not match"):
            join_rows(([row], [], []), panel([]))


if __name__ == "__main__":
    unittest.main()

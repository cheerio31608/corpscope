import unittest
from datetime import date, timedelta

from build_split_aware_price_returns import STOCKS
from compare_ten_year_split_safe_baselines import build_rows, chronological_fold


class TenYearSplitSafeBaselinesTest(unittest.TestCase):
    def sample_days(self, with_unknown_share_change=False):
        days = []
        first = date(2018, 1, 1)
        for index in range(100):
            current = (first + timedelta(days=index)).isoformat()
            rows = {}
            for stock in STOCKS:
                close = 1000 if stock == "005930" and index < 40 else 20 if stock == "005930" else 100
                shares = 5000 if stock == "005930" and index >= 40 else 100
                if with_unknown_share_change and stock == "035420" and index >= 30:
                    shares = 90
                rows[stock] = {"TDD_CLSPRC": str(close), "TDD_OPNPRC": str(close),
                               "ACC_TRDVOL": "100", "LIST_SHRS": str(shares)}
            days.append((current, rows))
        return days

    def test_split_inside_label_is_corrected_without_changing_pre_split_features(self):
        days = self.sample_days()
        event = {"date": days[40][0], "stockCode": "005930", "newSharesPerOldShare": 50}
        rows, counts, unknown = build_rows(days, [event])
        samsung = next(item for item in rows if item["stock"] == "005930" and item["decision_date"] == days[22][0])
        self.assertEqual(50, samsung["split_factor_in_label"])
        self.assertEqual(0, samsung["target_pct"])
        self.assertEqual(-98, samsung["raw_target_pct"])
        self.assertEqual([0.0, 0.0, 0.0, 0.0, 1.0], samsung["features"][:5])
        self.assertGreater(counts["labelsCrossingVerifiedSplit"], 0)
        self.assertEqual([], unknown)

    def test_unclassified_share_change_blocks_affected_windows(self):
        days = self.sample_days(with_unknown_share_change=True)
        event = {"date": days[40][0], "stockCode": "005930", "newSharesPerOldShare": 50}
        rows, counts, unknown = build_rows(days, [event])
        self.assertEqual(1, len(unknown))
        self.assertEqual("035420", unknown[0]["stockCode"])
        self.assertGreater(counts["heldUnclassifiedListedShareChange"], 0)
        self.assertFalse(any(item["stock"] == "035420" and item["decision_date"] == days[22][0]
                             for item in rows))

    def test_future_exit_price_changes_label_but_not_past_features(self):
        days = self.sample_days()
        event = {"date": days[40][0], "stockCode": "005930", "newSharesPerOldShare": 50}
        original, _, _ = build_rows(days, [event])
        before = next(item for item in original if item["stock"] == "005930"
                      and item["decision_date"] == days[22][0])
        days[84][1]["005930"]["TDD_CLSPRC"] = "22"
        changed, _, _ = build_rows(days, [event])
        after = next(item for item in changed if item["stock"] == "005930"
                     and item["decision_date"] == days[22][0])
        self.assertEqual(before["features"], after["features"])
        self.assertEqual(0, before["target_pct"])
        self.assertAlmostEqual(10, after["target_pct"])

    def test_chronological_fold_uses_label_availability(self):
        def row(decision, available):
            return {"decision_date": decision, "label_available_date": available}
        rows = [row("2023-01-01", "2023-04-01"), row("2023-12-01", "2024-03-01"),
                row("2024-01-01", "2024-04-01"), row("2024-12-01", "2025-03-01"),
                row("2025-01-01", "2025-04-01")]
        train, validation, test = chronological_fold(rows, 2025)
        self.assertEqual([rows[0]], train)
        self.assertEqual([rows[2]], validation)
        self.assertEqual([rows[4]], test)


if __name__ == "__main__":
    unittest.main()

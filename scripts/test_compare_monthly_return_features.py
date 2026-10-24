import unittest
from datetime import date, timedelta

from build_split_aware_price_returns import STOCKS
from cancellation_event_features import asof_features, verify_listing_changes
from compare_monthly_cancellation_follow_on import restrict_test_origins
from compare_monthly_return_features import build_rows, training_window


class MonthlyReturnFeaturesTest(unittest.TestCase):
    def days(self):
        result = []
        current = date(2018, 7, 1)
        while current <= date(2019, 4, 30):
            result.append((current.isoformat(), {
                stock: {"TDD_CLSPRC": "100", "ACC_TRDVOL": "1000", "LIST_SHRS": "1000"}
                for stock in STOCKS
            }))
            current += timedelta(days=1)
        return result

    def row(self, rows):
        return next(row for row in rows if row["stock"] == STOCKS[0]
                    and row["origin_month"] == "2019-01")

    def test_target_month_changes_label_but_not_origin_features(self):
        days = self.days()
        before = self.row(build_rows(days)[0])
        for day, stocks in days:
            if day.startswith("2019-02"):
                stocks[STOCKS[0]]["TDD_CLSPRC"] = "110"
        after = self.row(build_rows(days)[0])
        self.assertEqual(before["features"], after["features"])
        self.assertEqual("2019-02-01", after["decision_date"])
        self.assertEqual("2019-03-01", after["label_available_date"])
        self.assertEqual(0, before["target_pct"])
        self.assertAlmostEqual(10, after["target_pct"])

    def test_later_share_change_excludes_affected_stock_only(self):
        days = self.days()
        for day, stocks in days:
            if day >= "2019-02-15":
                stocks[STOCKS[0]]["LIST_SHRS"] = "900"
        rows, held = build_rows(days)
        self.assertFalse(any(row["stock"] == STOCKS[0] and row["origin_month"] == "2019-01"
                             for row in rows))
        self.assertTrue(any(row["stock"] == STOCKS[1] and row["origin_month"] == "2019-01"
                            for row in rows))
        self.assertGreater(held["listedShareChange"], 0)

    def test_training_window_requires_matured_label(self):
        rows = [
            {"decision_date": "2021-12-01", "label_available_date": "2022-01-03",
             "origin_month": "2021-11"},
            {"decision_date": "2021-11-01", "label_available_date": "2021-12-01",
             "origin_month": "2021-10"},
            {"decision_date": "2018-01-02", "label_available_date": "2018-03-01",
             "origin_month": "2017-12"},
        ]
        self.assertEqual(2, len(training_window(rows, "2022-01-01", "expanding")))
        self.assertEqual([rows[1]], training_window(rows, "2022-01-01", "trailing_36"))

    def test_reviewed_cancellation_restores_row_with_only_announced_features(self):
        days = self.days()
        for day, stocks in days:
            if day >= "2019-02-15":
                stocks[STOCKS[0]]["LIST_SHRS"] = "900"
        event = {"stockCode": STOCKS[0], "announcementDate": "2019-01-15",
                 "availableFromDate": "2019-01-16", "plannedCommonShares": 100,
                 "observedListingDate": "2019-02-15", "observedShareDelta": -100}
        rows, _ = build_rows(days, [event])
        row = self.row(rows)
        self.assertEqual(17, len(row["features"]))
        self.assertEqual([1.0, 0.1, 16.0], row["features"][-3:])
        self.assertEqual("2019-03-01", row["label_available_date"])

    def test_unannounced_future_cancellation_has_no_model_signal(self):
        event = {"stockCode": STOCKS[0], "availableFromDate": "2019-02-10",
                 "plannedCommonShares": 100, "observedListingDate": "2019-02-15"}
        self.assertEqual([0.0, 0.0, 0.0],
                         asof_features(STOCKS[0], "2019-02-01", 1000, [event]))
        event["observedListingDate"] = "2019-03-15"
        self.assertEqual([0.0, 0.0, 0.0],
                         asof_features(STOCKS[0], "2019-02-01", 1000, [event]))

    def test_later_correction_changes_only_later_feature_value(self):
        event = {"stockCode": STOCKS[0], "availableFromDate": "2019-01-16",
                 "plannedCommonShares": 100,
                 "versions": [{"availableFromDate": "2019-02-11", "plannedCommonShares": 80}]}
        before = asof_features(STOCKS[0], "2019-02-01", 1000, [event])
        event["versions"][0]["plannedCommonShares"] = 70
        self.assertEqual(before, asof_features(STOCKS[0], "2019-02-01", 1000, [event]))
        self.assertEqual(0.07, asof_features(STOCKS[0], "2019-03-01", 1000, [event])[1])

    def test_two_disclosures_can_match_one_listed_share_change(self):
        days = self.days()
        for day, stocks in days:
            if day >= "2019-02-15":
                stocks[STOCKS[0]]["LIST_SHRS"] = "900"
        events = [{"stockCode": STOCKS[0], "observedListingDate": "2019-02-15",
                   "observedShareDelta": -40},
                  {"stockCode": STOCKS[0], "observedListingDate": "2019-02-15",
                   "observedShareDelta": -60}]
        self.assertEqual({(STOCKS[0], "2019-02-15")}, verify_listing_changes(days, events))

    def test_announced_cancellation_without_observed_reduction_is_known_asof(self):
        event = {"stockCode": STOCKS[0], "availableFromDate": "2026-08-20",
                 "plannedCommonShares": 240, "observedListingDate": None,
                 "observedShareDelta": None}
        self.assertEqual(set(), verify_listing_changes(self.days(), [event]))
        self.assertEqual([0.0, 0.0, 0.0],
                         asof_features(STOCKS[0], "2026-08-19", 1000, [event]))
        self.assertEqual([1.0, 0.24, 12.0],
                         asof_features(STOCKS[0], "2026-09-01", 1000, [event]))

    def test_follow_on_excludes_december_origin_decided_in_january(self):
        rows = [{"origin_month": "2025-12", "decision_date": "2026-01-02"},
                {"origin_month": "2026-01", "decision_date": "2026-02-02"}]
        self.assertEqual(rows[1:], restrict_test_origins(rows, ["2026-01"]))


if __name__ == "__main__":
    unittest.main()

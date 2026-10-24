import unittest
from datetime import date, timedelta
from unittest.mock import patch

from forecast_month_end_retrospective import CUTOFF, asof_days, features_at, month_plus, training_rows


STOCK = "005930"


def synthetic_days(count=190):
    first = date(2025, 1, 1)
    return [((first + timedelta(days=index)).isoformat(), {
        STOCK: {"TDD_CLSPRC": str(1000 + index), "ACC_TRDVOL": "10000"}
    }, f"hash-{index}") for index in range(count)]


class MonthEndRetrospectiveTest(unittest.TestCase):
    def test_prediction_loader_never_requests_post_cutoff_archive(self):
        days = [(CUTOFF, {}, "hash")]
        with patch("forecast_month_end_retrospective.load_archive", return_value=days) as loader:
            self.assertEqual(days, asof_days("archive", (STOCK,)))
        loader.assert_called_once_with("archive", "2023-01-01", CUTOFF, (STOCK,))

    def test_training_labels_end_within_supplied_asof_history(self):
        days = synthetic_days()
        for horizon in (1, 2, 3):
            rows = training_rows(days, (STOCK,), horizon)
            self.assertTrue(rows)
            self.assertTrue(all(row["targetDate"] <= days[-1][0] for row in rows))
            self.assertTrue(all(row["targetDate"][:7] == month_plus(row["originDate"][:7], horizon)
                                for row in rows))
        before = features_at(days[:151], (STOCK,), 150)[STOCK]
        changed = synthetic_days()
        changed[170][1][STOCK]["TDD_CLSPRC"] = "999999"
        self.assertEqual(before, features_at(changed[:151], (STOCK,), 150)[STOCK])


if __name__ == "__main__":
    unittest.main()

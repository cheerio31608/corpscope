import unittest
from decimal import Decimal as D

from build_split_aware_price_returns import STOCKS
from compare_month_end_close_baseline import raw_monthly, score


class MonthEndCloseBaselineTest(unittest.TestCase):
    def test_last_observed_close_predicts_next_month_mean(self):
        def prices(close):
            return {stock: {"TDD_CLSPRC": str(close)} for stock in STOCKS}

        months = raw_monthly([("2024-01-02", prices(100)), ("2024-01-31", prices(120)),
                              ("2024-02-01", prices(120)), ("2024-02-28", prices(120))])
        origin, target = months[(STOCKS[0], "2024-01")], months[(STOCKS[0], "2024-02")]
        predicted = (origin["last"] / origin["mean"] - 1) * 100
        actual = (target["mean"] / origin["mean"] - 1) * 100
        row = {"actualPct": actual,
               "predictions": {"previousMean0Pct": D(0), "originLastClose": predicted,
                               "priceSelected": D(0), "priceAndDisclosureSelected": D(0)}}
        self.assertEqual(D(110), origin["mean"])
        self.assertEqual(D(0), score([row])["originLastClose"])
        self.assertGreater(score([row])["previousMean0Pct"], 9)


if __name__ == "__main__":
    unittest.main()

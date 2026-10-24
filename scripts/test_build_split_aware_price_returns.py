import hashlib
import json
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

from build_split_aware_price_returns import STOCKS, derive, interval_close_return


class SplitAwarePriceReturnsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=".reports")
        self.root = Path(self.temporary.name)
        self.archive = self.root / "archive"
        self.archive.mkdir()
        self.events = self.root / "events.json"
        self.panel = self.root / "panel.jsonl"
        self.audit = self.root / "audit.json"
        self.events.write_text(json.dumps({"events": [{
            "stockCode": "005930", "firstPostSplitPriceDate": "2018-05-04",
            "lastPreSplitPositiveOhlcDate": "2018-04-27", "newSharesPerOldShare": 50,
            "expectedPreCloseKrw": 2650000, "expectedPostCloseKrw": 51900,
        }]}), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def add_day(self, day, samsung_close, samsung_open=None, naver_close=100):
        rows = []
        for stock in STOCKS:
            close = samsung_close if stock == "005930" else naver_close if stock == "035420" else 100
            open_ = samsung_open if stock == "005930" and samsung_open is not None else close
            rows.append({"ISU_CD": stock, "TDD_OPNPRC": str(open_), "TDD_CLSPRC": str(close)})
        payload = json.dumps({"OutBlock_1": rows}).encode()
        digest = hashlib.sha256(payload).hexdigest()
        path = self.archive / f"stk_bydd_trd-{day}-{digest}.json"
        path.write_bytes(payload)
        path.with_name(path.name + ".metadata.json").write_text(json.dumps({
            "source": "KRX", "api": "stk_bydd_trd", "requestedDate": day,
            "sha256": digest, "rows": len(rows),
        }), encoding="utf-8")
        return path

    def test_only_verified_boundary_adjusts_and_suspended_rows_do_not_advance_price(self):
        self.add_day("20180426", 2600000)
        self.add_day("20180427", 2650000)
        self.add_day("20180430", 2650000, samsung_open=0)
        self.add_day("20180504", 51900)
        result = derive(self.archive, self.events, self.panel, self.audit, require_complete=False)
        rows = [json.loads(line) for line in self.panel.read_text(encoding="utf-8").splitlines()]
        samsung = [row for row in rows if row["stockCode"] == "005930"]
        self.assertEqual("RAW_CLOSE_CHANGE_NOT_FULLY_ACTION_REVIEWED", samsung[1]["status"])
        self.assertEqual("NO_POSITIVE_OPEN_CLOSE", samsung[2]["status"])
        self.assertIsNone(samsung[2]["splitAwareCloseChangePct"])
        self.assertEqual("2018-04-27", samsung[3]["previousPositiveDate"])
        self.assertEqual("VERIFIED_SPLIT_BOUNDARY", samsung[3]["status"])
        self.assertEqual("-98.041509", samsung[3]["rawCloseChangePct"])
        self.assertEqual("-2.075472", samsung[3]["splitAwareCloseChangePct"])
        self.assertEqual(50, samsung[3]["appliedNewSharesPerOldShare"])
        self.assertEqual("-0.192308", format(interval_close_return(
            self.panel, "005930", "2018-04-26", "2018-05-04").quantize(Decimal("0.000001")), "f"))
        self.assertFalse(result["forecastReady"])
        self.assertEqual(1, len(result["verifiedSplitBoundaries"]))
        self.assertEqual(0, result["counts"]["unreviewedLargeRawChanges"])

    def test_changed_split_price_fails_instead_of_silently_adjusting(self):
        self.add_day("20180427", 2650000)
        self.add_day("20180504", 52000)
        with self.assertRaisesRegex(ValueError, "boundary no longer matches"):
            derive(self.archive, self.events, self.panel, self.audit, require_complete=False)
        self.assertFalse(self.panel.exists())

    def test_unreviewed_large_jump_is_withheld_and_hash_change_fails(self):
        first = self.add_day("20180427", 2650000, naver_close=100)
        self.add_day("20180504", 51900, naver_close=20)
        result = derive(self.archive, self.events, self.panel, self.audit, require_complete=False)
        rows = [json.loads(line) for line in self.panel.read_text(encoding="utf-8").splitlines()]
        naver = next(row for row in rows if row["stockCode"] == "035420" and row["date"] == "2018-05-04")
        self.assertEqual("UNREVIEWED_LARGE_RAW_CHANGE", naver["status"])
        self.assertIsNone(naver["splitAwareCloseChangePct"])
        self.assertEqual(1, result["counts"]["unreviewedLargeRawChanges"])
        first.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            derive(self.archive, self.events, self.panel, self.audit, require_complete=False)


if __name__ == "__main__":
    unittest.main()

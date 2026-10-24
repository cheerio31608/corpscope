import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from audit_ten_year_price_history import CANDIDATES, audit


class TenYearCoverageTest(unittest.TestCase):
    def test_single_2016_market_day_cannot_approve_ten_year_history(self):
        with tempfile.TemporaryDirectory(dir=".reports") as temp:
            folder = Path(temp)
            day = "20160104"
            rows = [{"ISU_CD": code, "TDD_OPNPRC": "100", "TDD_CLSPRC": "101"}
                    for code in CANDIDATES if code not in ("329180", "373220")]
            payload = json.dumps({"OutBlock_1": rows}).encode()
            digest = hashlib.sha256(payload).hexdigest()
            path = folder / f"stk_bydd_trd-{day}-{digest}.json"
            path.write_bytes(payload)
            path.with_name(path.name + ".metadata.json").write_text(json.dumps({
                "source": "KRX", "api": "stk_bydd_trd", "requestedDate": day,
                "sha256": digest, "rows": len(rows),
            }))
            result = audit(folder)
            self.assertFalse(result["archiveCalendarComplete"])
            self.assertEqual(365, result["yearly"]["2016"]["missingArchiveDays"])
            self.assertEqual(1, result["yearly"]["2016"]["candidates"]["005930"]["positiveCloseDates"])
            self.assertFalse(result["companies"]["005930"]["has2016Through2025RawCoverage"])
            self.assertEqual(0, result["yearly"]["2016"]["candidates"]["329180"]["observedDates"])
            path.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                audit(folder)


if __name__ == "__main__":
    unittest.main()

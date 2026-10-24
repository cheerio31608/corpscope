import unittest
import hashlib
import json
import tempfile
from datetime import date, timedelta
from pathlib import Path

from compare_price_baselines import STOCKS, build_rows, load_archive, split_rows


def synthetic_days(count=300):
    result = []
    for index in range(count):
        day = (date(2024, 1, 1) + timedelta(days=index)).isoformat()
        rows = {stock: {"TDD_OPNPRC": str(1000 + index), "TDD_CLSPRC": str(1001 + index),
                        "ACC_TRDVOL": str(10000 + index)} for stock in STOCKS}
        result.append((day, rows, "synthetic"))
    return result


class BaselineDatasetTest(unittest.TestCase):
    def test_nonpositive_open_is_rejected_unless_monthly_cohort_explicitly_skips_it(self):
        with tempfile.TemporaryDirectory(dir=".reports") as temp:
            folder = Path(temp)
            for day, suspended in (("20180502", True), ("20180504", False)):
                rows = [{"ISU_CD": stock, "TDD_OPNPRC": "0" if suspended and stock == STOCKS[0] else "100",
                         "TDD_CLSPRC": "101", "ACC_TRDVOL": "1"} for stock in STOCKS]
                payload = json.dumps({"OutBlock_1": rows}).encode()
                digest = hashlib.sha256(payload).hexdigest()
                path = folder / f"stk_bydd_trd-{day}-{digest}.json"
                path.write_bytes(payload)
                path.with_name(path.name + ".metadata.json").write_text(json.dumps({
                    "source": "KRX", "requestedDate": day, "sha256": digest,
                    "rows": len(rows), "fetchedAt": "2026-10-22T00:00:00+09:00",
                }))
            with self.assertRaisesRegex(ValueError, "Nonpositive open/close"):
                load_archive(folder, "2018-05-02", "2018-05-04", STOCKS)
            kept = load_archive(folder, "2018-05-02", "2018-05-04", STOCKS,
                                skip_nonpositive=True)
            self.assertEqual(["2018-05-04"], [day[0] for day in kept])

    def test_latest_empty_response_does_not_use_older_price(self):
        with tempfile.TemporaryDirectory(dir=".reports") as temp:
            folder = Path(temp)

            def save(rows, hour):
                payload = json.dumps({"OutBlock_1": rows}).encode()
                digest = hashlib.sha256(payload).hexdigest()
                name = f"stk_bydd_trd-20261020-{digest}.json"
                (folder / name).write_bytes(payload)
                (folder / (name + ".metadata.json")).write_text(json.dumps({
                    "source": "KRX", "requestedDate": "20261020", "sha256": digest,
                    "rows": len(rows), "fetchedAt": f"2026-10-21T{hour}:00:00+09:00",
                }))
                (folder / f"observation-stk_bydd_trd-20261020-{hour}.json").write_text(json.dumps({
                    "source": "KRX", "api": "stk_bydd_trd", "requestedDate": "20261020",
                    "sha256": digest, "file": name, "rows": len(rows),
                    "observedAt": f"2026-10-21T{hour}:00:00+09:00",
                }))

            stock_rows = [{"ISU_CD": code, "TDD_OPNPRC": "100", "TDD_CLSPRC": "101"}
                          for code in STOCKS]
            save([], "02")
            save(stock_rows, "09")
            self.assertEqual(1, len(load_archive(folder, "2026-10-20", "2026-10-20")))
            save([], "10")
            self.assertEqual([], load_archive(folder, "2026-10-20", "2026-10-20"))

    def test_future_price_change_cannot_change_past_features(self):
        original = synthetic_days()
        before = next(row for row in build_rows(original) if row["stock"] == STOCKS[0] and row["entry_date"] == original[40][0])
        changed = synthetic_days()
        changed[102][1][STOCKS[0]]["TDD_CLSPRC"] = "9999"  # the 63rd observation, known only later
        after = next(row for row in build_rows(changed) if row["stock"] == STOCKS[0] and row["entry_date"] == changed[40][0])
        self.assertEqual(before["features"], after["features"])
        self.assertNotEqual(before["target_pct"], after["target_pct"])
        self.assertEqual(original[38][0], before["last_feature_date"])

    def test_label_availability_purges_boundary_overlap(self):
        def row(decision, available):
            return {"decision_date": decision, "label_available_date": available}

        train, validation, test = split_rows([
            row("2024-07-01", "2024-11-29"),
            row("2024-08-01", "2024-12-02"),  # not known at validation 00:00
            row("2025-01-02", "2025-06-30"),
            row("2025-02-01", "2025-07-01"),  # not known at test 00:00
            row("2025-07-02", "2025-10-01"),
        ])
        self.assertEqual((1, 1, 1), (len(train), len(validation), len(test)))

    def test_q4_holdout_purges_validation_labels_at_october_boundary(self):
        def row(decision, available):
            return {"decision_date": decision, "label_available_date": available}

        train, validation, test = split_rows([
            row("2024-08-30", "2024-12-05"),
            row("2025-06-30", "2025-09-26"),
            row("2025-07-01", "2025-10-01"),
            row("2025-10-01", "2025-12-30"),
        ], "2025_q4_holdout")
        self.assertEqual((1, 1, 1), (len(train), len(validation), len(test)))


if __name__ == "__main__":
    unittest.main()

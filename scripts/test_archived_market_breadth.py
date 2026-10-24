import unittest

from archived_market_breadth import augment, daily_breadth, rolling_features


def quote(close, shares=100, volume=10):
    return {'TDD_CLSPRC': str(close), 'LIST_SHRS': str(shares), 'ACC_TRDVOL': str(volume)}


class ArchivedBreadthTest(unittest.TestCase):
    def test_contemporaneous_pairs_exclude_analysis_and_share_changes(self):
        previous = {'a': quote(100), 'b': quote(100), 'split': quote(100),
                    'self': quote(100), 'exit': quote(100), 'stale': quote(100)}
        current = {'a': quote(110), 'b': quote(90), 'split': quote(50, 200),
                   'self': quote(200), 'new': quote(999), 'stale': quote(500, volume=0)}
        result = daily_breadth(previous, current, excluded=['self'], minimum=2)
        self.assertEqual(result['pairedSecurities'], 2)
        self.assertEqual(result['medianReturn'], 0)
        self.assertEqual(result['advancingFraction'], .5)

    def test_future_changes_do_not_change_past_features(self):
        daily = [(f'2025-01-{i:02d}', {'medianReturn': i / 100, 'advancingFraction': .5})
                 for i in range(1, 6)]
        before = rolling_features(daily, 3)
        daily[-1] = ('2025-01-05', {'medianReturn': -100, 'advancingFraction': 0})
        after = rolling_features(daily, 3)
        self.assertEqual(before['2025-01-04'], after['2025-01-04'])
        self.assertAlmostEqual(before['2025-01-03'][0], .02)

    def test_join_uses_origin_not_target_and_preserves_row(self):
        row = {'origin_date': '2025-01-31', 'decision_date': '2025-02-03', 'features': [1, 2]}
        result = augment([row], {'2025-01-31': [.1, .5], '2025-02-28': [99, 99]})
        self.assertEqual(result[0]['features'], [1, 2, .1, .5])
        self.assertEqual(row['features'], [1, 2])

    def test_missing_or_future_cutoff_fails_instead_of_dropping_rows(self):
        with self.assertRaises(ValueError):
            augment([{'origin_date': '2025-01-31', 'decision_date': '2025-02-03'}], {})
        with self.assertRaises(ValueError):
            augment([{'origin_date': '2025-02-03', 'decision_date': '2025-02-03'}], {})

    def test_insufficient_cross_section_fails(self):
        with self.assertRaises(ValueError):
            daily_breadth({'a': quote(100)}, {'a': quote(100)}, excluded=[], minimum=2)


if __name__ == '__main__':
    unittest.main()

import unittest
from unittest.mock import patch

from compare_three_year_selection import annual_validation, select_three_years


class ThreeYearSelectionTest(unittest.TestCase):
    def test_single_good_year_does_not_dominate_by_recency(self):
        annual = [{'maePctPoints': {'recent_0pct': 5, 'model': x}} for x in (10, 10, 1)]
        selected, summary = select_three_years(annual, ['recent_0pct', 'model'])
        self.assertEqual(selected, 'recent_0pct')
        self.assertEqual(summary['model']['winsAgainstBaseline'], 1)
        self.assertEqual(summary['model']['meanMaePctPoints'], 7)

    def test_repeated_improvement_and_tie(self):
        annual = [{'maePctPoints': {'recent_0pct': 5, 'model': 4}} for _ in range(3)]
        selected, summary = select_three_years(annual, ['recent_0pct', 'model'])
        self.assertEqual(selected, 'model')
        self.assertEqual(summary['model']['winsAgainstBaseline'], 3)
        for item in annual:
            item['maePctPoints']['model'] = 5
        self.assertEqual(select_three_years(annual, ['recent_0pct', 'model'])[0], 'recent_0pct')

    def test_training_and_validation_label_boundaries(self):
        def row(decision, label):
            return {'decision_date': decision, 'label_available_date': label,
                    'origin_month': decision[:7], 'features': [0, 0, 0, 0],
                    'mean_target_pct': 0, 'target_pct': 0}
        earlier = row('2021-10-01', '2021-12-01')
        boundary = row('2021-12-01', '2022-01-01')
        valid = row('2022-06-01', '2022-08-01')
        late = row('2022-12-01', '2023-01-01')
        future = row('2023-05-01', '2023-07-01')
        with patch('compare_three_year_selection.candidate_predictions', return_value=[0]) as predict:
            result = annual_validation([earlier, boundary, valid, late, future], 2022,
                                       {'candidateOrder': ['recent_0pct']})
        self.assertEqual(predict.call_args.args[1], [earlier])
        self.assertEqual(predict.call_args.args[2], [valid])
        self.assertEqual(result['rows'], 1)

    def test_requires_three_finite_scores(self):
        with self.assertRaises(ValueError):
            select_three_years([], ['recent_0pct'])
        with self.assertRaises(ValueError):
            select_three_years([{'maePctPoints': {'recent_0pct': float('nan')}}] * 3, ['recent_0pct'])


if __name__ == '__main__':
    unittest.main()

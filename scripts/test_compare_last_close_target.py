import unittest
from unittest.mock import patch

from compare_last_close_target import retarget, select_candidate, to_last_close_target, to_mean_return


class LastCloseTargetTest(unittest.TestCase):
    def test_known_prices_and_roundtrip(self):
        # Origin mean 100, last close 120, next mean 132: old target32%, new10%.
        self.assertAlmostEqual(to_last_close_target(32, 1.2), 10)
        self.assertAlmostEqual(to_mean_return(10, 1.2), 32)
        self.assertAlmostEqual(to_mean_return(0, 1.2), 20)

    def test_retarget_preserves_inputs_dates_and_original(self):
        original = {'features': [0, 0, 0, .2], 'target_pct': 32,
                    'decision_date': '2024-01-02', 'label_available_date': '2024-02-01'}
        changed = retarget([original])[0]
        self.assertEqual(original['target_pct'], 32)
        self.assertAlmostEqual(changed['target_pct'], 10)
        for key in ('features', 'decision_date', 'label_available_date'):
            self.assertEqual(changed[key], original[key])

    def test_selection_uses_converted_validation_error_and_boundary(self):
        train = [{'origin_month': '2022-10', 'decision_date': '2022-11-01',
                  'label_available_date': '2022-12-01'},
                 {'origin_month': '2022-12', 'decision_date': '2023-01-02',
                  'label_available_date': '2023-02-01'}]
        validation = [{'features': [0, 0, 0, .2], 'mean_target_pct': 32}]
        with patch('compare_last_close_target.candidate_predictions', side_effect=[[0], [10]]) as predict:
            name, scores = select_candidate(train, validation, 2024,
                {'candidateOrder': ['recent_0pct', 'linear_expanding']})
        self.assertEqual(name, 'linear_expanding')
        self.assertAlmostEqual(scores[name], 0)
        for call in predict.call_args_list:
            self.assertEqual(call.args[1], train[:1])

    def test_invalid_origin_ratio_rejected(self):
        for ratio in (0, -1, float('nan'), float('inf')):
            with self.assertRaises(ValueError):
                to_last_close_target(10, ratio)


if __name__ == '__main__':
    unittest.main()

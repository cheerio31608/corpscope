import unittest

from compare_validation_month_end_blend import blend, mae, select_alpha


class ValidationBlendTest(unittest.TestCase):
    def test_validation_selects_interior_weight_and_keeps_it_on_bad_test(self):
        alpha, _ = select_alpha([2, 4], [0, 0], [4, 8], [0, .25, .5, .75, 1])
        self.assertEqual(alpha, .5)
        # Test labels cannot enter selection: an adverse test is scored unchanged.
        self.assertEqual(mae([-100], blend([0], [8], alpha)), 104)
        self.assertEqual(alpha, .5)

    def test_endpoints_and_tie_prefer_baseline(self):
        self.assertEqual(blend([2], [10], 0), [2])
        self.assertEqual(blend([2], [10], 1), [10])
        alpha, _ = select_alpha([5], [5], [5], [1, .5, 0])
        self.assertEqual(alpha, 0)

    def test_malformed_pairs_and_values_fail(self):
        for operation in (lambda: blend([1], [], .5), lambda: mae([1], []),
                          lambda: mae([], []), lambda: mae([1], [float('nan')]),
                          lambda: blend([1], [2], 2)):
            with self.assertRaises(ValueError):
                operation()


if __name__ == '__main__':
    unittest.main()

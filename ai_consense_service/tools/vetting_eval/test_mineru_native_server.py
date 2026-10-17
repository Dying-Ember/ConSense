"""Runtime argument injection only; the official parser/model is not loaded."""
from copy import deepcopy
from types import SimpleNamespace
import unittest

from mineru_native_server import install_runtime_profile, MAX_TOKENS, ENGINE_OPTIONS


class MineruNativeServerTests(unittest.TestCase):
    def model(self):
        class Model:
            def _create_model(self, backend, *args, **kwargs):
                return backend, args, kwargs
        return Model

    def test_only_selected_runtime_limits_change(self):
        model = self.model()
        params = {name: SimpleNamespace(max_new_tokens=None, temperature=value,
                  top_p=0.01, prompt=name) for name, value in [('table', 0), ('[layout]', 0.2)]}
        before = deepcopy(params)
        install_runtime_profile(model, params)
        backend, args, kwargs = model()._create_model('vllm-async-engine', '/exact/model',
            None, model_name='official-model', max_concurrency=1)
        self.assertEqual(backend, 'vllm-async-engine')
        self.assertEqual(args, ('/exact/model', None))
        self.assertEqual(kwargs, dict(model_name='official-model', max_concurrency=1) | ENGINE_OPTIONS)
        for name, actual in params.items():
            expected = vars(before[name]) | {'max_new_tokens': MAX_TOKENS}
            self.assertEqual(vars(actual), expected)

    def test_conflicting_profile_and_fallback_fail(self):
        model = self.model(); original = model._create_model
        with self.assertRaisesRegex(ValueError, 'generation limit'):
            install_runtime_profile(model, {'table': SimpleNamespace(max_new_tokens=4096)})
        self.assertIs(model._create_model, original)
        install_runtime_profile(model, {'table': SimpleNamespace(max_new_tokens=None)})
        for backend, kwargs in [('transformers', {}), ('vllm-async-engine', {'dtype': 'float16'}),
                                ('vllm-async-engine', {'max_model_len': 32768})]:
            with self.subTest(backend=backend, kwargs=kwargs), self.assertRaises(ValueError):
                model()._create_model(backend, '/exact/model', None, **kwargs)


if __name__ == '__main__':
    unittest.main()

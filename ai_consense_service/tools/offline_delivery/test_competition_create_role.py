import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class CreateRoleTest(unittest.TestCase):
    def test_h2_heap_defaults_to_32g_and_accepts_override(self):
        script = Path(__file__).with_name('competition-create-role.sh')
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / 'docker'
            fake.write_text('#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
            fake.chmod(0o755)
            env = {**os.environ, 'DOCKER_BIN': str(fake)}
            env.pop('H2_HEAP', None)
            for override, expected in ((None, '-Xmx32g'), ('16g', '-Xmx16g')):
                with self.subTest(override=override):
                    run_env = dict(env)
                    if override:
                        run_env['H2_HEAP'] = override
                    result = subprocess.run(['sh', str(script), 'h2'], env=run_env,
                                            text=True, capture_output=True, check=True)
                    args = json.loads(result.stdout)
                    self.assertEqual([arg for arg in args if arg.startswith('-Xmx')], [expected])
                    self.assertIn('org.h2.tools.Server', args)

    def test_caller_user_cannot_override_non_root(self):
        script = Path(__file__).with_name('competition-create-role.sh')
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / 'docker'
            fake.write_text('#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
            fake.chmod(0o755)
            env = {**os.environ, 'DOCKER_BIN': str(fake)}
            for role in ('app', 'h2', 'qdrant', 'source', 'gpu'):
                for user_option in (['--user', 'root'], ['--user=root'], ['-u', '0']):
                    with self.subTest(role=role, user_option=user_option):
                        result = subprocess.run(['sh', str(script), role, *user_option], env=env,
                                                text=True, capture_output=True, check=True)
                        args = json.loads(result.stdout)
                        options = args[:args.index('--entrypoint')]
                        user_index = max(i for i, arg in enumerate(options)
                                         if arg == '--user' or arg.startswith('--user=') or arg == '-u')
                        self.assertEqual(options[user_index:user_index+2], ['--user', 'consense:consense'])


if __name__ == '__main__':
    unittest.main()

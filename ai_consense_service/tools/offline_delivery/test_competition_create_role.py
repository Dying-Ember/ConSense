import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class CreateRoleTest(unittest.TestCase):
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

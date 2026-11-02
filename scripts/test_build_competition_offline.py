import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('build-competition-offline.sh').resolve()


@unittest.skipUnless(os.name == 'posix', 'requires a POSIX shell')
class OfflineBuildTest(unittest.TestCase):
    def test_commands_use_explicit_caches_and_stop_on_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source copy'
            web = source / 'ai_consense_web'
            service = source / 'ai_consense_service'
            web.mkdir(parents=True)
            service.mkdir()
            (web / 'package-lock.json').write_text('{}')
            (service / 'pom.xml').write_text('<project/>')
            java, maven, node, npm_cache, maven_repo = [root / name for name in
                ('java', 'maven', 'node', 'npm cache', 'maven repo')]
            for path in (java / 'bin', maven / 'bin', node / 'bin', npm_cache, maven_repo):
                path.mkdir(parents=True)
            for program in (java / 'bin/java', java / 'bin/javac', node / 'bin/node'):
                program.write_text('#!/bin/sh\nexit 0\n')
                program.chmod(0o755)
            for program in (node / 'bin/npm', maven / 'bin/mvn'):
                program.write_text('#!/bin/sh\nprintf "%s\\n" "$0 $*" >> "$CALL_LOG"\n'
                                   '[ "${FAIL_NPM:-}" != yes ] || [ "${0##*/}" != npm ]\n')
                program.chmod(0o755)
            log = root / 'calls.log'
            env = {**os.environ, 'CALL_LOG': str(log)}
            command = ['sh', str(SCRIPT), *map(str, (source, java, maven, node, npm_cache, maven_repo))]
            subprocess.run(command, env=env, check=True)
            calls = log.read_text().splitlines()
            self.assertEqual(len(calls), 3)
            self.assertIn(f'--offline ci --no-audit --no-fund --cache {npm_cache}', calls[0])
            self.assertIn('--offline run build', calls[1])
            self.assertIn(f'--offline -B -ntp -Dmaven.repo.local={maven_repo} -DskipTests package', calls[2])
            log.unlink()
            self.assertNotEqual(subprocess.run(command, env={**env, 'FAIL_NPM': 'yes'}).returncode, 0)
            self.assertEqual(len(log.read_text().splitlines()), 1)


if __name__ == '__main__':
    unittest.main()

"""Plan or execute a native MySQL backup and a fresh, isolated restore; defaults are read-only."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

from delivery import asset, require, sha


def run(argv, input_path=None):
    with Path(input_path).open('rb') if input_path else open(os.devnull, 'rb') as source:
        result = subprocess.run(argv, stdin=source, capture_output=True, check=True, timeout=1800)
    return result.stdout.decode('utf-8')


def settings(defaults, database):
    path = Path(defaults).resolve()
    require(path.is_file(), 'private MySQL defaults file required')
    if os.name != 'nt':
        require(path.stat().st_mode & 0o077 == 0, 'MySQL defaults file must be private to its owner')
    require(re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,63}', database or '') and database.lower() not in
            ('mysql', 'sys', 'information_schema', 'performance_schema'), 'explicit application database required')
    return '--defaults-extra-file=' + str(path)


def backup(defaults, database, output, execute=False, runner=run):
    private = settings(defaults, database)
    output = Path(output).resolve()
    require(not output.exists(), 'fresh backup output required; existing assets are never overwritten')
    args = ['mysqldump', private, '--single-transaction', '--skip-lock-tables', '--routines', '--events', '--triggers',
            '--hex-blob', '--set-gtid-purged=OFF', '--no-create-db', '--result-file=' + str(output / 'snapshot.sql'), database]
    result = {'sourceDatabase': database, 'output': str(output), 'executed': False, 'targetAccepted': False}
    if not execute:
        return result
    output.mkdir(parents=True)
    # Failed dumps remain in this fresh directory for diagnosis; no existing backup is removed.
    runner(args)
    snapshot = output / 'snapshot.sql'
    require(snapshot.is_file() and snapshot.stat().st_size > 0, 'native dump did not produce a complete nonempty file')
    receipt = {'contract': 'consense-mysql-backup-v1', 'sourceDatabase': database, 'consistency': 'single-transaction',
               'snapshot': {'path': 'snapshot.sql', 'bytes': snapshot.stat().st_size, 'sha256': sha(snapshot)},
               'targetAccepted': False, 'boundary': 'Native dump produced; restore, schema migration and business acceptance remain separate.'}
    path = output / 'backup.json'
    path.write_text(json.dumps(receipt, indent=2) + '\n')
    result.update(executed=True, receiptSha256=sha(path), snapshot=receipt['snapshot'])
    return result


def restore(defaults, database, receipt_path, expected_receipt, execute=False, runner=run):
    private = settings(defaults, database)
    receipt_path = Path(receipt_path).resolve()
    require(sha(receipt_path) == expected_receipt, 'backup receipt differs from the approved SHA256')
    receipt = json.loads(receipt_path.read_bytes())
    require(receipt.get('contract') == 'consense-mysql-backup-v1' and receipt.get('sourceDatabase') != database,
            'restore requires a distinct isolated target database')
    snapshot = asset(receipt_path.parent, receipt['snapshot'])
    result = {'targetDatabase': database, 'snapshotSha256': receipt['snapshot']['sha256'], 'executed': False, 'targetAccepted': False}
    if not execute:
        return result
    grants = runner(['mysql', private, '--batch', '--skip-column-names', '--execute=SHOW GRANTS']).splitlines()
    require(grants, 'restore target account privileges are unknown')
    for grant in grants:
        parsed = re.fullmatch(r'GRANT ([A-Z_ ,]+) ON (.+) TO .+', grant)
        require(parsed and 'WITH GRANT OPTION' not in grant, 'restore account must have no global privileges, roles or grant option')
        privileges, scope = parsed.groups()
        scope = scope.replace('`', '')
        require(scope == database + '.*' or scope == '*.*' and privileges == 'USAGE',
                'restore account privileges must be restricted to the isolated target schema')
    query = ('SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()),'
             '(SELECT COUNT(*) FROM information_schema.processlist WHERE DB=DATABASE() AND ID<>CONNECTION_ID())')
    state = runner(['mysql', private, '--database=' + database, '--batch', '--skip-column-names', '--execute=' + query]).strip()
    require(state == '0\t0', 'restore target must be an empty isolated database with no other active connection')
    # The target account must have privileges only on this disposable schema; no cutover is performed here.
    require(sha(snapshot) == receipt['snapshot']['sha256'], 'backup asset changed before restore')
    runner(['mysql', private, '--database=' + database, '--binary-mode=1'], snapshot)
    require(sha(snapshot) == receipt['snapshot']['sha256'], 'backup asset changed during restore; target must be discarded')
    result.update(executed=True, boundary='Native import returned; verify schema, row counts, migrations and recovery before adoption.')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('backup', 'restore'))
    parser.add_argument('--defaults', required=True, help='Private client option file; secrets never enter arguments or receipts')
    parser.add_argument('--database', required=True)
    parser.add_argument('--out')
    parser.add_argument('--receipt')
    parser.add_argument('--expected-receipt-sha256')
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    if args.operation == 'backup':
        require(args.out, '--out is required for backup')
        result = backup(args.defaults, args.database, args.out, args.execute)
    else:
        require(args.receipt and args.expected_receipt_sha256, 'restore needs an exact receipt and expected SHA256')
        result = restore(args.defaults, args.database, args.receipt, args.expected_receipt_sha256, args.execute)
    print(json.dumps(result, indent=2))

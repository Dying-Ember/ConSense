"""Generate a reviewable exact native OCR configuration; never start providers."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vetting_eval'))
from native_provider_lifecycle import validate_provider, _finite, _bytes, _save


def prepare_complete_ocr(config_path, output):
    source = Path(config_path).resolve()
    config = json.loads(source.read_bytes())
    _finite(config.get('timeoutSeconds'), 'timeoutSeconds')
    config = copy.deepcopy(config)
    for name in ('paddleocr-vl', 'mineru'):
        row = config.get(name, {})
        if row.get('ownedBoot') is not None:
            row['ownedBoot'].setdefault('precision', 'bf16')
            row.setdefault('identity', {}).setdefault('config', {}).setdefault('precision', 'bf16')
        validate_provider(row)
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('fresh configuration output directory required')
    output.mkdir(parents=True)
    raw = _bytes(config)
    path = output / 'complete-ocr.json'
    with path.open('xb') as stream:
        stream.write(raw)
    digest = hashlib.sha256(raw).hexdigest()
    _save(output / 'environment.json', {'CONSENSE_COMPLETE_OCR_CONFIG': str(path),
        'CONSENSE_COMPLETE_OCR_CONFIG_SHA256': digest})
    plan = {'schema': 'consense.complete-ocr-boot-plan.v1', 'configSha256': digest,
        'inputDeclarationSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'providerOrder': ['paddleocr-vl', 'mineru'], 'timeoutSeconds': config['timeoutSeconds'],
        'handoffStatus': 'owned_native_release_required' if all(config[name].get('ownedBoot') or config[name].get('ownedJob') for name in
            ('paddleocr-vl', 'mineru')) else 'remote_handoff_unverified',
        'providers': {name: {'baseUrl': config[name]['baseUrl'], 'identity': config[name]['identity'],
            'ownedBoot': config[name].get('ownedBoot'), 'modelReady': False} for name in ('paddleocr-vl', 'mineru')},
        'targetAccepted': False,
        'boundary': 'Offline configuration and declared file SHA verification only; no native launch, image/model/API/GPU/source-quality acceptance.'}
    _save(output / 'boot-plan.json', plan)
    return plan


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, help='Private resolved provider declaration with exact native paths and file SHA inventory')
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    print(json.dumps(prepare_complete_ocr(args.config, args.out), indent=2))

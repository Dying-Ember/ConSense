"""Derive conservative script evidence from a pinned official Unihan release, offline."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

VERSION = '17.0.0'
ARCHIVE_SHA = 'f7a48b2b545acfaa77b2d607ae28747404ce02baefee16396c5d2d7a8ef34b5e'
VARIANTS_SHA = '3f23cd71872633f3350875d25bd388e83b60fa71807634c9a600ec26f38a68ab'
LICENSE_SHA = 'e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def generate(archive, license_path, output):
    archive_bytes = archive.read_bytes()
    license_bytes = license_path.read_bytes()
    if digest(archive_bytes) != ARCHIVE_SHA or digest(license_bytes) != LICENSE_SHA:
        raise ValueError('Use the pinned official Unicode archive and license bytes')
    with zipfile.ZipFile(archive) as bundle:
        variants_bytes = bundle.read('Unihan_Variants.txt')
    if digest(variants_bytes) != VARIANTS_SHA:
        raise ValueError('Unihan variant member differs from the pinned release')
    variants = variants_bytes.decode('utf-8')
    if '# Unicode Version ' + VERSION not in variants:
        raise ValueError('Unexpected Unicode version')
    fields = {'kTraditionalVariant': {}, 'kSimplifiedVariant': {}}
    for line in variants.splitlines():
        if not line or line.startswith('#'):
            continue
        source, field, values = line.split('\t')
        if field in fields:
            fields[field][int(source[2:], 16)] = {int(value.split('<')[0][2:], 16) for value in values.split()}
    to_traditional = fields['kTraditionalVariant']
    to_simplified = fields['kSimplifiedVariant']

    def evidence(forward, reverse):
        return {point for point, targets in forward.items()
                if targets and point not in reverse and point not in targets
                and all(point in reverse.get(target, set()) for target in targets)}

    hans = evidence(to_traditional, to_simplified)
    hant = evidence(to_simplified, to_traditional)
    if not hans or not hant or hans & hant:
        raise ValueError('Script evidence must be nonempty and disjoint')
    text = ('# Conservative script evidence derived from Unicode Unihan ' + VERSION + '\n'
            '# Copyright 2025 Unicode, Inc. See unicode-license-v3.txt.\n'
            '# Reciprocal non-self variants only; a code point with both variant properties is excluded.\n'
            '# Script evidence only, not an exhaustive language classifier or a text conversion table.\n'
            + 'zhHans\t' + ''.join(chr(point) for point in sorted(hans)) + '\n'
            + 'zhHant\t' + ''.join(chr(point) for point in sorted(hant)) + '\n').encode('utf-8')
    output.mkdir(parents=True, exist_ok=True)
    resource = output / ('unihan-script-' + VERSION + '.txt')
    resource.write_bytes(text)
    (output / 'unicode-license-v3.txt').write_bytes(license_bytes)
    manifest = {
        'unicodeVersion': VERSION,
        'sourceUrl': 'https://www.unicode.org/Public/' + VERSION + '/ucd/Unihan.zip',
        'sourceArchiveBytes': len(archive_bytes), 'sourceArchiveSha256': ARCHIVE_SHA,
        'sourceMember': 'Unihan_Variants.txt', 'sourceMemberBytes': len(variants_bytes), 'sourceMemberSha256': VARIANTS_SHA,
        'licenseUrl': 'https://www.unicode.org/license.txt', 'license': 'Unicode License V3',
        'licenseBytes': len(license_bytes), 'licenseSha256': LICENSE_SHA,
        'licenseSnapshot': 'Official bytes retrieved 2026-10-20; copyright 1991-2026',
        'derivation': 'For each direction, retain the source code point only if it has no opposite variant property, no self target, and every target explicitly maps back in the opposite property. Retain no other variant fields.',
        'runtimeRule': 'Infer only from unquoted question text with evidence from exactly one set. Both sets or neither set remain generic zh. Explicit language/script requests take precedence. Never convert user/source text.',
        'limitations': 'Unihan variants are evidence, not a complete orthographic classifier. Shared/self/dual-property characters and mixed-script text do not determine a script. The resource does not attest the model body script.',
        'resource': resource.name, 'resourceBytes': len(text), 'resourceSha256': digest(text),
        'hansEvidenceCodePoints': len(hans), 'hantEvidenceCodePoints': len(hant),
        'generator': 'tools/generate_advisory_script_data.py'
    }
    (output / ('unihan-script-' + VERSION + '.json')).write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--license', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'src/main/resources/advice')
    args = parser.parse_args()
    print(json.dumps(generate(args.archive, args.license, args.output), indent=2))

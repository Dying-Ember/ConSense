"""Narrow compatibility with the immediately preceding Source release."""
import hashlib
from pathlib import Path

PREDECESSOR = {
    'migrated_qdrant_index.py': 'c5f08c53233f1aa249826b72433583c814aaf25cebbc0b56e70f658bed397150',
    'oer_server.py': 'c16153ed5ead2b9cb53fd063330ffd336638e177bd45f0ab457882e70933f34c',
}
CURRENT = {
    'migrated_qdrant_index.py': ('80868c1dad75654b2f4e6cb9aeb4871b2d48c9e132fcb3e19c23e484402ecb55', '8054f9c77d594d49679ac182d2bd0ead03bdb567461b8bcb196fde2bc48081f6', 'c9ff43dd50b55057f92e88b2f79da26661a29f540c56a4f74a91b296773ef758', 'a1a37a6699eacf546cc6eaf5883fb0db40f41d1170cae5959c1c2ccb20f58ebd'),
    'oer_server.py': ('e3b47f2a161de74872bafcf68cb6b295fa484172b95d771f11d0822e4399314a', '65cb7ef437114ebba7510c10252005018f7f3508b8a9719fc02aa343a5135fc1', '61cb8c1cebe980efd58f275f344e4c9a09751678d272db9b7b91fb02aacd4394', '7929a1557080d62673399602a083dfc22c6a05342a7ec592c22142efa684de5a'),
    'source_page_index.py': ('e076f4c55d5feec68bc7dacc349d74fdfeaeb37c74dda3692ceb10da3b7dd3d5', '4653043b0e51de494342e69ec2c8e6feedeaba72fd598f68976f4a43ebdcd5b3'),
    'server.py': ('81e1d46eea92d8a3f95ded52473dd693c51164aad2aa1af63724f20e441b7d09', 'eb9d9d83f89c5ed4721e33e133cbaa4df4c8b7bc1c370306f51a3c4ea99f5007', '1fec2b2f61069245505a4aca4564839ac48d3a8b872b025a7243d285bd7acc51', 'ed828a0ad3b9fcf2c88a66f4b6673512990fc3c72b21eaba7286827dc00248c6'),
    'model_session_observer.py': ('97e83ed75cf84b420b6febc592d133b7eb5c5a0da3890d31fa54676d4fd49a66', '5c92a0dc91a1fb9547885829a4433ba6445f9c697c72df19a8b163ea051b113b'),
    'model_session_receipt.py': ('7edbd15c12087610834259e608ab0efd1607e77236f651292f2ee06fea4c4c09', '1be49d08985e938000484f7d07cd4366e5e25aef640fee54b2d7ec090bfbdda7'),
}
HELPER = 'ordinary_predecessor_compat.py'
HELPER_PIN = '8edb24a6b6f23c877254628e5d16afb2b24637cf9ea939a644a9ba0a7841c0f2'
TICKET14_PREVIOUS = {
    'migrated_qdrant_index.py': '80868c1dad75654b2f4e6cb9aeb4871b2d48c9e132fcb3e19c23e484402ecb55',
    'fixed_gpu_profiles.py': '5abadc07faa75e5b34f05523280fef8abe2abd4a5b3da040718933c359df06bc',
    'oer_server.py': 'e3b47f2a161de74872bafcf68cb6b295fa484172b95d771f11d0822e4399314a',
    HELPER: 'cbef38b18b2bd5b81af65b23f7893dae85887219a489829c7b50a2c479a0ed87',
}
TICKET14_CURRENT = {
    'migrated_qdrant_index.py': 'c9ff43dd50b55057f92e88b2f79da26661a29f540c56a4f74a91b296773ef758',
    'fixed_gpu_profiles.py': '855b92c4651faa130d3e134b7042401b0be8a6e906f68720f0b0c105121a5a1e',
    'oer_server.py': '61cb8c1cebe980efd58f275f344e4c9a09751678d272db9b7b91fb02aacd4394',
}
PREVIOUS_HELPER = ('be1c2b989fd4177c5a676e07413460a84384a47ae19969d03f754f00b38ff56c',
                   'e642c81d4a3a5d1bb46164421678e84824b3e993767ab3375a4d61cd5d9c29ea',
                   'e67821112cc847e1608afbf732340f19f78542cef9b6c6078c1c055a7459a414',
                   'ebed817ef378b86b09790c70066a7839d0e5898d51dd5362cf4209e835902954',
                   '2868455e43e7249c5b3395faa116655a9578b78b2acccb86ce62fbc53ab09a7a',
                   'f5a65deec41c4a628f6711745a88ff8f78f46d228877378d8585beb40436d641')
RELEASE_PREDECESSORS = {
    'model_session_observer.py': 'badc59bae09e1d408401bc4f1fde6c33012f7a9fa15359fbcc86171b152848c3',
    'model_session_receipt.py': '5bba5dbcd58f901e569263970ff227db5225a77e65c9fd3d019d6dfb37a24843',
}
PREVIOUS_SERVER = ('1fec2b2f61069245505a4aca4564839ac48d3a8b872b025a7243d285bd7acc51',
                   'b0e4f1ee1852fe103ae57cffc1127d8976c84ade2ae5f4b973f8ef4dd0a4e37b',
                   '5fcf83691241b2ed43e215b26b42b532f500a4cce6cc1c8f8d4584ec1e942277')
PAGE_PREDECESSOR = ('94d5bc332b56c8cf84394f644fa861fcdfc603ac102ed620d9188f381183f53f',
                    'fa4c071bb230676afa02b1f6fafb90c35010d908410cfbea63c0bdf464e10e55')


def _approved_helper(raw):
    # Only uniform LF or CRLF is equivalent. Exclude mixed line endings.
    normalized = raw.replace(b'\r\n', b'\n')
    if b'\r' in normalized or (b'\r\n' in raw and raw.count(b'\r\n') != raw.count(b'\n')):
        return False
    needle = ("HELPER_PIN = '" + HELPER_PIN + "'").encode('ascii')
    if normalized.count(needle) != 1:
        return False
    normalized = normalized.replace(needle, b"HELPER_PIN = '<self>'", 1)
    return hashlib.sha256(normalized).hexdigest() == HELPER_PIN


def compatible(stored, current, source_root):
    if not isinstance(stored, dict) or not isinstance(current, dict):
        return False
    if {k: v for k, v in stored.items() if k != 'algorithms'} != {k: v for k, v in current.items() if k != 'algorithms'}:
        return False
    before, after = stored.get('algorithms'), current.get('algorithms')
    if not isinstance(before, dict) or not isinstance(after, dict):
        return False
    reuse = 'same_project_vector_reuse.py'
    old_window = '53575013d344c8589074539fd06bacc3971f58e8c2a938ccfc3e686c97303cb1'
    new_reuse = '2827df4de9f8470e72cd30659ea13835cf43ec30d78982d36012c7f080a6629f'
    if before.get(reuse) == '6928823ad33d94c92468c930df0e6b690458aafe58dfcb11ae837a56e22b4026':
        try:
            root = Path(source_root).resolve()
            helper = (root / HELPER).read_bytes()
            if (Path(__file__).resolve() == root / HELPER and _approved_helper(helper)
                    and before.get(HELPER) == '346c0c2c037e4bf096d5e961b15d946ba6e832bbd97710015fd9a8bd4bc7696e'
                    and after.get(HELPER) == hashlib.sha256(helper).hexdigest()
                    and after.get(reuse) == new_reuse == hashlib.sha256((root / reuse).read_bytes()).hexdigest()
                    and set(before) == set(after)
                    and all(before[name] == after[name] for name in before if name not in (HELPER, reuse))):
                return True
        except OSError:
            return False
    if reuse in after and reuse not in before and before.get('window_runtime.py') == old_window:
        root = Path(source_root).resolve()
        try:
            expected = {'window_runtime.py': 'e0744bbe831ff9a1635800055c9a4ca1b50680f23868ba8d90edf4ca22a1b4de',
                        reuse: new_reuse}
            if not all(after.get(name) == sha == hashlib.sha256((root / name).read_bytes()).hexdigest()
                       for name, sha in expected.items()):
                return False
            adjusted = dict(current)
            adjusted['algorithms'] = dict(after)
            adjusted['algorithms'].pop(reuse)
            adjusted['algorithms']['window_runtime.py'] = old_window
            return compatible(stored, adjusted, source_root)
        except OSError:
            return False
    if stored.get('signatureVersion') == 6 and stored.get('oerTargetModule') == 'advisory' and set(before) == set(after):
        try:
            root = Path(source_root).resolve()
            files = {name: (root / name).read_bytes() for name in (*TICKET14_CURRENT, HELPER)}
            if (Path(__file__).resolve() == root / HELPER and _approved_helper(files[HELPER])
                    and all(before.get(name) == digest for name, digest in TICKET14_PREVIOUS.items())
                    and all(after.get(name) == digest == hashlib.sha256(files[name]).hexdigest()
                            for name, digest in TICKET14_CURRENT.items())
                    and after.get(HELPER) == hashlib.sha256(files[HELPER]).hexdigest()
                    and all(before[name] == after[name] for name in before if name not in TICKET14_PREVIOUS)):
                return True
        except OSError:
            return False
    if set(after) != set(before) | {HELPER}:
        return False
    try:
        root = Path(source_root).resolve()
        if Path(__file__).resolve() != root / HELPER:
            return False
        files = {name: (root / name).read_bytes() for name in (*CURRENT, HELPER)}
        hashes = {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}
    except OSError:
        return False
    if (not all(after.get(name) == hashes[name] and hashes[name] in allowed for name, allowed in CURRENT.items())
            or after.get(HELPER) != hashes[HELPER] or not _approved_helper(files[HELPER])):
        return False
    portable = 'portable_index_signature.py'
    if portable in before:
        try:
            portable_hash = hashlib.sha256((root / portable).read_bytes()).hexdigest()
        except OSError:
            return False
        current_portable = ('b92c8226446bd6d910ab286285fb662e973a6998971f7bc719784f4b9a84476e',
                            '9d4280839269fbdaac307e75246716b30f2431288c6439e367a3d105da2f8f50')
        previous_portable = ('77b621514a727e700cca219a0db9787905fb381d65855c510273e1ee79394807',
                             '0369c93c7da6023188d97f249094af20031183b834d2d6a83a65f073cdec1866')
        if after.get(portable) != portable_hash:
            return False
        if before[portable] != portable_hash and not (portable_hash in current_portable and before[portable] in previous_portable):
            return False
    server = 'server.py'
    if before.get(server) != after.get(server) and before.get(server) not in PREVIOUS_SERVER:
        return False
    if HELPER in before:
        return (before[HELPER] in PREVIOUS_HELPER
                and all(before[name] == after[name] or
                        (name in RELEASE_PREDECESSORS and before[name] == RELEASE_PREDECESSORS[name])
                        for name in before if name not in (HELPER, server, portable)))
    page = 'source_page_index.py'
    return (all(before.get(name) == digest for name, digest in PREDECESSOR.items())
            and (before.get(page) == after.get(page) or before.get(page) in PAGE_PREDECESSOR)
            and all(before[name] == after[name] or
                    (name in RELEASE_PREDECESSORS and before[name] == RELEASE_PREDECESSORS[name])
                    for name in before if name not in (*PREDECESSOR, page, server, portable)))

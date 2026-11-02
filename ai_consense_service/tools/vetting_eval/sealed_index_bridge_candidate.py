"""Isolated candidate bridge; runtime adoption and Server authentication are not wired."""
import hashlib
import json
import os
import re
from urllib.parse import urlsplit
from pathlib import Path

from fixed_development_baseline_runtime import desc, write
from index_integrity import verify_reuse
from migrate_sealed_collections import _checked_file, _pinned_json, require, transfer_with_journal
from token_windows import digest

_IMPORTED_SOURCE_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


SCHEMA = "consense.sealed-index-server-migration-candidate.v1"
BINDING_QUERY = ('SELECT id,project_id,document_id,projection_sha256,original_source_sha256,'
    'java_parsed_source_hash,routing_sha256,source_revision_sha256,projection_revision_sha256,'
    'canonical_source_hash,scope_policy_sha256,adoption_manifest_sha256,review_reference_sha256,'
    'snapshot_bytes_sha256,declared_module,created_at,family_composition_sha256,'
    'family_component_set_sha256,snapshot_content_sha256 FROM canonical_source_binding')


def _binding_rows(raw):
    """Read only the binding query, excluding aggregate-query rows in the same snapshot."""
    rows = []
    inside = completed = False
    for line in raw.decode('utf-8').splitlines():
        if line.startswith('QUERY\t'):
            require(not inside, 'Binding query was not completed')
            if line[6:] == BINDING_QUERY:
                require(not completed, 'Duplicate binding query')
                inside = True
        elif inside and line.startswith('ROW\t'):
            row = line.split('\t')
            require(len(row) == 20 and bool(row[1]), 'H2 binding snapshot is malformed')
            rows.append(row)
        elif inside and line.startswith('MS\t'):
            inside = False
            completed = True
        elif inside:
            raise ValueError('H2 binding snapshot is malformed')
    require(completed and not inside and len({row[1] for row in rows}) == len(rows),
            'Binding query incomplete or ambiguous')
    return rows


def _legacy_scope(metadata, audit_row, matched, h2_project, module):
    """Recover only source addresses proven by the audited historical bindings."""
    if 'sourceScope' in metadata:
        return None
    by_id = {item[1]: item for item in matched}
    projections = [by_id[binding_id][4] for binding_id in audit_row['bindingIds']]
    pins = metadata['signature'].get('sourcePageEvidence', {})
    sha = lambda value: isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None
    require(projections and len(set(projections)) == len(projections) and all(map(sha, projections))
        and isinstance(pins, dict) and set(pins).issubset(projections)
        and all(sha(key) and sha(value) for key, value in pins.items()), 'Legacy projection binding differs')
    expected = h2_project + '::canonical::' + module + '::' + digest(projections if not pins else [projections, pins])
    require(metadata['projectId'] == expected, 'Legacy canonical index key differs')
    return {'projectId': expected, 'module': module, 'projectionSha256': projections,
            'sourcePageEvidence': dict(sorted(pins.items()))}

def _audit(inputs, phase_path, phase_sha, audit_path, bindings_path, bindings_sha, h2_project, module, *, check_input_files=True):
    phase = _pinned_json(phase_path, phase_sha)
    require(phase == inputs["phaseB"] and phase["status"] == "PASS" and phase["number"] == inputs["number"]
            and phase["metadataSha256"] == inputs["metadata"]["sha256"]
            and phase["sqliteSha256"] == inputs["sqlite"]["sha256"], "Phase B binding differs")
    audit = _pinned_json(audit_path, phase["bindingAuditSha256"])
    rows = [row for row in audit["indexes"] if row["file"] == inputs["number"]]
    require(len(rows) == 1, "Index binding audit is ambiguous")
    if check_input_files:
        for item in ("metadata", "sqlite", "qdrantMeta"):
            _checked_file(inputs[item]["path"], inputs[item])
    metadata = _pinned_json(inputs["metadata"]["path"], inputs["metadata"]["sha256"])
    row = rows[0]
    identity_fields = ("documentId", "sourceRevisionSha256", "projectionRevisionSha256", "originalSourceSha256")
    observed = {tuple(item.get(key) for key in identity_fields) for item in metadata["structureContexts"]}
    identities = {tuple(item) for item in row["identities"]}
    binding_ids = row["bindingIds"]
    with Path(bindings_path).open("rb") as source:
        raw = source.read(16 * 1024 * 1024 + 1)
    require(len(raw) <= 16 * 1024 * 1024 and hashlib.sha256(raw).hexdigest() == bindings_sha, "H2 binding snapshot changed")
    h2_rows = _binding_rows(raw)
    matched = [item for item in h2_rows if item[1] in binding_ids and item[2] == h2_project and item[15] == module]
    matched_ids = {item[1] for item in matched}
    h2_identities = {(item[3], item[8], item[9], item[5]) for item in matched}
    seal = metadata["vectorIntegrity"]
    require(row["projectId"] == metadata["projectId"] and row["h2Project"] == h2_project
        and row["module"] == module and row["collection"] == inputs["collection"] == metadata["collection"]
        and row["seal"] == seal["sealSha256"] == phase["sealSha256"]
        and seal["sealSha256"] == digest({key: value for key, value in seal.items() if key != "sealSha256"})
        and seal["pointCount"] == phase["pointCount"] and seal["dimension"] == phase["dimension"]
        and "persistedPointsSha256" in phase["matchedRecordedValues"]
        and observed == identities == h2_identities and len(identities) == len(row["identities"])
        and matched_ids == set(binding_ids) and len(matched) == len(binding_ids)
        and len(set(binding_ids)) == len(binding_ids) == len(identities), "Source index binding differs")
    row = {**row, '_bindingRows': matched}
    return metadata, phase, row

def _server_binding(server):
    """Bind the existing reader and an independently created writer to one configuration."""
    from window_runtime import require_initial_database_binding
    url = os.environ.get('CONSENSE_QDRANT_URL')
    try:
        parsed = urlsplit(url or '')
        require(parsed.scheme in ('http', 'https') and bool(parsed.hostname)
            and parsed.username is None and parsed.password is None
            and not parsed.query and not parsed.fragment, 'Non-secret Server URL required')
        parsed.port  # Reject malformed ports without including the URL in diagnostics.
    except ValueError:
        raise ValueError('Non-secret Server URL required') from None
    state = server.STATE
    configuration = {'CONSENSE_QDRANT_URL': url}
    if 'CONSENSE_QDRANT_API_KEY' in os.environ:
        configuration['CONSENSE_QDRANT_API_KEY'] = os.environ['CONSENSE_QDRANT_API_KEY']
    identity = (str(state), url, configuration.get('CONSENSE_QDRANT_API_KEY'))
    database = server.database()
    def check():
        require_initial_database_binding(server, database, identity)
    check()
    return state, configuration, database, check


def transfer_server_candidate(server, inputs, local_state, out, batch_size, max_seconds, max_points, *, target_collection, checkpoint=None):
    """Transfer through a fresh client from the reader's effective Server configuration.

    The caller remains responsible for actual same-collection writer scope; this
    configuration binding does not establish Server authentication or exclusivity.
    """
    require(isinstance(target_collection, str) and target_collection not in ('', '.', '..')
            and target_collection != inputs.get('collection') and '/' not in target_collection
            and '\\' not in target_collection, 'Distinct new target collection required')
    from qdrant_backend import connect
    state, configuration, reader, check = _server_binding(server)
    check()
    collections = reader.get_collections().collections
    aliases = reader.get_aliases().aliases
    check()
    require(isinstance(collections, list) and isinstance(aliases, list)
            and all(isinstance(getattr(row, 'name', None), str) for row in collections)
            and all(isinstance(getattr(row, 'alias_name', None), str) for row in aliases)
            and not any(row.name == target_collection for row in collections)
            and not any(row.alias_name == target_collection for row in aliases),
            'Target collection or alias already exists')
    def guarded_checkpoint():
        check()
        if checkpoint is not None:
            checkpoint()
        check()
    def target_factory():
        guarded_checkpoint()
        target = connect(state, configuration)
        try:
            guarded_checkpoint()
        except BaseException:
            target.close()
            raise
        return target
    return transfer_with_journal(inputs, local_state, target_factory, out, batch_size, max_seconds, max_points,
        checkpoint=guarded_checkpoint, target_collection=target_collection, target_url=configuration['CONSENSE_QDRANT_URL'])


def create_candidate(inputs, phase_path, phase_sha, audit_path, bindings_path, bindings_sha, journal, server, h2_project, module, output, *, publish=True):
    _, configuration, target, check = _server_binding(server)
    target_url = configuration['CONSENSE_QDRANT_URL']
    metadata, phase, row = _audit(inputs, phase_path, phase_sha, audit_path, bindings_path, bindings_sha, h2_project, module)
    raw = (Path(journal) / "migration-receipt.jsonl").read_bytes()
    require(len(raw) <= 1024 * 1024 and raw.endswith(b"\n"), "Transfer journal is incomplete")
    events = [json.loads(line) for line in raw.splitlines()]
    require([event["status"] for event in events] == ["initializing", "metadata_verified", "running",
        "transfer_intent", "completed_candidate_not_adopted"], "Transfer did not complete")
    target_name = events[-1].get("targetCollection")
    require(isinstance(target_name, str) and target_name != inputs["collection"]
        and all(event.get("targetCollection") == target_name and event.get("targetUrl") == target_url for event in events)
        and events[1]["metadataSha256"] == inputs["metadata"]["sha256"]
        and events[-1]["source"] == events[-1]["target"]
        and events[-1]["source"]["pointsSha256"] == metadata["vectorIntegrity"]["persistedPointsSha256"],
        "Transfer binding or source seal differs")
    check()
    candidate = {**metadata, "collection": target_name}
    actual = verify_reuse(target, candidate, key=metadata["projectId"], collection=target_name,
                          chunks=candidate["chunks"], signature=candidate["signature"])
    require(actual == events[-1]["target"], "Target full seal changed")
    check()
    # Recheck the same pinned inputs after the target scan without decoding a
    # second metadata copy while the first remains live.
    for name in ('metadata', 'sqlite', 'qdrantMeta'):
        _checked_file(inputs[name]['path'], inputs[name])
    require(_pinned_json(phase_path, phase_sha) == phase, 'Phase B binding differs')
    _pinned_json(audit_path, phase['bindingAuditSha256'])
    with Path(bindings_path).open('rb') as source:
        raw_bindings = source.read(16 * 1024 * 1024 + 1)
    require(len(raw_bindings) <= 16 * 1024 * 1024
        and hashlib.sha256(raw_bindings).hexdigest() == bindings_sha, 'H2 binding snapshot changed')
    check()
    output = Path(output)
    require(output.is_absolute() and output.parent.is_dir() and output.parent.resolve() == output.parent
        and not output.exists() and not output.is_symlink(), "Fresh candidate directory required")
    protected = [Path(inputs[name]["path"]).parent for name in ("metadata", "sqlite", "qdrantMeta")] + [Path(journal)]
    protected += [Path(phase_path), Path(audit_path), Path(bindings_path)]
    require(all(not output.is_relative_to(path) and not path.is_relative_to(output) for path in protected),
            "Candidate directory overlaps protected inputs")
    output.mkdir(mode=0o700)
    candidate_desc = write(output / "candidate-metadata.json", candidate)
    receipt = {"schema": SCHEMA, "status": "candidate_not_adopted", "projectId": metadata["projectId"],
        "h2Project": h2_project, "module": module, "target": {"url": target_url, "collection": target_name},
        "inputs": inputs, "phaseB": desc(phase_path), "bindingAudit": desc(audit_path),
        "bindings": desc(bindings_path), "journal": desc(Path(journal) / "migration-receipt.jsonl"), "candidateMetadata": candidate_desc,
        "targetVectorIntegrity": actual, "serverAuthenticationVerified": False,
        "transferJournalAuthority": "local-unverified", "runtimeCompatibilityVerified": False, "businessAccepted": False}
    check()
    receipt_path = output / "candidate-receipt.json"
    pending = output / "candidate-receipt.pending.json"
    ready = output / ".candidate-receipt.ready"
    # Retain the pending marker and staged bytes on failure. Only the final
    # name is accepted by the sealed reader; linking never overwrites a peer.
    write(pending, {"status": "pending_without_final_receipt", "candidateReceipt": "candidate-receipt.json"})
    fd = os.open(ready, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
        json.dump(receipt, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    check()
    if publish:
        os.link(ready, receipt_path)
        return receipt_path
    return ready

def candidate_artifacts(receipt, target_url, h2_project, module, *, include_legacy_scope=False):
    require(receipt["schema"] == SCHEMA and receipt["status"] == "candidate_not_adopted"
        and receipt["h2Project"] == h2_project and receipt["module"] == module
        and receipt["target"]["url"] == target_url
        and receipt["target"]["collection"] != receipt["inputs"]["collection"], "Candidate route binding differs")
    metadata, phase, row = _audit(receipt["inputs"], Path(receipt["phaseB"]["path"]),
        receipt["phaseB"]["sha256"], Path(receipt["bindingAudit"]["path"]), Path(receipt["bindings"]["path"]),
        receipt["bindings"]["sha256"], h2_project, module, check_input_files=False)
    journal = receipt["journal"]
    require(desc(journal["path"]) == journal, "Transfer journal changed")
    candidate = {**metadata, "collection": receipt["target"]["collection"]}
    # write() uses this exact encoding. Compare its incremental digest to the
    # separately pinned file without decoding a second large metadata object.
    encoded = json.JSONEncoder(ensure_ascii=False, indent=2).iterencode(candidate)
    sha = hashlib.sha256(); size = 0
    for part in encoded:
        data = part.encode('utf-8'); sha.update(data); size += len(data)
    sha.update(b'\n'); size += 1
    expected = receipt["candidateMetadata"]
    require(desc(expected['path']) == expected and sha.hexdigest() == expected['sha256']
        and size == expected['bytes'] and candidate["projectId"] == receipt["projectId"], "Candidate metadata differs")
    if include_legacy_scope:
        return candidate, _legacy_scope(metadata, row, row['_bindingRows'], h2_project, module)
    return candidate

def verify_candidate(path, receipt_sha, server, h2_project, module):
    require(Path(path).name == 'candidate-receipt.json', 'Published candidate receipt required')
    _, configuration, target, check = _server_binding(server)
    receipt = _pinned_json(path, receipt_sha)
    candidate = candidate_artifacts(receipt, configuration['CONSENSE_QDRANT_URL'], h2_project, module)
    check()
    actual = verify_reuse(target, candidate, key=candidate["projectId"], collection=candidate["collection"],
                          chunks=candidate["chunks"], signature=candidate["signature"])
    check()
    require(actual == receipt["targetVectorIntegrity"], "Target full seal changed")
    return candidate

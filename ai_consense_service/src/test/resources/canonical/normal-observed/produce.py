"""Authored Java seam fixture helper; actual producer/CAS, no neural consumer."""
import json
from pathlib import Path
import sys
sys.path.insert(0, sys.argv[1])
from artifact_store import ArtifactStore
from normal_observed_projection import produce_normal_observed, validate_normal_observed
from oer_source_registry import _serialize
request = json.loads(Path(sys.argv[2]).read_bytes())
store = ArtifactStore(sys.argv[3])
projection = produce_normal_observed(request['candidateGraph'], request['sourceBinding'], artifact_store=store)
validate_normal_observed(projection, artifact_store=store)
Path(sys.argv[4]).write_bytes(_serialize(projection))

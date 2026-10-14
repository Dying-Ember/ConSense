"""Check that an incomplete or misleading loss history cannot be rendered."""
import copy
import json
import sys
from pathlib import Path

from render_progress_report import validate


ledger = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
validate(ledger)
run = ledger["trainingHistory"]["runs"][0]
# Keep real evidence, but omit unrelated scorecards for these rejection probes.
probe = {**ledger, "documentScorecards": []}
mutations = [
    lambda r: r.update(forwards=r["forwards"] + 1),
    lambda r: r["losses"][0].update(loss=float("nan")),
    lambda r: r["losses"][0].update(loss=-1),
    lambda r: r["losses"][1].update(**r["losses"][0]),
    lambda r: r["losses"][0].update(step=999),
    lambda r: r.update(evidence=["missing-training-evidence"]),
    lambda r: r["sourceOutcome"].update(lossIsNotSourceAccuracy=False),
    lambda r: r["sourceOutcome"].update(evidence=["missing-source-evidence"]),
]
for mutate in mutations:
    candidate = copy.deepcopy(run)
    mutate(candidate)
    try:
        validate({**probe, "trainingHistory": {"runs": [candidate]}})
    except ValueError:
        pass
    else:
        raise AssertionError("Invalid training history was accepted")
print(json.dumps({"status": "passed", "realLedgerValidated": True,
                  "trainingHistoryRejectionControls": len(mutations)}))

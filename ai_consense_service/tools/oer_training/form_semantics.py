"""Page-local form semantics; schema validity never establishes source correctness."""
import json
import math

SCHEMA = "consense.form-semantics.v1"
PROMPT = (
    "Observe the complete page and return one JSON object with schema " + SCHEMA + ". "
    "Use keys schema,pageId,imageSize,fields,groups,relations. imageSize is [width,height] "
    "of the input raster. IDs are unique page-local occurrence IDs, including repeated labels. "
    "Each field has id,type,writingBbox,state,value,owner,writing,labels. writingBbox encloses "
    "its writing regions, not its label or the whole field. Coordinates are top-left xyxy "
    "pixels in the input raster. Each writing region has id,bbox,state,value; each label has "
    "id,text. Each group has id,label,parentId; label is null without a separate printed heading. "
    "owner references a group or is null. "
    "Each page-local relation has id,kind,fromId,toId referencing fields/groups. "
    "State is filled,unfilled,mixed,unknown; writing regions cannot be mixed. "
    "Preserve exact visible entered text. Field value joins filled writing values in reading "
    "order with a newline. Unfilled/unknown values are null. A mixed field retains filled "
    "and unfilled writing regions separately. Complete visual context is required for "
    "unfilled; absent OCR or white pixels alone do not establish it. Keep uncertain states, "
    "types and owners unknown/null. Do not infer legal adoption or stored database values. "
    "Scanning background and compression halos do not require pixel-wise role segmentation. "
    "Do not infer unseen next-page fields or use Markdown."
)


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _keys(value, keys, name):
    _require(isinstance(value, dict) and set(value) == set(keys.split()), name + " keys differ")


def validate_form(page, *, complete=False):
    """Validate references/states/bounds, without promoting a prediction to reviewed gold."""
    _keys(page, "schema pageId imageSize fields groups relations", "Form page")
    _require(page["schema"] == SCHEMA, "Unsupported form semantics schema")
    _require(isinstance(page["pageId"], str) and bool(page["pageId"].strip()), "Page ID required")
    size = page["imageSize"]
    _require(isinstance(size, list) and len(size) == 2
             and all(type(v) is int and v > 0 for v in size), "Actual raster imageSize required")
    for name in ("fields", "groups", "relations"):
        _require(isinstance(page[name], list), name + " must be an array")
    seen = set()

    def identify(row):
        value = row.get("id")
        _require(isinstance(value, str) and bool(value.strip()) and value not in seen,
                 "Missing or duplicate occurrence ID")
        seen.add(value)
        return value

    def box(value):
        _require(isinstance(value, list) and len(value) == 4
                 and all(type(v) in (int, float) and math.isfinite(v) for v in value),
                 "Finite writing bbox required")
        _require(0 <= value[0] < value[2] <= size[0]
                 and 0 <= value[1] < value[3] <= size[1], "Writing bbox outside actual raster")

    def state(row, *, mixed=False):
        allowed = {"filled", "unfilled", "unknown"} | ({"mixed"} if mixed else set())
        _require(isinstance(row["state"], str) and row["state"] in allowed, "Unsupported form state")
        if complete:
            _require(row["state"] != "unknown", "Unknown form target cannot become complete supervision")
        if row["state"] in {"filled", "mixed"}:
            _require(isinstance(row["value"], str) and bool(row["value"].strip()),
                     "Filled form state needs positive literal value")
        else:
            _require(row["value"] is None, "Unfilled/unknown form value must be null")

    groups = {}
    for group in page["groups"]:
        _keys(group, "id label parentId", "Group")
        groups[identify(group)] = group
        _require(group["label"] is None or isinstance(group["label"], str),
                 "Raw group label or null required")
    for group in groups.values():
        parent = group["parentId"]
        ancestors = {group["id"]}
        while parent is not None:
            _require(isinstance(parent, str) and parent in groups and parent not in ancestors,
                     "Foreign or cyclic form owner")
            ancestors.add(parent)
            parent = groups[parent]["parentId"]
    fields = {}
    for field in page["fields"]:
        _keys(field, "id type writingBbox state value owner writing labels", "Field")
        fields[identify(field)] = field
        _require(isinstance(field["type"], str) and bool(field["type"].strip()), "Field type required")
        if complete:
            _require(field["type"] != "unknown" and field["owner"] is not None,
                     "Unknown field type/owner cannot become complete supervision")
        _require(field["owner"] is None or isinstance(field["owner"], str)
                 and field["owner"] in groups, "Foreign form owner")
        box(field["writingBbox"])
        state(field, mixed=True)
        _require(isinstance(field["labels"], list), "Field labels array required")
        for label in field["labels"]:
            _keys(label, "id text", "Label")
            identify(label)
            _require(isinstance(label["text"], str), "Raw label text required")
        _require(isinstance(field["writing"], list) and bool(field["writing"]),
                 "Complete writing regions required")
        for writing in field["writing"]:
            _keys(writing, "id bbox state value", "Writing region")
            identify(writing)
            box(writing["bbox"])
            outer, inner = field["writingBbox"], writing["bbox"]
            _require(outer[0] <= inner[0] and outer[1] <= inner[1]
                     and inner[2] <= outer[2] and inner[3] <= outer[3],
                     "Writing region leaves its field writing envelope")
            state(writing)
        states = {w["state"] for w in field["writing"]}
        derived = ("unknown" if "unknown" in states else
                   "mixed" if len(states) > 1 else next(iter(states)))
        _require(field["state"] == derived, "Field state loses mixed or unknown writing regions")
        value = "\n".join(w["value"] for w in field["writing"] if w["state"] == "filled")
        _require(field["value"] == (value if derived in {"filled", "mixed"} else None),
                 "Field value differs from complete literal writing sequence")
    endpoints = set(fields) | set(groups)
    for relation in page["relations"]:
        _keys(relation, "id kind fromId toId", "Relation")
        identify(relation)
        _require(isinstance(relation["kind"], str) and bool(relation["kind"].strip()),
                 "Observed relation kind required")
        _require(isinstance(relation["fromId"], str) and isinstance(relation["toId"], str)
                 and relation["fromId"] in endpoints and relation["toId"] in endpoints
                 and relation["fromId"] != relation["toId"], "Foreign/self form relation")
        if relation["kind"] == "belongs_to":
            _require(relation["fromId"] in fields
                     and fields[relation["fromId"]]["owner"] == relation["toId"],
                     "Relation disagrees with field owner")
        elif relation["kind"] == "subgroup_of":
            _require(relation["fromId"] in groups
                     and groups[relation["fromId"]]["parentId"] == relation["toId"],
                     "Relation disagrees with group parent")
    return page


def form_diagnostic(raw, finish_reason, *, page_id, image_size):
    """Protocol diagnostics only: JSON character error rate is not OCR accuracy."""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result, "Duplicate form JSON key")
            result[key] = value
        return result

    try:
        _require(finish_reason == "stop", "Form completion did not finish normally")
        _require(isinstance(raw, str), "Complete raw form text required")
        page = validate_form(json.loads(raw, object_pairs_hook=unique))
        _require(page["pageId"] == page_id and page["imageSize"] == image_size,
                 "Form output metadata differs from the actual input page")
        return {"protocolValid": True, "declaredFieldCount": len(page["fields"]),
                "declaredUnknownStateCount": sum(f["state"] == "unknown" for f in page["fields"]),
                "sourceSemanticsAccepted": False}
    except ValueError as error:
        return {"protocolValid": False, "error": str(error), "sourceSemanticsAccepted": False}

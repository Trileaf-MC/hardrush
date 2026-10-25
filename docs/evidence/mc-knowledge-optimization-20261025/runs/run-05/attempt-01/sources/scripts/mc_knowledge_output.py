"""Pure rendering and whole-stdout budgets for mc-knowledge.

The budget counts Unicode code points, including JSON escaping and the final LF.
Evidence records are immutable, content-addressed JSON; no first-display state is
used. Rendering only slices an already acquired page, never re-runs a lookup.
"""
from bisect import bisect_right
from copy import deepcopy
import hashlib
import json
import os
import tempfile
from pathlib import Path


class OutputBudgetTooSmall(ValueError):
    def __init__(self, minimum, budget, warnings):
        super().__init__("输出预算无法容纳完整身份、范围、告警、续读位置和最少正文。")
        self.minimum = minimum
        self.budget = budget
        self.warnings = warnings


def _canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"


def collect_warnings(value):
    """Keep every warning, including nested decompiler warnings, verbatim."""
    warnings = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "warnings" and isinstance(child, list):
                warnings.extend(child)
            elif isinstance(child, (dict, list)):
                warnings.extend(collect_warnings(child))
    elif isinstance(value, list):
        for child in value:
            warnings.extend(collect_warnings(child))
    return warnings


def store_evidence(result, args):
    """Persist full available provenance with the original, unsqueezed response."""
    context = {key: str(value) if isinstance(value, Path) else value
               for key, value in vars(args).items()}
    record = {"schema": 1, "query": context, "result": result}
    metadata_path = result.get("decompiled", {}).get("path")
    if metadata_path:
        metadata_path = Path(metadata_path).with_name("source.json")
        try:
            record["decompiler_provenance"] = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            # The original lookup remains valid if a cache is removed concurrently.
            record["decompiler_provenance"] = {"unavailable": str(metadata_path)}
    payload = _canonical(record)
    identity = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    path = args.cache_dir / "evidence" / (identity + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    # Publish an already complete file using an atomic no-replace hard link.
    # Concurrent readers never observe a half-written content-addressed record.
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(payload)
    try:
        temporary.chmod(0o444)
        try:
            os.link(temporary, path)
        except FileExistsError:
            if path.read_text(encoding="utf-8") != payload:
                raise ValueError("内容寻址证据文件校验失败：" + str(path))
    finally:
        temporary.unlink()
    return {"sha256": identity, "path": str(path)}, record


def _visible_matches(text, candidates, start_char):
    lines = text.split("\n")
    if start_char:
        lines = lines[1:]
    result = []
    for line in lines:
        try:
            item = json.loads(line)
        except ValueError:
            continue
        if item in candidates:
            result.append(item)
    return result


def _cut(result, count):
    value = deepcopy(result)
    text = result["text"]
    if count >= len(text):
        return value
    selected = text[:count]
    start = result.get("returned_range", {"start_line": 1, "start_char": 0})
    # Offset table from the complete acquired page avoids counting a cut CR as
    # an entire CRLF. A final partial line has no following line boundary.
    pieces = text.splitlines(keepends=True)
    offsets = [0]
    for index, piece in enumerate(pieces):
        boundary = len(piece.splitlines(keepends=False)) == 0 or piece != piece.splitlines(keepends=False)[0]
        if index < len(pieces) - 1 or boundary:
            offsets.append(offsets[-1] + len(piece))
    row = bisect_right(offsets, count) - 1
    position = {"start_line": start["start_line"] + row,
                "start_char": count - offsets[row] + (start["start_char"] if row == 0 else 0)}
    value["text"] = selected
    if "returned_chars" in value:
        value["returned_chars"] = len(selected)
    if "matches" in value and "matches_representation" in value:
        value["matches"] = _visible_matches(selected, result["matches"], start["start_char"])
    value["returned_range"] = {"start_line": start["start_line"], "start_char": start["start_char"],
                               "end_line": position["start_line"], "end_char": position["start_char"]}
    extra = {key: child for key, child in (result.get("next") or {}).items()
             if key not in ("start_line", "start_char")}
    source = result.get("source", {})
    if source.get("kind") == "minecraft-wiki":
        extra["oldid"] = source["revision"]
        extra["toc" if source.get("section") == "toc" else "section"] = True if source.get("section") == "toc" else source.get("section")
    value["next"] = {**position, **extra}
    value["truncated"] = True
    value["output_truncated"] = True
    value["completeness"] = "partial"
    return value


def _compact(result, args):
    value = deepcopy(result)
    source = value.get("source")
    if source:
        # Full source paths, checksums and tool identity remain in evidence.
        discard = {"jar", "jar_sha256", "fetched_at", "revision_resolved_at", "cache_hit"}
        value["source"] = {key: child for key, child in source.items() if key not in discard}
        if "minecraft_version" in source:
            if source.get("jar_sha256"):
                value["source"]["jar_id"] = "sha256:" + source["jar_sha256"][:16]
            value["source"]["side"] = ("requested " + args.side + "; supplied JAR side unverified"
                                         if source.get("kind") == "supplied-jar" else args.side)
        else:
            value["source"]["edition"] = source.get("edition_scope", "unspecified; community documentation")
            value["source"]["side"] = "not applicable; community documentation"
            value["source"]["integrity"] = "revision-pinned" if source.get("revision") else "search snapshot"
    decompiled = value.get("decompiled")
    if decompiled:
        value["decompiled"] = {key: child for key, child in decompiled.items()
                               if key in ("class", "member", "descriptor", "view")}
    value.pop("cache_hit", None)
    if value.get("evidence"):
        value["evidence"]["id"] = "sha256:" + value["evidence"].pop("sha256")[:16]
    value["warnings"] = collect_warnings(result)
    return value


def _render(result, compact, elapsed_ms):
    value = deepcopy(result)
    body = value.pop("text", "") if compact else ""
    if compact:
        value["format"] = "compact"
    value["metrics"] = {"elapsed_ms": elapsed_ms, "body_chars": len(body if compact else value.get("text", "")), "output_chars": 0}
    for _ in range(20):
        rendered = json.dumps(value, ensure_ascii=False, **({"separators": (",", ":")} if compact else {"indent": 2})) + "\n"
        if compact:
            rendered += body + "\n"
        if value["metrics"]["output_chars"] == len(rendered):
            return rendered
        value["metrics"]["output_chars"] = len(rendered)
    raise ValueError("输出字符数无法收敛")


def render_output(result, args, elapsed_ms):
    """Render full JSON or a compact JSON header plus raw body within stdout's budget."""
    compact = args.format == "compact"
    bounded = args.max_output_chars is not None or compact
    budget = args.max_output_chars if args.max_output_chars is not None else 6000
    value = deepcopy(result)
    if not bounded and args.provenance == "reference":
        # Legacy JSON remains read-only and byte-for-byte structurally compatible;
        # a new artifact is only needed for compact/bounded/full provenance views.
        return _render(value, False, elapsed_ms)
    evidence, record = store_evidence(value, args)
    value["evidence"] = evidence
    if args.provenance == "full":
        value["provenance"] = {key: child for key, child in record.items() if key != "result"}
        value["provenance"]["source"] = deepcopy(result.get("source", {}))
    if compact:
        value = _compact(value, args)
    # Search-list legacy fields remain untouched in unbounded JSON. In bounded
    # responses, JSONL text is the lossless continuation stream (a single long
    # item can itself span pages). matches contains only complete visible items.
    if bounded and "matches" in value:
        if "text" not in value:
            raise ValueError("列表输出缺少可分页正文")
        if compact:
            value.pop("matches")
        else:
            value["matches"] = _visible_matches(value["text"], value["matches"],
                                                 value.get("returned_range", {}).get("start_char", 0))
        value["matches_representation"] = "JSONL in text; fragments continue with next"
    if bounded:
        if "matches" in result:
            value["truncated"] = value.get("truncated", False) or value.get("body_truncated", False)
        value["completeness"] = "partial" if value.get("truncated") or value.get("body_truncated") or value.get("matches_limited") else "complete"
        if not value.get("ok", True):
            value["completeness"] = "error"
        elif collect_warnings(result.get("decompiled", {})):
            value["completeness"] = "decompiler warnings; inspect warnings"
    rendered = _render(value, compact, elapsed_ms)
    if not bounded or len(rendered) <= budget:
        return rendered
    if "text" not in value or not value["text"]:
        raise OutputBudgetTooSmall(len(rendered), budget, collect_warnings(result))
    minimum_value = _cut(value, 1)
    best_minimum = _render(minimum_value, compact, elapsed_ms)
    minimum = min(len(best_minimum), len(rendered))
    # At a line boundary, a large end_char can reset to zero, so envelope size
    # is not strictly monotonic. The smallest success is at one character, a
    # line boundary, or the complete page. Check those boundaries exactly.
    offset = 0
    for piece in value["text"].splitlines(keepends=True):
        offset += len(piece)
        if offset >= len(value["text"]) or offset > minimum:
            break
        candidate = _render(_cut(value, offset), compact, elapsed_ms)
        if len(candidate) < minimum:
            best_minimum, minimum = candidate, len(candidate)
    if minimum > budget:
        raise OutputBudgetTooSmall(minimum, budget, collect_warnings(result))
    # Binary search finds a fitting prefix without re-running the acquisition.
    # A line-boundary minimum above is the fallback if metadata-size resets
    # make the serialized length non-monotonic near a boundary.
    low, high = 1, len(value["text"]) - 1
    best = best_minimum
    while low <= high:
        count = (low + high) // 2
        candidate = _render(_cut(value, count), compact, elapsed_ms)
        if len(candidate) <= budget:
            best, low = candidate, count + 1
        else:
            high = count - 1
    return best


def budget_error(error, args):
    value = {"ok": False, "error": {"code": "OUTPUT_BUDGET_TOO_SMALL", "message": str(error),
                                    "budget": error.budget, "minimum_required_chars": error.minimum},
             "warnings": error.warnings}
    rendered = json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n"
    # An arbitrarily tiny stdout cannot physically carry its own error. Empty
    # stdout still respects the budget; stderr carries the complete explanation.
    return rendered if len(rendered) <= error.budget else "", rendered


def render_failure(result, args, error):
    """Report evidence/render failures without trying to write another artifact."""
    failure = {"ok": False, "error": {"code": "OUTPUT_FAILED", "message": str(error)},
               "warnings": collect_warnings(result), "completeness": "error"}
    if not result.get("ok", True):
        failure["lookup_error"] = result["error"]
    rendered = _render(failure, args.format == "compact", 0)
    budget = args.max_output_chars if args.max_output_chars is not None else (6000 if args.format == "compact" else None)
    return rendered if budget is None or len(rendered) <= budget else "", rendered

"""Bounded, revision-pinned reads of the community Minecraft Wiki (stdlib only)."""

from bisect import bisect_right
from datetime import datetime, timezone
import hashlib
import html
import json
from pathlib import Path
import re
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen


USER_AGENT = "HardRush-MinecraftKnowledge/1.0 (read-only research CLI)"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
LATEST_CACHE_SECONDS = 24 * 60 * 60


class WikiError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def _now():
    return datetime.now(timezone.utc).isoformat()


def _key(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _title_key(title):
    return title.replace("_", " ").strip().casefold()


def _clean_title(value):
    return html.unescape(re.sub(r"<[^>]+>", "", str(value)))


def _read_cache(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except (ValueError, OSError) as error:
        raise WikiError("WIKI_CACHE_INVALID", f"Wiki 缓存无法读取：{path}；使用 --refresh 重新获取。") from error
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise WikiError("WIKI_CACHE_INVALID", f"Wiki 缓存格式不正确：{path}；使用 --refresh 重新获取。")
    return value


def _write_cache(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".wiki-", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump({"schema": 1, **value}, stream, ensure_ascii=False)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _request_json(base_url, parameters, timeout):
    query = {"format": "json", "formatversion": 2, **parameters}
    request = Request(base_url + "/api.php?" + urlencode(query), headers={
        "User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urlopen(request, timeout=timeout) as response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
        if len(payload) > MAX_RESPONSE_BYTES:
            raise WikiError("WIKI_RESPONSE_TOO_LARGE", "Wiki 响应超过 4 MiB；请缩小查询范围。")
        result = json.loads(payload.decode("utf-8"))
    except HTTPError as error:
        error.close()
        raise WikiError("WIKI_HTTP_ERROR", f"Wiki HTTP {error.code}；可稍后重试，或使用 --offline 读取已有缓存。") from error
    except (URLError, TimeoutError, OSError) as error:
        raise WikiError("WIKI_NETWORK_ERROR", "无法连接 Minecraft Wiki API；检查网络，或使用 --offline 读取已有缓存。") from error
    except (UnicodeError, ValueError) as error:
        raise WikiError("WIKI_RESPONSE_INVALID", "Wiki 未返回有效 JSON；可能是代理或访问限制页面。") from error
    if not isinstance(result, dict):
        raise WikiError("WIKI_RESPONSE_INVALID", "Wiki JSON 根节点不是对象。")
    if "error" in result:
        issue = result["error"]
        raise WikiError("WIKI_API_ERROR", f"Wiki API：{issue.get('code', 'unknown')}：{issue.get('info', '')}")
    return result


def _positive(value, name, upper):
    if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= upper:
        raise WikiError("WIKI_ARGUMENT_INVALID", f"{name} 必须在 1–{upper} 之间。")
    return value


def slice_text(text, start_line=1, start_char=0, max_lines=80, max_chars=5000):
    """Return a bounded exact slice; next positions cannot lose a long line's tail.

    Lines are 1-based; character offsets are 0-based and end positions exclusive.
    The budget applies to the text field, not the surrounding provenance JSON.
    """
    _positive(start_line, "start_line", 10_000_000)
    _positive(max_lines, "max_lines", 10000)
    _positive(max_chars, "max_chars", 1_000_000)
    if not isinstance(start_char, int) or isinstance(start_char, bool) or start_char < 0:
        raise WikiError("WIKI_ARGUMENT_INVALID", "start_char 必须是非负整数。")
    lines = text.splitlines(keepends=True)
    offsets = [0]
    for line in lines:
        offsets.append(offsets[-1] + len(line))
    if start_line > len(lines) + 1:
        raise WikiError("WIKI_RANGE_INVALID", f"起始行超过正文范围（{len(lines)} 行）。")
    if start_line == len(lines) + 1:
        if start_char:
            raise WikiError("WIKI_RANGE_INVALID", "正文末尾的 start_char 必须为 0。")
        start = len(text)
    else:
        if start_char >= len(lines[start_line - 1]) and start_char != 0:
            raise WikiError("WIKI_RANGE_INVALID", "start_char 超出起始行；请使用返回的 next 位置。")
        start = offsets[start_line - 1] + start_char
    end_by_lines = offsets[min(len(lines), start_line - 1 + max_lines)]
    end = min(len(text), end_by_lines, start + max_chars)

    def position(offset):
        row = min(bisect_right(offsets, offset) - 1, len(lines))
        return {"start_line": row + 1, "start_char": offset - offsets[row]}

    continuation = position(end) if end < len(text) else None
    end_position = position(end)
    return {
        "text": text[start:end],
        "total_lines": len(lines),
        "total_chars": len(text),
        "returned_chars": end - start,
        "returned_range": {"start_line": start_line, "start_char": start_char,
                           "end_line": end_position["start_line"], "end_char": end_position["start_char"]},
        "truncated": end < len(text),
        "next": continuation,
    }


def _parsed_metadata(result):
    parsed = result.get("parse")
    if (not isinstance(parsed, dict) or not isinstance(parsed.get("revid"), int) or parsed["revid"] <= 0
            or not isinstance(parsed.get("title"), str)):
        raise WikiError("WIKI_RESPONSE_INVALID", "Wiki 响应缺少有效页面修订号。")
    sections = parsed.get("sections")
    if not isinstance(sections, list):
        raise WikiError("WIKI_RESPONSE_INVALID", "Wiki 响应缺少章节目录；API 的 sections 能力可能发生变化。")
    return {"title": parsed["title"], "revision": parsed["revid"], "sections": sections,
            "fetched_at": _now()}


def _fresh(value):
    try:
        age = (datetime.now(timezone.utc) - datetime.fromisoformat(value["fetched_at"])).total_seconds()
        return 0 <= age <= LATEST_CACHE_SECONDS
    except (KeyError, TypeError, ValueError):
        return False


def _metadata(title, oldid, lang_dir, base_url, offline, refresh, timeout):
    pointer_path = lang_dir / "titles" / (_key(_title_key(title)) + ".json")
    revision_path = lang_dir / "revisions" / str(oldid) / "toc.json" if oldid else None
    cached = None if refresh else _read_cache(revision_path or pointer_path)
    if cached and (not isinstance(cached.get("revision"), int) or cached["revision"] <= 0
                   or not isinstance(cached.get("title"), str) or not isinstance(cached.get("sections"), list)):
        raise WikiError("WIKI_CACHE_INVALID", "Wiki 目录缓存缺少页面身份；使用 --refresh 重新获取。")
    if cached and (oldid or offline or _fresh(cached)):
        if oldid and _title_key(cached.get("title", "")) != _title_key(title):
            raise WikiError("WIKI_REVISION_MISMATCH", "该 oldid 属于其他页面；请使用修订号对应的规范标题。")
        return cached, True
    if offline:
        raise WikiError("WIKI_CACHE_MISS", "离线缓存中没有该页面／修订；先联网读取，或改用本机原版源码。")
    parameters = {"action": "parse", "prop": "sections|revid"}
    if oldid:
        parameters["oldid"] = oldid
    else:
        parameters.update(page=title, redirects=1)
    result = _parsed_metadata(_request_json(base_url, parameters, timeout))
    if oldid and (result["revision"] != oldid or _title_key(result["title"]) != _title_key(title)):
        raise WikiError("WIKI_REVISION_MISMATCH", "Wiki 返回的页面或修订号与请求不一致。")
    _write_cache(lang_dir / "revisions" / str(result["revision"]) / "toc.json", result)
    if not oldid:
        _write_cache(pointer_path, result)
        canonical_path = lang_dir / "titles" / (_key(_title_key(result["title"])) + ".json")
        if canonical_path != pointer_path:
            _write_cache(canonical_path, result)
    return result, False


def _resolve_section(selection, sections):
    if selection is None or str(selection) == "0":
        return "0", "Lead"
    selection = str(selection)
    if re.fullmatch(r"(?:\d+|T-\d+)", selection):
        matching = [item for item in sections if str(item.get("index")) == selection]
    else:
        matching = [item for item in sections if _clean_title(item.get("line", "")).casefold() == selection.casefold()]
    if len(matching) != 1:
        hint = "; ".join(f"{item.get('index')}: {_clean_title(item.get('line', ''))}" for item in matching[:10])
        raise WikiError("WIKI_SECTION_AMBIGUOUS" if matching else "WIKI_SECTION_NOT_FOUND",
                        "章节名称不唯一，请按 --toc 返回的 index 查询：" + hint if matching
                        else "没有找到该章节；先用 --toc 查看当前修订的章节 index。")
    item = matching[0]
    return str(item["index"]), _clean_title(item.get("line", ""))


def _section(metadata, index, lang_dir, base_url, offline, refresh, timeout):
    path = lang_dir / "revisions" / str(metadata["revision"]) / ("section-" + _key(index) + ".json")
    value = None if refresh else _read_cache(path)
    if value is not None:
        if value.get("revision") != metadata["revision"] or value.get("section") != index or not isinstance(value.get("text"), str):
            raise WikiError("WIKI_CACHE_INVALID", "Wiki 正文缓存身份不一致；使用 --refresh 重新获取。")
        return value, True
    if offline:
        raise WikiError("WIKI_CACHE_MISS", "该修订的章节正文尚未缓存；离线模式不会请求网络。")
    response = _request_json(base_url, {"action": "parse", "oldid": metadata["revision"],
                                       "section": index, "prop": "wikitext|revid"}, timeout)
    parsed = response.get("parse", {})
    content = parsed.get("wikitext")
    if isinstance(content, dict):
        content = content.get("*")
    if parsed.get("revid") != metadata["revision"] or not isinstance(content, str):
        raise WikiError("WIKI_REVISION_MISMATCH", "章节响应缺少正文，或修订号与目录不一致。")
    value = {"title": metadata["title"], "revision": metadata["revision"], "section": index,
             "text": content, "fetched_at": _now()}
    _write_cache(path, value)
    return value, False


def wiki_query(args, cache_dir):
    """Read Wiki metadata/text. cache_dir is the Wiki-specific cache directory.

    Args: title; search/toc/section/oldid/lang/offline/refresh/start_line/start_char/
    max_lines/max_chars/limit/timeout are optional attributes with CLI defaults.
    Raw wikitext intentionally preserves edition and conditional template markers.
    """
    title = getattr(args, "title", "").strip()
    if not title or len(title) > 300:
        raise WikiError("WIKI_ARGUMENT_INVALID", "页面标题／搜索词不能为空且不得超过 300 字符。")
    lang = getattr(args, "lang", "en")
    if not re.fullmatch(r"[a-z]{2,3}(?:-[a-z]{2,4})?", lang):
        raise WikiError("WIKI_ARGUMENT_INVALID", "lang 必须是 Wiki 语言代码，例如 en、zh。")
    base_url = "https://minecraft.wiki" if lang == "en" else f"https://{lang}.minecraft.wiki"
    lang_dir = Path(cache_dir) / lang
    offline, refresh = bool(getattr(args, "offline", False)), bool(getattr(args, "refresh", False))
    if offline and refresh:
        raise WikiError("WIKI_ARGUMENT_INVALID", "--offline 与 --refresh 不能同时使用。")
    timeout = getattr(args, "timeout", 20)
    if not isinstance(timeout, (int, float)) or not 0 < timeout <= 120:
        raise WikiError("WIKI_ARGUMENT_INVALID", "timeout 必须大于 0 且不超过 120 秒。")
    oldid = getattr(args, "oldid", None)
    if oldid is not None:
        _positive(oldid, "oldid", 10**12)
    search, toc = bool(getattr(args, "search", False)), bool(getattr(args, "toc", False))
    selection = getattr(args, "section", None)
    if sum((search, toc, selection is not None)) > 1 or (search and oldid):
        raise WikiError("WIKI_ARGUMENT_INVALID", "search、toc、section 应单独使用；搜索不能指定 oldid。")
    warnings = ["Minecraft Wiki 是社区资料；保留 Java/Bedrock 与条件标记，页面修订号不代表游戏版本已核实。"]

    if search:
        limit = _positive(getattr(args, "limit", 5), "limit", 20)
        path = lang_dir / "search" / (_key(f"{title}\n{limit}") + ".json")
        record = None if refresh else _read_cache(path)
        hit = bool(record and (offline or _fresh(record)))
        if not hit:
            if offline:
                raise WikiError("WIKI_CACHE_MISS", "没有该搜索的离线缓存；可以直接读取已缓存页面。")
            response = _request_json(base_url, {"action": "query", "list": "search", "srsearch": title,
                                               "srlimit": limit, "srprop": "snippet", "srinfo": "totalhits"}, timeout)
            query = response.get("query", {})
            items = query.get("search")
            if not isinstance(items, list):
                raise WikiError("WIKI_RESPONSE_INVALID", "Wiki 搜索响应缺少结果列表。")
            record = {"items": items[:limit], "total_hits": query.get("searchinfo", {}).get("totalhits"),
                      "fetched_at": _now()}
            _write_cache(path, record)
        content = "\n".join(f"{item['title']} | {base_url}/w/{quote(item['title'].replace(' ', '_'), safe='')}\n"
                            f"{_clean_title(item.get('snippet', ''))}" for item in record["items"])
        source = {"kind": "minecraft-wiki-search", "query": title, "language": lang,
                  "url": base_url + "/w/Special:Search?" + urlencode({"search": title}),
                  "fetched_at": record["fetched_at"], "cache_hit": hit,
                  "result_count": len(record["items"]), "total_hits": record.get("total_hits")}
        if record.get("total_hits", 0) and record["total_hits"] > len(record["items"]):
            warnings.append("搜索只返回首批候选；请细化搜索词或直接读取具体页面。")
    else:
        metadata, metadata_hit = _metadata(title, oldid, lang_dir, base_url, offline, refresh, timeout)
        source = {"kind": "minecraft-wiki", "page": metadata["title"], "revision": metadata["revision"],
                  "language": lang, "url": base_url + "/w/" + quote(metadata["title"].replace(" ", "_"), safe="")
                  + "?oldid=" + str(metadata["revision"]), "edition_scope": "mixed; unfiltered",
                  "revision_resolved_at": metadata["fetched_at"]}
        if toc:
            content = "0 | Lead\n" + "\n".join(f"{item['index']} | {_clean_title(item.get('line', ''))}"
                                                 for item in metadata["sections"])
            source.update(section="toc", fetched_at=metadata["fetched_at"], cache_hit=metadata_hit)
        else:
            index, name = _resolve_section(selection, metadata["sections"])
            record, hit = _section(metadata, index, lang_dir, base_url, offline, refresh, timeout)
            content = record["text"]
            source.update(section=index, section_title=name, format="wikitext",
                          fetched_at=record["fetched_at"], cache_hit=metadata_hit and hit)
        warnings.append("继续查询／分页时携带 source.revision 为 --oldid，以固定同一页面修订。")

    result = slice_text(content, getattr(args, "start_line", 1), getattr(args, "start_char", 0),
                        getattr(args, "max_lines", 80), getattr(args, "max_chars", 5000))
    if result["next"] is not None and not search:
        result["next"]["oldid"] = source["revision"]
        result["next"]["toc" if toc else "section"] = True if toc else source["section"]
    result.update(source=source, warnings=warnings)
    return result

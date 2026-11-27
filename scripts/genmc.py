#!/usr/bin/env python3
"""Talk to a paired genMC build studio from a script, and export its build into a lobby pack source directory.

The genMC MCP relay (https://genmc.app/api/mcp) never returns .schem bytes to an agent, and a whole build as JSON is far
too large for an agent's context. So the export goes through this script: it fetches the JSON export over the relay,
writes `build.schem` (Sponge v3) and records the build's minimum corner as `origin` in `build.hardrush.json`
(docs/plan/建筑清单与大厅包构建.md, section 1.1).

    scripts/genmc.py pair <code>            pair with the studio tab showing this code
    scripts/genmc.py resume                 adopt a resumeKey read from stdin (after pairing through the MCP tools)
    scripts/genmc.py status
    scripts/genmc.py call <tool> [json|@file] [--out FILE] [--image FILE] [--limit N]
    scripts/genmc.py docs [article] [--query WORDS]     the genMC knowledge base (agent-guide, agent-tools, …)
    scripts/genmc.py upload <gmv1.json> --label TEXT [--merge]
    scripts/genmc.py export lobby-packs/<id>

`call` reaches the studio's tools (build_*); names starting with genmc_ go to the relay itself. `upload` sends blocks
computed locally (GMV1 JSON: {"format":"gmv1","palette":[ids],"parts":[paths],"runs":[[x,y,z,length,palette,part],…]}),
the way to build when the studio's program sandbox will not start, and the natural one when a generator script is the
pack's source.

Only the standard library is used. The resume key lives in .local/genmc/session.json (not version-controlled).
"""
import argparse
import base64
import gzip
import json
import re
import struct
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SESSION = ROOT / ".local/genmc/session.json"
ENDPOINT = "https://genmc.app/api/mcp"
# Minecraft 26.3. The importer refuses files newer than the server and upgrades older ones.
GAME_VERSION = "26.3"
DATA_VERSION = 5023
# The server's default hardrush.lobby.maxSize. A lobby meant to ship with the mod has to fit the default.
MAX_AXIS = 256
CHUNK_CHARS = 500_000  # build_upload_chunk takes up to 600,000 characters a piece


class Relay:
    def __init__(self):
        self.session = None
        self.next_id = 0

    def _post(self, body, timeout=630):
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "User-Agent": "genmc-script"}
        if self.session:
            headers["mcp-session-id"] = self.session
        request = urllib.request.Request(ENDPOINT, data=json.dumps(body).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            self.session = response.headers.get("mcp-session-id") or self.session
            raw = response.read().decode("utf-8")
        if raw.lstrip().startswith("{"):
            return json.loads(raw)
        reply = None
        for line in raw.splitlines():
            if line.startswith("data:"):
                try:
                    message = json.loads(line[5:])
                except ValueError:
                    continue
                if "result" in message or "error" in message:
                    reply = message
        return reply

    def _open(self):
        if self.session:
            return
        self._post({"jsonrpc": "2.0", "id": 0, "method": "initialize", "params": {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "hardrush-genmc-script", "version": "1"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def tool(self, name, arguments):
        """Calls a relay tool; returns its content blocks."""
        self._open()
        self.next_id += 1
        reply = self._post({"jsonrpc": "2.0", "id": self.next_id, "method": "tools/call",
                            "params": {"name": name, "arguments": arguments}})
        if reply is None:
            sys.exit(f"genMC 没有返回结果（{name}）")
        if "error" in reply:
            sys.exit(f"genMC 报错（{name}）：{json.dumps(reply['error'], ensure_ascii=False)}")
        return reply["result"].get("content", [])

    def studio(self, name, arguments):
        """Calls a tool of the paired studio tab."""
        return self.tool("genmc_call", {"name": name, "input": arguments, "resumeKey": resume_key()})


def resume_key():
    if not SESSION.is_file():
        sys.exit("还没有配对：先运行 scripts/genmc.py pair <配对码>，或 scripts/genmc.py resume")
    return json.loads(SESSION.read_text())["resumeKey"]


def save_key(key):
    SESSION.parent.mkdir(parents=True, exist_ok=True)
    SESSION.write_text(json.dumps({"resumeKey": key}))
    SESSION.chmod(0o600)


def text_of(blocks):
    return "\n".join(b.get("text", "") for b in blocks if b.get("type") == "text")


def as_json(blocks, what):
    try:
        return json.loads(text_of(blocks))
    except ValueError:
        sys.exit(f"{what} 返回的不是 JSON：{text_of(blocks)[:400]}")


# --- NBT (just what a Sponge schematic needs) -------------------------------------------------------------------------

def _name(name):
    data = name.encode("utf-8")
    return struct.pack(">H", len(data)) + data


class Short(int): pass
class IntArray(list): pass


def _payload(value):
    if isinstance(value, Short):
        return 2, struct.pack(">h", value)
    if isinstance(value, int):
        return 3, struct.pack(">i", value)
    if isinstance(value, (bytes, bytearray)):
        return 7, struct.pack(">i", len(value)) + bytes(value)
    if isinstance(value, IntArray):
        return 11, struct.pack(">i", len(value)) + b"".join(struct.pack(">i", v) for v in value)
    if isinstance(value, list):  # only empty lists are written
        return 9, b"\x00" + struct.pack(">i", 0)
    if isinstance(value, dict):
        out = b""
        for key, child in value.items():
            tag, data = _payload(child)
            out += bytes([tag]) + _name(key) + data
        return 10, out + b"\x00"
    raise TypeError(type(value))


def nbt(root):
    tag, data = _payload(root)
    return bytes([tag]) + _name("") + data


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return out


def schematic(blocks):
    """blocks: [[x, y, z, "id[states]"], ...] → (.schem bytes, minimum corner, size)."""
    if not blocks:
        sys.exit("建筑是空的，没有可导出的方块")
    lo = [min(b[i] for b in blocks) for i in range(3)]
    hi = [max(b[i] for b in blocks) for i in range(3)]
    w, h, l = (hi[i] - lo[i] + 1 for i in range(3))
    if w > MAX_AXIS or l > MAX_AXIS:
        sys.exit(f"建筑水平尺寸 {w}×{l} 超过大厅上限 {MAX_AXIS}")
    palette = {"minecraft:air": 0}
    cells = [0] * (w * h * l)
    for x, y, z, state in blocks:
        if ":" not in state.split("[", 1)[0]:
            state = "minecraft:" + state
        index = palette.setdefault(state, len(palette))
        cells[(x - lo[0]) + (z - lo[2]) * w + (y - lo[1]) * w * l] = index
    data = bytearray()
    for index in cells:
        data += varint(index)
    root = {"Schematic": {
        "Version": 3, "DataVersion": DATA_VERSION,
        "Width": Short(w), "Height": Short(h), "Length": Short(l),
        "Offset": IntArray([0, 0, 0]),
        "Blocks": {"Palette": palette, "Data": data, "BlockEntities": []},
    }}
    return gzip.compress(nbt(root), mtime=0), lo, (w, h, l)


def manifest_text(manifest):
    """Indented like a hand-written manifest: one marker or sign per line, coordinates on one line."""
    lines = ["{"]
    keys = list(manifest)
    for k, key in enumerate(keys):
        value, comma = manifest[key], "," if k < len(keys) - 1 else ""
        if isinstance(value, list) and value and all(isinstance(item, dict) for item in value):
            lines.append(f"  {json.dumps(key)}: [")
            lines += [f"    {json.dumps(item, ensure_ascii=False)}{',' if i < len(value) - 1 else ''}" for i, item in enumerate(value)]
            lines.append(f"  ]{comma}")
        else:
            lines.append(f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}{comma}")
    return "\n".join(lines) + "\n}\n"


# --- commands ---------------------------------------------------------------------------------------------------------

def cmd_pair(args):
    relay = Relay()
    result = as_json(relay.tool("genmc_pair", {"code": args.code}), "genmc_pair")
    if not result.get("resumeKey"):
        sys.exit(f"配对失败：{json.dumps(result, ensure_ascii=False)[:400]}")
    save_key(result["resumeKey"])
    print(json.dumps({"paired": True, "page": result.get("page"), "tools": len(result.get("tools", []))}, ensure_ascii=False))


def cmd_resume(args):
    key = sys.stdin.read().strip()
    if not key:
        sys.exit("从标准输入读不到 resumeKey")
    save_key(key)
    cmd_status(args)


def cmd_status(args):
    relay = Relay()
    result = as_json(relay.tool("genmc_status", {"resumeKey": resume_key()}), "genmc_status")
    print(json.dumps({k: result.get(k) for k in ("paired", "reachable", "page")}, ensure_ascii=False))


def cmd_call(args):
    raw = args.input or "{}"
    arguments = json.loads(Path(raw[1:]).read_text() if raw.startswith("@") else raw)
    relay = Relay()
    if args.tool.startswith("genmc_"):
        if args.tool in ("genmc_status", "genmc_call"):
            arguments.setdefault("resumeKey", resume_key())
        blocks = relay.tool(args.tool, arguments)
    else:
        blocks = relay.studio(args.tool, arguments)
    text = text_of(blocks)
    if args.out:
        Path(args.out).write_text(text)
        print(f"已写入 {args.out}（{len(text)} 字符）")
    else:
        print(text if len(text) <= args.limit else text[:args.limit] + f"\n…（共 {len(text)} 字符，用 --out 保存全文）")
    images = [b for b in blocks if b.get("type") == "image"]
    if images and args.image:
        Path(args.image).write_bytes(base64.b64decode(images[0]["data"]))
        print(f"图片已写入 {args.image}")
    elif images:
        print(f"（返回了 {len(images)} 张图片，用 --image FILE 保存第一张）")


def cmd_docs(args):
    arguments = {"query": args.query} if args.query else {"article": args.article} if args.article else {}
    text = text_of(Relay().tool("genmc_docs", arguments))
    print(text if len(text) <= args.limit else text[:args.limit] + f"\n…（共 {len(text)} 字符，用 --limit 调大）")


def guards(relay):
    """The revisions every mutation must carry, read fresh."""
    state = as_json(relay.studio("build_get_state", {}), "build_get_state")
    return {"expectedProjectId": state["projectId"], "expectedRevision": state["revision"],
            "expectedContextRevision": state["contextRevision"]}


def cmd_upload(args):
    import uuid
    source = json.loads(Path(args.file).read_text())
    if source.get("format") != "gmv1":
        sys.exit('上传文件必须是 {"format": "gmv1", "palette": […], "parts": […], "runs": […]}')
    payload = base64.b64encode(gzip.compress(json.dumps(source, separators=(",", ":")).encode())).decode()
    pieces = [payload[i:i + CHUNK_CHARS] for i in range(0, len(payload), CHUNK_CHARS)]
    if len(pieces) > 64:
        sys.exit(f"建筑太大：需要 {len(pieces)} 段，genMC 一次上传最多 64 段")
    relay = Relay()
    upload_id = str(uuid.uuid4())
    for index, piece in enumerate(pieces):
        relay.studio("build_upload_chunk", {"uploadId": upload_id, "index": index, "total": len(pieces), "data": piece})
    arguments = {"uploadId": upload_id, "label": args.label, "mode": "merge" if args.merge else "replace", **guards(relay)}
    text = text_of(relay.studio("build_import_upload", arguments))
    print(text if len(text) <= 1500 else text[:1500] + "\n…")


def check_game_version(relay):
    """genMC names blocks for one Minecraft release; the .schem claims DATA_VERSION, so the two must match."""
    try:
        listed = json.loads(text_of(relay.studio("build_list_blocks", {"query": "stone", "limit": 1})))
    except ValueError:
        return
    version = listed.get("version") if isinstance(listed, dict) else None
    if version and str(version) != GAME_VERSION:
        sys.exit(f"genMC 的方块目录是 Minecraft {version}，而本脚本按 {GAME_VERSION}（数据版本 {DATA_VERSION}）写文件；"
                 "先更新 scripts/genmc.py 里的 GAME_VERSION 和 DATA_VERSION")


def cmd_export(args):
    target = Path(args.pack)
    if not re.fullmatch(r"[a-z0-9_-]{1,32}", target.name):
        sys.exit(f"目录名（包 id）必须是 [a-z0-9_-]{{1,32}}：{target.name}")
    relay = Relay()
    check_game_version(relay)
    state = as_json(relay.studio("build_get_state", {}), "build_get_state")
    exported = as_json(relay.studio("build_export", {"format": "json", "expectedRevision": state["revision"]}), "build_export")
    build = json.loads(exported["text"])["build"]
    data, lo, size = schematic(build["blocks"])
    target.mkdir(parents=True, exist_ok=True)
    for other in ("build.litematic", "build.nbt"):
        if (target / other).exists():
            sys.exit(f"{target / other} 已存在；一个包只能有一个建筑文件")
    (target / "build.schem").write_bytes(data)
    manifest_path = target / "build.hardrush.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.is_file() else {
        "format": 1, "update_shapes": True, "markers": [], "signs": []}
    manifest["origin"] = lo
    manifest_path.write_text(manifest_text(manifest))
    print(json.dumps({"schem": str(target / "build.schem"), "blocks": len(build["blocks"]), "size": size,
                      "origin": lo, "revision": state["revision"], "project": state.get("projectId")}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    pair = sub.add_parser("pair"); pair.add_argument("code"); pair.set_defaults(run=cmd_pair)
    sub.add_parser("resume").set_defaults(run=cmd_resume)
    sub.add_parser("status").set_defaults(run=cmd_status)
    call = sub.add_parser("call")
    call.add_argument("tool"); call.add_argument("input", nargs="?")
    call.add_argument("--out"); call.add_argument("--image"); call.add_argument("--limit", type=int, default=6000)
    call.set_defaults(run=cmd_call)
    docs = sub.add_parser("docs")
    docs.add_argument("article", nargs="?"); docs.add_argument("--query"); docs.add_argument("--limit", type=int, default=20000)
    docs.set_defaults(run=cmd_docs)
    upload = sub.add_parser("upload")
    upload.add_argument("file"); upload.add_argument("--label", required=True)
    upload.add_argument("--merge", action="store_true", help="place over the current build instead of replacing it")
    upload.set_defaults(run=cmd_upload)
    export = sub.add_parser("export"); export.add_argument("pack"); export.set_defaults(run=cmd_export)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()

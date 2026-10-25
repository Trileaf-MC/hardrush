#!/usr/bin/env python3
"""Bounded, version-pinned vanilla Minecraft lookups; Python standard library only.

Local commands never download artifacts or launch Minecraft. Generated sources live
under .local/mc-knowledge; stdout defaults to JSON with provenance and metrics.
Use --format compact for a metadata header followed by the exact raw body.
"""
import argparse
from bisect import bisect_right
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = 2


def next_command(args, command=None, class_name=None, descriptor=None):
    """Executable recovery command with the exact lookup environment preserved."""
    argv = ["python3", str(Path(__file__).resolve())]
    for flag in ("project", "version", "gradle_home", "cache_dir", "java_home", "vineflower", "jar", "side"):
        value = getattr(args, flag, None)
        if flag == "version" and value is None:
            value = project_version(args.project)
        if value is not None:
            argv.extend(["--" + flag.replace("_", "-"), str(value)])
    if getattr(args, "offline", False):
        argv.append("--offline")
    for flag in ("format", "max_output_chars", "provenance", "max_chars", "max_lines", "limit", "timeout"):
        value = getattr(args, flag, None)
        if value is not None:
            argv.extend(["--" + flag.replace("_", "-"), str(value)])
    argv.append(command or args.command)
    if class_name is not None:
        argv.append(class_name)
        if getattr(args, "member", None):
            argv.extend(["--member", args.member])
        if (command or args.command) == "source":
            selected = descriptor if descriptor is not None else getattr(args, "descriptor", None)
            if selected:
                argv.extend(["--descriptor", selected])
            if getattr(args, "bytecode", False):
                argv.append("--bytecode")
    return {"argv": argv, "shell": shlex.join(argv)}
DECOMPILE_FLAGS = ["--log-level=WARN", "--decompiler-comments=1"]


class KnowledgeError(RuntimeError):
    def __init__(self, code, message, **details):
        super().__init__(message)
        self.code, self.details = code, details


def digest(path, algorithm="sha256"):
    result = hashlib.new(algorithm)
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    temporary.replace(path)


def write_text(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(value)
    temporary.replace(path)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def project_version(root):
    path = root / "gradle.properties"
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            match = re.fullmatch(r"\s*minecraft_version\s*=\s*([^\s#]+)\s*", line)
            if match:
                return match[1]
    raise KnowledgeError("PROJECT_VERSION_MISSING", "找不到项目 minecraft_version；请指定 --version。")


def page_text(text, args):
    """Lossless continuation even when one Java/JSON line exceeds max_chars."""
    lines = text.splitlines(keepends=True)
    if not lines:
        lines = [""]
    if args.start_line > len(lines):
        raise KnowledgeError("RANGE_INVALID", "start-line 超过文件行数。", total_lines=len(lines))
    offsets = [0]
    for line in lines:
        offsets.append(offsets[-1] + len(line))
    start = offsets[args.start_line - 1] + args.start_char
    if args.start_char > len(lines[args.start_line - 1]):
        raise KnowledgeError("RANGE_INVALID", "start-char 超过当前行长度。")
    start_row = min(bisect_right(offsets, start) - 1, len(lines)) if text else 0
    start_position = {"start_line": start_row + 1, "start_char": start - offsets[start_row]}
    line_end = offsets[min(len(lines), start_row + args.max_lines)]
    end = min(len(text), start + args.max_chars, line_end)
    if end < start:
        raise KnowledgeError("RANGE_INVALID", "无效的分页范围。")
    end_row = min(bisect_right(offsets, end) - 1, len(lines))
    position = {"start_line": end_row + 1, "start_char": end - offsets[end_row]}
    next_position = position if end < len(text) else None
    return {"text": text[start:end], "total_lines": len(lines), "total_chars": len(text),
            "returned_range": {**start_position,
                               "end_line": position["start_line"], "end_char": position["start_char"]},
            "truncated": end < len(text), "next": next_position}


def run_java(command, timeout):
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        raise KnowledgeError("JAVA_TIMEOUT", "Java 查询超时；可调整 --timeout。") from None
    except OSError as error:
        raise KnowledgeError("JAVA_UNAVAILABLE", str(error)) from None
    if result.returncode:
        raise KnowledgeError("JAVA_FAILED", "Java 工具执行失败。", diagnostic=(result.stderr + result.stdout)[-2000:])
    return result.stdout, result.stderr


def java_tools(args, required):
    candidates = []
    if args.java_home:
        candidates.append(Path(args.java_home))
    else:
        pointer = args.project / ".local/toolchains/java-home.txt"
        if pointer.is_file():
            candidates.append(Path(pointer.read_text().strip()))
        if os.environ.get("JAVA_HOME"):
            candidates.append(Path(os.environ["JAVA_HOME"]))
        found = shutil.which("javap")
        if found:
            candidates.append(Path(found).resolve().parent.parent)
    failures = []
    for home in candidates:
        java, javap = home / "bin/java", home / "bin/javap"
        if os.name == "nt":
            java, javap = java.with_suffix(".exe"), javap.with_suffix(".exe")
        if not java.is_file() or not javap.is_file():
            continue
        try:
            stdout, stderr = run_java([str(java), "-version"], args.timeout)
            match = re.search(r'version "(\d+)', stdout + stderr)
            if match and int(match[1]) >= required:
                return {"java": str(java), "javap": str(javap), "major": int(match[1])}
            failures.append(str(home))
        except KnowledgeError:
            failures.append(str(home))
    raise KnowledgeError("JDK_MISSING", f"需要 JDK {required}+（含 javap）；请指定 --java-home。", checked=failures)


def vineflower(args):
    if args.vineflower:
        path = Path(args.vineflower).expanduser()
        candidates = [path] if path.is_file() else []
    else:
        base = args.gradle_home / "caches/modules-2/files-2.1/org.vineflower/vineflower"
        candidates = list(base.glob("*/*/vineflower-*.jar"))
        candidates = [p for p in candidates if not re.search(r"-(sources|javadoc)\.jar$", p.name)]
        candidates.sort(key=lambda p: tuple(int(x) for x in re.findall(r"\d+", p.parent.parent.name)), reverse=True)
    if not candidates:
        raise KnowledgeError("VINEFLOWER_MISSING", "Gradle 缓存中没有 Vineflower；请用 --vineflower 指定已有 JAR。")
    path = candidates[0].resolve()
    with zipfile.ZipFile(path) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    match = re.search(r"Implementation-Version:\s*([^\r\n]+)", manifest)
    return {"path": str(path), "version": match[1] if match else path.stem,
            "sha256": digest(path)}


class Vanilla:
    def __init__(self, args):
        self.args = args
        self.version = args.version or project_version(args.project)
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+ -]*", self.version) or ".." in self.version:
            raise KnowledgeError("VERSION_INVALID", "无效的版本标识。")
        folder = args.gradle_home / "caches/fabric-loom" / self.version
        expected = None
        self.integrity = "content-addressed; upstream checksum unavailable"
        if args.jar:
            self.jar = Path(args.jar).expanduser().resolve()
            kind = "supplied-jar"
        elif args.side == "server":
            self.jar = folder / "minecraft-extracted_server.jar"
            wrapper = folder / "minecraft-server.jar"
            if wrapper.is_file():
                with zipfile.ZipFile(wrapper) as archive:
                    rows = [row.split("\t") for row in archive.read("META-INF/versions.list").decode().splitlines()]
                    rows = [row for row in rows if len(row) == 3 and row[1] == self.version]
                    if len(rows) != 1:
                        raise KnowledgeError("SERVER_BUNDLE_MISMATCH", "服务端 bundle 未声明唯一的目标版本。")
                    expected, _, member = rows[0]
                    if not self.jar.exists():
                        self.jar = args.cache_dir / "inputs" / expected / "server.jar"
                        if not self.jar.exists():
                            self.jar.parent.mkdir(parents=True, exist_ok=True)
                            with tempfile.NamedTemporaryFile(dir=self.jar.parent, delete=False) as stream:
                                temporary = Path(stream.name)
                                stream.write(archive.read("META-INF/versions/" + member))
                            temporary.replace(self.jar)
                self.integrity = "matches server bundle SHA-256"
            kind = "vanilla-server"
        else:
            self.jar = folder / "minecraft-client.jar"
            kind = "vanilla-client (includes shared code)"
        if not self.jar.is_file():
            raise KnowledgeError("JAR_MISSING", "目标版本原版 JAR 不在缓存中；先准备对应 Gradle 依赖或指定 --jar。", path=str(self.jar))
        self.sha256 = digest(self.jar)
        if expected and self.sha256 != expected:
            raise KnowledgeError("JAR_CHECKSUM_MISMATCH", "提取的服务端 JAR 与 bundle 校验值不一致。")
        with zipfile.ZipFile(self.jar) as archive:
            try:
                self.info = json.loads(archive.read("version.json"))
            except (KeyError, ValueError):
                raise KnowledgeError("JAR_IDENTITY_MISSING", "JAR 缺少可验证的 version.json。") from None
            if self.info.get("id") != self.version:
                raise KnowledgeError("VERSION_MISMATCH", "JAR 实际版本与请求版本不一致。", requested=self.version, actual=self.info.get("id"))
            self.entries = archive.namelist()
        self.classes = sorted(n[:-6].replace("/", ".") for n in self.entries
                              if n.startswith("net/minecraft/") and n.endswith(".class"))
        if not self.classes:
            raise KnowledgeError("NAMED_CLASSES_MISSING", "此入口需要可读的 net.minecraft 类名；旧版需先准备正确映射的 JAR。")
        self.bucket = args.cache_dir / "artifacts" / self.sha256
        self.provenance = {"edition": "Java", "minecraft_version": self.version, "kind": kind,
                           "jar": str(self.jar), "jar_sha256": self.sha256, "integrity": self.integrity}

    def resolve(self, query):
        normalized = query.removesuffix(".class").replace("/", ".")
        exact = [c for c in self.classes if c == normalized]
        if not exact:
            exact = [c for c in self.classes if c.rsplit(".", 1)[-1] == normalized]
        if len(exact) == 1:
            return exact[0]
        candidates = exact or [c for c in self.classes if normalized.lower() in c.lower()][:self.args.limit]
        raise KnowledgeError("CLASS_AMBIGUOUS" if exact else "CLASS_NOT_FOUND",
                             "请使用 find-class 返回的完整类名。", candidates=candidates,
                             next_commands=[next_command(self.args, class_name=name) for name in candidates])

    def bytecode(self, class_name, code=False):
        tools = java_tools(self.args, self.info.get("java_version", 25))
        cache = self.bucket / "javap" / str(tools["major"]) / (class_name + (".code.txt" if code else ".txt"))
        if cache.is_file():
            return cache.read_text(encoding="utf-8"), True
        command = [tools["javap"], "-p", "-s"] + (["-c"] if code else [])
        stdout, _ = run_java(command + ["-classpath", str(self.jar), class_name], self.args.timeout)
        write_text(cache, stdout)
        return stdout, False

    def members(self, class_name):
        text, _ = self.bytecode(class_name)
        result = []
        previous = ""
        for line in text.splitlines():
            if line.strip().startswith("descriptor:"):
                descriptor = line.strip().split(":", 1)[1].strip()
                is_method = descriptor.startswith("(")
                name = previous.split("(", 1)[0].split()[-1] if is_method else previous.rstrip(";").split()[-1]
                if name == class_name:
                    name = "<init>"
                elif previous == "static {};":
                    name = "<clinit>"
                result.append({"name": name, "descriptor": descriptor, "kind": "method" if is_method else "field", "declaration": previous})
            elif line.strip():
                previous = line.strip()
        return result

    def inherited_members(self, class_name, declared, max_depth=8, max_classes=32):
        """Find declaration hints only; never substitute or select a parent method."""
        blocked = {(member["name"], member["descriptor"]) for member in declared}
        pending, seen, found, warnings = [(class_name, 0, blocked)], set(), [], []
        while pending and len(seen) < max_classes:
            current, depth, blocked = pending.pop(0)
            if current in seen:
                continue
            seen.add(current)
            try:
                text, _ = self.bytecode(current)
                current_members = self.members(current) if depth else declared
                if depth:
                    for member in current_members:
                        if (member["name"] == self.args.member
                                and member["name"] not in ("<init>", "<clinit>")
                                and not member["declaration"].startswith("private ")
                                and (not self.args.descriptor or member["descriptor"] == self.args.descriptor)
                                and (member["name"], member["descriptor"]) not in blocked):
                            found.append({**member, "declaring_class": current, "inheritance_depth": depth})
                header = next((line.strip() for line in text.splitlines() if re.search(r"\b(class|interface|enum)\s", line)), "")
                # javap headers may contain nested generic arguments; remove them
                # before reading extends / implements lists.
                while re.search(r"<[^<>]*>", header):
                    header = re.sub(r"<[^<>]*>", "", header)
                parents = re.findall(r"net\.minecraft\.[A-Za-z0-9_.$]+", header)
                parents = [parent for parent in parents
                           if parent != current and parent in self.classes and parent not in seen]
                if depth >= max_depth:
                    if parents:
                        warnings.append("继承线索查询已达到最大深度 " + str(max_depth) + "；请沿 outline 手动核对。")
                    continue
                # Block overrides only along this ancestry path. Independent
                # interfaces can still provide separate declaration clues.
                parent_blocked = blocked | {(member["name"], member["descriptor"]) for member in current_members}
                pending.extend((parent, depth + 1, parent_blocked) for parent in parents)
            except KnowledgeError as error:
                warnings.append("继承声明线索不可用：" + current + " (" + error.code + ")")
        if pending:
            warnings.append("继承线索查询已达到最大类数 " + str(max_classes) + "；请沿 outline 手动核对。")
        return found, warnings

    def select_member(self, class_name):
        declared = self.members(class_name)
        named = [m for m in declared if m["name"] == self.args.member]
        candidates = [m for m in named if not self.args.descriptor or m["descriptor"] == self.args.descriptor]
        if len(candidates) != 1:
            inherited, warnings = ([], [])
            if not candidates:
                inherited, warnings = self.inherited_members(class_name, declared)
            commands = [next_command(self.args, class_name=class_name, descriptor=m["descriptor"])
                        for m in (candidates or named)]
            commands.extend(next_command(self.args, class_name=m["declaring_class"], descriptor=m["descriptor"])
                            for m in inherited)
            if not commands:
                commands.append(next_command(self.args, command="outline", class_name=class_name))
            raise KnowledgeError("MEMBER_AMBIGUOUS" if candidates else "MEMBER_NOT_FOUND",
                                 "成员必须在此类声明；重载请指定 --descriptor，继承成员请查声明它的父类。",
                                 candidates=candidates, declared_candidates=named,
                                 inherited_candidates=inherited, warnings=warnings, next_commands=commands,
                                 inheritance_search={"max_depth": 8, "max_classes": 32, "automatic_selection": False})
        if candidates[0]["kind"] != "method":
            raise KnowledgeError("METHOD_REQUIRED", "字段请用 outline 或读取完整类；--member 反编译需要方法。",
                                 next_commands=[next_command(self.args, command="outline", class_name=class_name)])
        return candidates[0]

    def source(self, class_name):
        member = self.select_member(class_name) if self.args.member else None
        compiler = vineflower(self.args)
        tools = java_tools(self.args, self.info.get("java_version", 25))
        identity = {"schema": SCHEMA, "compiler_sha256": compiler["sha256"], "flags": DECOMPILE_FLAGS,
                    "java_major": tools["major"], "class": class_name, "member": member}
        key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        folder = self.bucket / "sources" / key
        source = folder / "source.java"
        metadata = read_json(folder / "source.json")
        if metadata and source.is_file() and digest(source) == metadata.get("source_sha256"):
            return source.read_text(encoding="utf-8"), {**metadata, "cache_hit": True}
        folder.mkdir(parents=True, exist_ok=True)
        outer = class_name.split("$", 1)[0].replace(".", "/")
        with tempfile.TemporaryDirectory(prefix="decompile-", dir=folder) as temporary:
            temporary = Path(temporary)
            inputs, outputs = temporary / "input", temporary / "output"
            outputs.mkdir()
            with zipfile.ZipFile(self.jar) as archive:
                for name in self.entries:
                    if name == outer + ".class" or (name.startswith(outer + "$") and name.endswith(".class")):
                        if ".." in name.split("/") or "\\" in name:
                            raise KnowledgeError("CLASS_PATH_INVALID", "JAR 中的类路径包含不安全分段。")
                        target = inputs / name
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(archive.read(name))
            flags = list(DECOMPILE_FLAGS)
            if member:
                flags.append("--method-to-decompile=" + class_name.replace(".", "/") + "." + member["name"] + member["descriptor"])
            stdout, stderr = run_java([tools["java"], "-Xmx512m", "-jar", compiler["path"], *flags,
                                       "-e=" + str(self.jar), str(inputs), str(outputs)], self.args.timeout)
            generated = outputs / (outer + ".java")
            if not generated.is_file():
                raise KnowledgeError("SOURCE_MISSING", "Vineflower 未生成目标源码。", diagnostic=(stdout + stderr)[-2000:])
            text = generated.read_text(encoding="utf-8")
            warnings = []
            diagnostic = (stdout + stderr).strip()
            if diagnostic:
                warnings.append(diagnostic[-1500:])
            if re.search(r"Couldn't be decompiled|could not be decompiled|Exception decompiling|\$VF:.*(?:error|failed)", text, re.I):
                warnings.append("反编译包含异常标记；用 source --bytecode 核对，不应将不完整 Java 当作结论。")
            generated.replace(source)
        metadata = {"class": class_name, "member": member and member["name"],
                    "descriptor": member and member["descriptor"], "compiler": compiler["version"],
                    "compiler_sha256": compiler["sha256"], "path": str(source),
                    "source_sha256": digest(source), "warnings": warnings}
        write_json(folder / "source.json", metadata)
        return text, {**metadata, "cache_hit": False}


def pointer_value(value, pointer):
    if pointer == "":
        return value
    if not pointer.startswith("/"):
        raise KnowledgeError("POINTER_INVALID", "JSON Pointer 应为空或以 / 开头。")
    try:
        for token in pointer[1:].split("/"):
            token = token.replace("~1", "/").replace("~0", "~")
            if isinstance(value, list):
                if not re.fullmatch(r"0|[1-9][0-9]*", token):
                    raise KeyError(token)
                value = value[int(token)]
            else:
                value = value[token]
    except (KeyError, IndexError, TypeError, ValueError):
        raise KnowledgeError("POINTER_NOT_FOUND", "JSON Pointer 未命中。", pointer=pointer) from None
    return value


def execute(args):
    if args.command == "wiki":
        from mc_knowledge_wiki import wiki_query
        result = wiki_query(args, args.cache_dir / "wiki")
        result["requested_minecraft_version"] = args.version or project_version(args.project)
        return result
    vanilla = Vanilla(args)
    result = {"source": vanilla.provenance}
    if args.command == "doctor":
        result.update(class_count=len(vanilla.classes), cache_dir=str(args.cache_dir))
        for name, operation in [("jdk", lambda: java_tools(args, vanilla.info.get("java_version", 25))),
                                ("vineflower", lambda: vineflower(args))]:
            try:
                result[name] = operation()
            except KnowledgeError as error:
                result[name] = {"available": False, "message": str(error)}
        return result
    if args.command == "find-class":
        query = args.query.replace("/", ".").lower()
        matches = [c for c in vanilla.classes if query in c.lower()]
        matches.sort(key=lambda c: (c.rsplit(".", 1)[-1].lower() != query, c))
        listed = matches[:args.limit]
        if args.format == "json" and args.max_output_chars is None:
            result.update(matches=listed, total_matches=len(matches), truncated=len(matches) > args.limit)
            return result
        page = page_text("\n".join(json.dumps(item, ensure_ascii=False) for item in listed), args)
        body_truncated = page.pop("truncated")
        result.update(matches=listed, total_matches=len(matches), matches_limited=len(matches) > args.limit,
                      truncated=len(matches) > args.limit, body_truncated=body_truncated, **page)
        return result
    if args.command in ("outline", "source"):
        class_name = vanilla.resolve(args.class_name)
        if args.command == "outline":
            text, hit = vanilla.bytecode(class_name)
            if args.member:
                candidates = [m for m in vanilla.members(class_name) if args.member.lower() in m["name"].lower()]
                text = "\n".join(m["declaration"] + "\n  descriptor: " + m["descriptor"] for m in candidates)
            result.update(class_name=class_name, cache_hit=hit, **page_text(text, args))
        elif args.bytecode or args.member in ("<init>", "<clinit>"):
            text, hit = vanilla.bytecode(class_name, code=True)
            if args.member:
                member = vanilla.select_member(class_name)
                blocks = re.split(r"(?m)(?=^  \S)", text)
                selected = [b for b in blocks if b.splitlines()[0].strip() == member["declaration"]]
                if len(selected) != 1:
                    raise KnowledgeError("BYTECODE_MEMBER_MISSING", "无法唯一定位字节码方法块。")
                text = selected[0]
                result["member"] = {"name": member["name"], "descriptor": member["descriptor"]}
            if args.member in ("<init>", "<clinit>") and not args.bytecode:
                result["warnings"] = ["初始化方法以字节码返回，避免单方法反编译遗漏被提升到字段声明处的初始化；不指定 --member 可读取完整 Java 类。"]
            result.update(class_name=class_name, representation="javap bytecode", cache_hit=hit, **page_text(text, args))
        else:
            text, metadata = vanilla.source(class_name)
            # Member views may still have a long import list. Start at the class
            # declaration unless the caller explicitly requested a file position.
            paging = argparse.Namespace(**vars(args))
            if args.member and not args.explicit_start:
                for number, line in enumerate(text.splitlines(), 1):
                    if re.match(r"^(?:public |protected |private |abstract |final |static )*(?:class|interface|enum|record)\s", line):
                        paging.start_line = number
                        break
            # Complete digests stay beside source.java in source.json. Returning
            # those two additional hashes on every small lookup wastes context.
            visible = {key: value for key, value in metadata.items()
                       if key not in ("source_sha256", "compiler_sha256") and value is not None}
            visible["view"] = "method" if args.member else "class"
            result.update(decompiled=visible, **page_text(text, paging))
        return result
    if args.command == "data":
        name = args.path
        if ".." in name.split("/") or name.startswith("/") or "\\" in name:
            raise KnowledgeError("RESOURCE_INVALID", "资源路径必须是 JAR 内相对路径。")
        if not (name.startswith(("data/", "assets/")) or name == "version.json"):
            name = "data/minecraft/" + name
        if not name.endswith(".json"):
            name += ".json"
        with zipfile.ZipFile(vanilla.jar) as archive:
            try:
                value = json.loads(archive.read(name))
            except KeyError:
                raise KnowledgeError("RESOURCE_MISSING", "本版 JAR 中没有此资源。", path=name) from None
        if args.pointer is not None:
            value = pointer_value(value, args.pointer)
        result.update(resource=name, pointer=args.pointer,
                      **page_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", args))
        return result
    if args.command == "search":
        hits, classes, seen = [], set(), set()
        for metadata_path in sorted((vanilla.bucket / "sources").glob("*/source.json")):
            metadata = read_json(metadata_path)
            if not metadata or not metadata["class"].startswith(args.scope):
                continue
            path = metadata_path.with_name("source.java")
            if not path.is_file() or digest(path) != metadata.get("source_sha256"):
                continue
            classes.add(metadata["class"])
            for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                key = (metadata["class"], line.strip())
                if args.query.casefold() in line.casefold() and key not in seen:
                    seen.add(key)
                    hits.append({"class": metadata["class"], "path": str(path), "line": line_number, "text": line.strip()})
        # Matching snippets are also paged: an arbitrarily long source line cannot flood stdout.
        text = "\n".join(json.dumps(hit, ensure_ascii=False) for hit in hits[:args.limit])
        result.update(coverage="cached decompilations only; includes partial method views", cached_classes=len(classes),
                      total_matches=len(hits), matches_limited=len(hits) > args.limit, **page_text(text, args))
        return result
    raise KnowledgeError("COMMAND_INVALID", "未知子命令。")


def positive(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("必须大于 0")
    return number


def nonnegative(value):
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError("不能小于 0")
    return number


def parser():
    common = argparse.ArgumentParser(add_help=False, argument_default=argparse.SUPPRESS, allow_abbrev=False)
    common.add_argument("--project", type=Path, help="项目根目录，默认本脚本所属仓库")
    common.add_argument("--version", help="显式比较其他版本；默认 gradle.properties")
    common.add_argument("--gradle-home", type=Path)
    common.add_argument("--cache-dir", type=Path)
    common.add_argument("--java-home")
    common.add_argument("--vineflower", help="已有 Vineflower JAR")
    common.add_argument("--jar", help="显式本地 JAR；version.json 必须匹配")
    common.add_argument("--side", choices=["server", "client"])
    common.add_argument("--offline", action="store_true", help="Wiki 只读缓存；本地命令始终不联网")
    common.add_argument("--max-lines", type=positive)
    common.add_argument("--max-chars", type=positive, help="正文字符预算（JSON 元数据另计）")
    common.add_argument("--start-line", type=positive)
    common.add_argument("--start-char", type=nonnegative)
    common.add_argument("--limit", type=positive)
    common.add_argument("--timeout", type=positive)
    common.add_argument("--format", choices=["json", "compact"], help="默认 json；也可显式设置 MC_KNOWLEDGE_FORMAT")
    common.add_argument("--max-output-chars", type=positive, help="整个 stdout 字符预算；compact 默认 6000，json 默认不限")
    common.add_argument("--provenance", choices=["reference", "full"], help="完整出处始终存内容寻址证据；full 同时展开")
    root = argparse.ArgumentParser(description=__doc__, parents=[common], allow_abbrev=False)
    sub = root.add_subparsers(dest="command", required=True)
    sub.add_parser("doctor", parents=[common], allow_abbrev=False, help="验证版本、JAR 和本机工具")
    find = sub.add_parser("find-class", parents=[common], allow_abbrev=False, help="搜索 JAR 类名，无需反编译")
    find.add_argument("query")
    for name in ["outline", "source"]:
        command = sub.add_parser(name, parents=[common], allow_abbrev=False, help="成员签名" if name == "outline" else "有限源码／方法／字节码")
        command.add_argument("class_name")
        command.add_argument("--member")
        if name == "source":
            command.add_argument("--descriptor", help="JVM 方法签名，用于区分重载")
            command.add_argument("--bytecode", action="store_true")
    data = sub.add_parser("data", parents=[common], allow_abbrev=False, help="读取原版 JAR 的 JSON")
    data.add_argument("path")
    data.add_argument("--pointer", help="JSON Pointer，例如 /scaling")
    search = sub.add_parser("search", parents=[common], allow_abbrev=False, help="仅搜索当前 JAR 已缓存的源码")
    search.add_argument("query")
    search.add_argument("--scope", default="", help="完整类名前缀")
    wiki = sub.add_parser("wiki", parents=[common], allow_abbrev=False, help="Wiki 搜索、首段、目录、固定修订的章节")
    wiki.add_argument("title")
    modes = wiki.add_mutually_exclusive_group()
    modes.add_argument("--search", action="store_true")
    modes.add_argument("--toc", action="store_true")
    modes.add_argument("--section")
    wiki.add_argument("--oldid", type=positive)
    wiki.add_argument("--lang", default="en")
    wiki.add_argument("--refresh", action="store_true")
    return root


def parse_args(argv=None):
    args = parser().parse_args(argv)
    args.explicit_start = hasattr(args, "start_line") or hasattr(args, "start_char")
    defaults = {"project": ROOT, "version": None, "gradle_home": Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")),
                "java_home": None, "vineflower": None, "jar": None, "side": "server", "offline": False,
                "max_lines": 80, "max_chars": 5000, "start_line": 1, "start_char": 0, "limit": 5, "timeout": 30,
                "format": os.environ.get("MC_KNOWLEDGE_FORMAT", "json"), "max_output_chars": None, "provenance": "reference"}
    for key, value in defaults.items():
        if not hasattr(args, key):
            setattr(args, key, value)
    if args.format not in ("json", "compact"):
        parser().error("MC_KNOWLEDGE_FORMAT 必须为 json 或 compact")
    args.project = args.project.expanduser().resolve()
    args.gradle_home = args.gradle_home.expanduser().resolve()
    args.cache_dir = getattr(args, "cache_dir", args.project / ".local/mc-knowledge").expanduser().resolve()
    if getattr(args, "descriptor", None) and not getattr(args, "member", None):
        parser().error("--descriptor 需要 --member")
    return args


def main(argv=None):
    started = time.perf_counter()
    args = parse_args(argv)
    code = 0
    try:
        result = {"ok": True, **execute(args)}
    except (RuntimeError, OSError, ValueError, zipfile.BadZipFile, KeyError) as error:
        result = {"ok": False, "error": {"code": getattr(error, "code", "LOOKUP_FAILED"),
                                         "message": str(error), **getattr(error, "details", {})}}
        code = 1
    from mc_knowledge_output import OutputBudgetTooSmall, budget_error, render_failure, render_output
    try:
        rendered = render_output(result, args, round((time.perf_counter() - started) * 1000, 2))
    except OutputBudgetTooSmall as error:
        rendered, diagnostic = budget_error(error, args)
        if not rendered:
            print(diagnostic, file=sys.stderr, end="")
        code = 1
    except (RuntimeError, OSError, ValueError) as error:
        rendered, diagnostic = render_failure(result, args, error)
        if not rendered:
            print(diagnostic, file=sys.stderr, end="")
        code = 1
    print(rendered, end="")
    return code


if __name__ == "__main__":
    sys.exit(main())

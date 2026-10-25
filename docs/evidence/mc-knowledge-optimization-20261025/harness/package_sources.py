#!/usr/bin/env python3
"""After trials finish, package workspace text evidence without modifying formal files."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tarfile
from urllib.parse import unquote
from common import BASE, read, dump, attempt_paths

TEXT_SUFFIXES = {'.java','.json','.jsonl','.txt','.html','.htm','.md','.markdown','.xml',
                 '.properties','.csv','.tsv','.log','.yaml','.yml','.toml','.sql','.mcmeta','.lang','.py'}
FORBIDDEN_PARTS = {'.git','.aws','.ssh','.gnupg','.codex','node_modules','__pycache__','.venv','venv'}
ORIGINAL_FILES = ['answer.md','prompt.txt','invocation.json','status.json','events.jsonl',
                  'observable.jsonl','transcript.md','tool-outputs.json','native-token-records.json']
TERMINAL_STATES = {'completed','failed','timeout','failed_missing_answer','failed_harness'}
SECRET_PATTERNS = [
    ('private_key',re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----')),
    ('aws_access_key',re.compile(r'\b(?:AKIA|ASIA)[0-9A-Z]{16}\b')),
    ('openai_style_key',re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b')),
    ('github_token',re.compile(r'\bgh[pousr]_[A-Za-z0-9]{20,}\b')),
    ('bearer_token',re.compile(r'(?i)\bBearer[ \t]+[A-Za-z0-9._~+/-]{16,}')),
    ('url_embedded_credentials',re.compile(r'https?://[^/\s:@]+:[^/@\s]+@')),
    ('credential_assignment',re.compile(r'''(?i)\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|authorization)\b(?:\\?["'])?[ \t]*[:=][ \t]*(?:\\?["'])([^"'\\\r\n]{8,})(?:\\?["'])''')),
]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def scan_text(text, location):
    """Reports locations/types only, never matched values or surrounding text."""
    hits = []
    for kind,pattern in SECRET_PATTERNS:
        for match in pattern.finditer(text):
            if kind == 'credential_assignment':
                candidate = match.group(1)
                if any(marker in candidate.lower() for marker in ('example','placeholder','changeme','dummy','your_','${','<')):
                    continue
            line = text.count('\n',0,match.start())+1
            column = match.start()-text.rfind('\n',0,match.start())
            hits.append({'location':location,'type':kind,'line':line,'column':column})
    return hits


def forbidden(relative):
    return (any(part in FORBIDDEN_PARTS for part in relative.parts)
            or relative.name == '.env' or relative.name.startswith('.env.')
            or relative.suffix.lower() in {'.pem','.key','.p12','.pfx','.keystore'}
            or relative.name.lower() in {'credentials','credentials.json','auth.json','id_rsa','id_ed25519'})


def collect_sources(workspace, max_bytes):
    workspace = workspace.resolve()
    selected,skipped,hits = [],[],[]
    for path in sorted(workspace.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(workspace)
        reason = None
        if forbidden(relative):
            reason = 'credential_or_excluded_path_not_read'
        elif not path.resolve().is_relative_to(workspace):
            reason = 'symlink_target_outside_workspace_not_read'
        elif path.suffix.lower() not in TEXT_SUFFIXES:
            reason = 'nonallowlisted_or_binary_extension_not_read'
        elif path.stat().st_size > max_bytes:
            reason = 'file_size_limit_not_read'
        if reason:
            skipped.append({'path':str(relative),'reason':reason})
            continue
        data = path.read_bytes()
        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError:
            skipped.append({'path':str(relative),'reason':'not_utf8_text'})
            continue
        if '\0' in text:
            skipped.append({'path':str(relative),'reason':'binary_nul_detected'})
            continue
        location = 'workspace/'+str(relative)
        hits.extend(scan_text(text,location))
        category = 'cli_cache' if relative.parts[:2] == ('.local','mc-knowledge') else 'other_workspace_text'
        selected.append({'relative_path':str(relative),'category':category,'bytes':len(data),'sha256':digest(data)})
    return selected,skipped,hits


LINK = re.compile(r'''(?P<prefix>!?\[[^\]\n]*\]\()(?P<target><[^>\n]+>|[^)\s]+)(?P<suffix>(?:\s+["'][^"'\n]*["'])?\))''')


def workspace_aliases(workspace):
    value = str(workspace.resolve())
    values = {value}
    if value.startswith('/private/tmp/'):
        values.add(value.replace('/private/tmp/','/tmp/',1))
    elif value.startswith('/tmp/'):
        values.add(value.replace('/tmp/','/private/tmp/',1))
    return sorted(values,key=len,reverse=True)


def portable_answer(text,workspace,manifest):
    available = {item['relative_path']:item for item in manifest}
    aliases = workspace_aliases(workspace)
    counts = {'workspace_links':0,'rewritten_links':0,'unresolved_workspace_links':0,'nonworkspace_links':0}
    unresolved = []
    fence = None
    output = []
    for line_number,line in enumerate(text.splitlines(keepends=True),1):
        match = re.match(r'^ {0,3}(`{3,}|~{3,})',line)
        if match:
            marker = match.group(1)[0]
            if fence is None:
                fence = marker
            elif marker == fence:
                fence = None
            output.append(line)
            continue
        if fence:
            output.append(line)
            continue
        def replace(match):
            raw = match.group('target')
            target = unquote(raw[1:-1] if raw.startswith('<') else raw)
            file_scheme = target.startswith('file://')
            if file_scheme:
                target = target[7:]
            suffix_match = re.search(r'(?::\d+|#L\d+)$',target)
            line_suffix = suffix_match.group() if suffix_match else ''
            local_path = target[:suffix_match.start()] if suffix_match else target
            alias = next((base for base in aliases if local_path.startswith(base+'/')),None)
            if alias is None:
                relative = Path(local_path)
                # A relative target is a workspace source only when its exact
                # normalized path is already in the archive manifest. Never
                # reinterpret URLs, anchors, parent paths or unknown files.
                if (file_scheme or not local_path or relative.is_absolute()
                        or re.match(r'^[A-Za-z][A-Za-z0-9+.-]*:',local_path)
                        or any(char in local_path for char in ('?','#'))
                        or '..' in relative.parts or str(relative) not in available):
                    counts['nonworkspace_links'] += 1
                    return match.group()
            else:
                relative = Path(local_path[len(alias)+1:])
            counts['workspace_links'] += 1
            candidate = workspace/relative
            reason = None
            if '..' in relative.parts:
                reason = 'parent_path_component_not_supported'
            elif str(relative) not in available:
                reason = 'not_in_text_archive'
            elif not candidate.is_file() or not candidate.resolve().is_relative_to(workspace.resolve()):
                reason = 'missing_or_outside_workspace'
            if reason:
                counts['unresolved_workspace_links'] += 1
                unresolved.append({'line':line_number,'type':reason,'workspace_relative_path':str(relative)})
                return match.group()
            replacement = 'sources/'+relative.as_posix()+line_suffix
            if any(char.isspace() for char in replacement):
                replacement = '<'+replacement+'>'
            counts['rewritten_links'] += 1
            return match.group('prefix')+replacement+match.group('suffix')
        output.append(LINK.sub(replace,line))
    return ''.join(output),counts,unresolved


def write_archive(workspace,selected,output):
    source_dir = output/'sources'
    archive_path = output/'sources.tar.gz'
    with archive_path.open('xb') as raw:
        with gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as zipped:
            with tarfile.open(fileobj=zipped,mode='w') as archive:
                for item in selected:
                    path = workspace/item['relative_path']
                    data = path.read_bytes()
                    if digest(data) != item['sha256']:
                        raise RuntimeError('Source changed after privacy preflight: '+item['relative_path'])
                    destination = source_dir/item['relative_path']
                    destination.parent.mkdir(parents=True,exist_ok=True)
                    destination.write_bytes(data)
                    info = tarfile.TarInfo(item['relative_path'])
                    info.size,info.mtime,info.mode = len(data),0,0o644
                    info.uid = info.gid = 0
                    info.uname = info.gname = ''
                    archive.addfile(info,io.BytesIO(data))
    return {'path':'sources.tar.gz','sha256':digest(archive_path.read_bytes()),'files':len(selected),
            'bytes_uncompressed':sum(item['bytes'] for item in selected)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',action='store_true',help='Requires every assigned trial to have terminal status; reads bodies only then.')
    parser.add_argument('--destination',type=Path,default=BASE/'export-bundle')
    parser.add_argument('--max-file-bytes',type=int,default=16_000_000)
    options = parser.parse_args()
    if not options.run:
        print(json.dumps({'plan':'Package text sources and portable answers only after all formal trials are terminal.',
             'destination':str(options.destination),'model_calls':0,'current_answers_or_cache_bodies_read':False,
             'privacy_policy':'Scan before exporting. Findings contain only location/type; any finding aborts packaging without exporting source bodies.'},ensure_ascii=False,indent=2))
        return
    destination = options.destination.resolve()
    prohibited = [BASE/name for name in ('runs','reference','baseline','final','inputs','harness')]
    if not destination.is_relative_to(BASE.resolve()) or any(destination.is_relative_to(path.resolve()) for path in prohibited) or destination == BASE.resolve():
        raise SystemExit('Destination must be a separate export directory inside this lab.')
    if not destination.is_dir():
        raise SystemExit('Run export_bundle.py first; source helper requires an existing export directory.')
    marker = destination/'source-packaging-status.json'
    if marker.exists():
        raise SystemExit('Source packaging already attempted here; use a fresh export directory. No overwrite.')
    rows = read(BASE/'manifest.json')['runs']
    if (BASE/'run.lock').exists():
        raise SystemExit('Experiment runner lock still exists; no answer or cache body read.')
    attempts = [(row,attempt) for row in rows for attempt in attempt_paths(row)]
    for row in rows:
        if not attempt_paths(row):
            raise SystemExit('An assigned trial has no attempt; no body read.')
    for row,attempt in attempts:
        status_path = attempt/'status.json'
        if not status_path.exists() or read(status_path).get('state') not in TERMINAL_STATES:
            raise SystemExit('Trials are still pending/running; no answer or cache body read.')
        workspace_path = attempt/'workspace'
        if workspace_path.is_symlink() or not workspace_path.resolve().is_relative_to(attempt.resolve()):
            raise SystemExit('Workspace path replaced by a symlink/external location; no body read.')
    # Only now is reading current workspace contents permitted.
    inventories,hits = [],[]
    for row,attempt in attempts:
        workspace = (attempt/'workspace').resolve()
        selected,skipped,found = collect_sources(workspace,options.max_file_bytes)
        initial = read(attempt/'initial-files.json')
        for item in selected:
            item['initial_input'] = item['relative_path'] in initial
        originals = []
        for name in ORIGINAL_FILES:
            path = attempt/name
            if path.is_file():
                if path.is_symlink() or not path.resolve().is_relative_to(attempt.resolve()):
                    found.append({'location':name,'type':'original_file_symlink_not_read','line':None,'column':None})
                    continue
                data = path.read_bytes()
                originals.append({'path':name,'sha256':digest(data),'bytes':len(data)})
                try:
                    text = data.decode('utf-8')
                except UnicodeDecodeError:
                    found.append({'location':name,'type':'non_utf8_original_not_exportable','line':None,'column':None})
                    continue
                found.extend(scan_text(text,name))
        for finding in found:
            hits.append({'run_id':row['id'],'attempt':attempt.name,**finding})
        inventories.append({'row':row,'attempt':attempt,'workspace':workspace,
                            'selected':selected,'skipped':skipped,'originals':originals})
    dump(destination/'source-privacy-scan.json',{'findings':hits,'finding_count':len(hits),
         'matched_values_exported':False,'scope':'Only selected workspace text and original observable experiment files. No home scan or credential-file read.',
         'limitations':'Pattern-based screening is not proof that all private content is absent.'})
    if hits:
        dump(marker,{'state':'blocked_privacy_findings','finding_count':len(hits),
                     'note':'Review locations/types in source-privacy-scan.json. No source archive or portable answer generated.'})
        print(json.dumps({'state':'blocked_privacy_findings','finding_count':len(hits),'matched_values_printed':False}))
        raise SystemExit(2)
    dump(marker,{'state':'packaging','attempts':len(inventories)})
    results = []
    try:
        for entry in inventories:
            row,attempt,workspace = entry['row'],entry['attempt'],entry['workspace']
            output = destination/'runs'/row['id']/attempt.name
            output.mkdir(parents=True,exist_ok=True)
            names = ('sources','sources.tar.gz','portable-answer.md','answer-original.md','source-path-map.json',
                     'source-manifest.json','original-file-hashes.json','unresolved-links.json')
            if any((output/name).exists() for name in names):
                raise RuntimeError('Refuse to overwrite existing portable-source artifacts.')
            archive = write_archive(workspace,entry['selected'],output)
            mappings = [{'source_absolute':str(workspace/item['relative_path']),
                         'source_aliases':[alias+'/'+item['relative_path'] for alias in workspace_aliases(workspace)],
                         'archive_relative_path':item['relative_path'],
                         'portable_relative_path':'sources/'+item['relative_path'],
                         'category':item['category'],'sha256':item['sha256']} for item in entry['selected']]
            dump(output/'source-path-map.json',mappings)
            dump(output/'source-manifest.json',{'archive':archive,'files':entry['selected'],'skipped':entry['skipped']})
            dump(output/'original-file-hashes.json',entry['originals'])
            answer = attempt/'answer.md'
            counts,unresolved = {},[]
            if answer.is_file():
                original = answer.read_bytes()
                rewritten,counts,unresolved = portable_answer(original.decode('utf-8'),workspace,entry['selected'])
                (output/'answer-original.md').write_bytes(original)
                (output/'portable-answer.md').write_text(rewritten)
            dump(output/'unresolved-links.json',{'counts':counts,'unresolved':unresolved})
            for item in entry['originals']:
                if digest((attempt/item['path']).read_bytes()) != item['sha256']:
                    raise RuntimeError('Original formal file changed during export.')
            results.append({'id':row['id'],'attempt':attempt.name,'archive':archive,'link_counts':counts,
                            'formal_answer_and_logs_unchanged':True})
            dump(destination/'source-packaging-summary.json',results)
        dump(marker,{'state':'completed','attempts':len(results),'formal_files_modified':False,
                     'privacy_findings':0,'model_calls':0})
    except Exception as error:
        dump(marker,{'state':'failed','error_type':type(error).__name__,'completed_attempts':len(results),
                     'note':'Partial export retained. Formal files were never written.'})
        raise
    print(json.dumps({'state':'completed','attempts':len(results),'formal_files_modified':False}))


if __name__ == '__main__':
    main()

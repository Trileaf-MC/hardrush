#!/usr/bin/env python3
"""Run nine independent, read-only research trials with auditable Codex JSON events."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import time

BASE = Path(__file__).resolve().parent
REPO = Path('/Users/leifwebber/projects/minecraft/hardrush')
MODEL = 'gpt-6.1-sol'
QUESTION = '''1. minecraft java 26.3 版本中，骷髅在细雪中转化为流浪者的具体行为，包括完整阶段过程和所需时间等信息。
2. 自然生成、未被驯服的狼，会主动攻击流浪者吗？如果是已经被玩家驯服的狼，又会怎样？'''
SUFFIX = {
    'A': '\n你可以使用 .agents/skills/minecraft-knowledge/SKILL.md 来配合调查',
    'B': '\n你可以任选 minecraft.wiki 直接查证或者利用本机的 ~/.gradle/caches/fabric-loom/ 中源码+ Vineflower 反编译器反编译源码来完成调查',
    'C': '',
}
COMMON = '''请用中文回答，并给出支持关键结论的可复核出处，明确版本与不确定性。
本次只调查原版机制。不要读取既有调查报告、聊天历史、个人记忆或其他实验目录；不要启动子代理或游戏客户端/服务器。可在当前工作目录存放临时资料，其他本地文件只读。'''

def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def prepare():
    rng = random.Random(20261025)
    rows = []
    for wave in range(1, 4):
        order = list('ABC')
        rng.shuffle(order)
        for group in order:
            index = len(rows) + 1
            run = BASE / f'run-{index:02d}'
            workspace = run / 'workspace'
            workspace.mkdir(parents=True, exist_ok=False)
            shutil.copy2(REPO / 'gradle.properties', workspace / 'gradle.properties')
            pointer = workspace / '.local/toolchains/java-home.txt'
            pointer.parent.mkdir(parents=True)
            shutil.copy2(REPO / '.local/toolchains/java-home.txt', pointer)
            if group == 'A':
                for name in ['.agents/skills/minecraft-knowledge/SKILL.md', 'scripts/mc-knowledge.py', 'scripts/mc_knowledge_wiki.py']:
                    target = workspace / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(REPO / name, target)
            prompt = QUESTION + SUFFIX[group] + '\n\n' + COMMON + '\n'
            (run / 'prompt.txt').write_text(prompt)
            row = dict(id=f'run-{index:02d}', group=group, repeat=wave, wave=wave,
                       workspace=str(workspace), prompt_sha256=hashlib.sha256(prompt.encode()).hexdigest())
            rows.append(row)
    manifest = dict(model=MODEL, effort='high', seed=20261025, timeout_seconds=1200,
                    concurrency=3, waves=3, runs=rows,
                    cache_policy='Identical existing raw Gradle cache; empty per-run generated source/wiki cache. OS/page/network caches not reset.',
                    common_instructions=COMMON,
                    source_hashes={n: hashlib.sha256((REPO/n).read_bytes()).hexdigest() for n in ['.agents/skills/minecraft-knowledge/SKILL.md','scripts/mc-knowledge.py','scripts/mc_knowledge_wiki.py']})
    dump(BASE / 'manifest.json', manifest)
    return manifest

def command(row):
    run = BASE / row['id']
    args = ['codex', '--no-daemon', 'exec', '--ignore-user-config', '--skip-git-repo-check',
            '-C', row['workspace'], '-m', MODEL,
            '-c', 'model_reasoning_effort="high"',
            '-c', 'cli_auth_credentials_store="keyring"',
            '-c', 'project_doc_max_bytes=0',
            '-c', 'web_search="live"',
            '-c', 'sandbox_workspace_write.network_access=true',
            '-c', 'suppress_unstable_features_warning=true',
            '-c', 'agents.enabled=false',
            '-s', 'workspace-write',
            '--enable', 'skip_host_skill_discovery',
            '--disable', 'memories', '--disable', 'plugins', '--disable', 'apps',
            '--disable', 'multi_agent', '--disable', 'chronicle', '--disable', 'hooks',
            '--json', '-o', str(run / 'answer.md'), '-']
    return args

def execute(row):
    run = BASE / row['id']
    args = command(row)
    dump(run / 'invocation.json', args)
    start = time.time()
    dump(run / 'status.json', dict(state='running', start_unix=start, **row))
    with (run / 'prompt.txt').open('rb') as stdin, (run / 'events.jsonl').open('wb') as stdout, (run / 'stderr.log').open('wb') as stderr:
        child = subprocess.Popen(args, stdin=stdin, stdout=stdout, stderr=stderr)
        try:
            code = child.wait(timeout=1200)
            state = 'completed' if code == 0 else 'failed'
        except subprocess.TimeoutExpired:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
            code = child.returncode
            state = 'timeout'
    status = dict(state=state, exit_code=code, start_unix=start, end_unix=time.time(),
                  elapsed_seconds=round(time.time()-start, 3), **row)
    events = [json.loads(s) for s in (run / 'events.jsonl').read_text().splitlines() if s.strip()]
    for event in events:
        if event.get('type') == 'thread.started': status['thread_id'] = event['thread_id']
        if event.get('type') == 'turn.completed': status['usage'] = event['usage']
    dump(run / 'status.json', status)
    print(json.dumps({k:status.get(k) for k in ['id','group','repeat','state','elapsed_seconds','usage']}, ensure_ascii=False), flush=True)
    return status

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--run', action='store_true')
    opts = parser.parse_args()
    manifest = prepare() if opts.prepare else json.loads((BASE/'manifest.json').read_text())
    if opts.prepare: print(json.dumps(manifest,ensure_ascii=False,indent=2))
    if opts.run:
        all_results=[]
        for wave in range(1,4):
            rows=[r for r in manifest['runs'] if r['wave']==wave]
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                all_results.extend(pool.map(execute, rows))
            dump(BASE/'results.json',all_results)

if __name__ == '__main__': main()

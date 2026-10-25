"""Shared definitions. This module never opens answer keys or launches a model."""
import hashlib
import json
import os
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
REPO = Path('/Users/leifwebber/projects/minecraft/hardrush')
MODEL = 'gpt-6.1-sol'
EFFORT = 'high'
SEED = 20261025
TIMEOUT = 1200
ARMS = {'J': {'format': 'json', 'skill': 'baseline'},
        'O': {'format': 'compact', 'skill': 'baseline'},
        'S': {'format': 'compact', 'skill': 'final'}}
QUESTIONS = {
    'T1': '''1. minecraft java 26.3 版本中，骷髅在细雪中转化为流浪者的具体行为，包括完整阶段过程和所需时间等信息。
2. 自然生成、未被驯服的狼，会主动攻击流浪者吗？如果是已经被玩家驯服的狼，又会怎样？''',
    'T2': 'Minecraft Java26.3中，铁傀儡对普通无护甲玩家一次近战的基础攻击属性、实际随机伤害区间，以及简单/普通/困难难度下的最终区间分别是什么？区分属性值与最终伤害并解释随机/难度缩放顺序。',
    'T3': 'Java26.3的刷怪笼在玩家离开后是否继续刷怪？给出默认玩家激活距离、刷怪延迟、一次尝试数量、附近同类上限、计时/生成失败时的行为，区分普通刷怪笼与试炼刷怪笼。',
}
SKILL_NOTICE = '\n你可以使用 .agents/skills/minecraft-knowledge/SKILL.md 来配合调查'
COMMON = '''请用中文回答，并给出支持关键结论的可复核出处，明确版本与不确定性。
本次只调查原版机制。不要读取既有调查报告、聊天历史、个人记忆或其他实验目录；不要启动子代理或游戏客户端/服务器。可在当前工作目录存放临时资料，其他本地文件只读。
CLI 输出格式由 MC_KNOWLEDGE_FORMAT 预设，请沿用；不要通过参数或环境覆盖成其他格式。'''
SKILL = Path('.agents/skills/minecraft-knowledge/SKILL.md')
CONFIGS = [Path('gradle.properties'), Path('.local/toolchains/java-home.txt')]


def dump(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    tmp.replace(path)


def read(path):
    return json.loads(Path(path).read_text())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sha_text(text):
    return hashlib.sha256(text.encode()).hexdigest()


def cli_files(root):
    scripts = Path(root) / 'scripts'
    return sorted({scripts / 'mc-knowledge.py', *scripts.glob('mc_knowledge*.py')})


def source_inventory():
    sources = {str(p.relative_to(BASE)): sha(p) for p in cli_files(BASE / 'final')}
    sources[str(Path('final') / SKILL)] = sha(BASE / 'final' / SKILL)
    sources[str(Path('baseline') / SKILL)] = sha(BASE / 'baseline' / SKILL)
    for rel in CONFIGS:
        sources[str(Path('inputs') / rel)] = sha(BASE / 'inputs' / rel)
    return sources


def verify_seal():
    seal = read(BASE / 'frozen-source-manifest.json')
    current = source_inventory()
    if current != seal['sha256']:
        raise RuntimeError('Frozen inputs changed; refuse to prepare/run against changed sources.')
    return seal


def make_prompt(topic):
    return QUESTIONS[topic] + SKILL_NOTICE + '\n\n' + COMMON + '\n'


def attempt_paths(row):
    return sorted((BASE / 'runs' / row['id']).glob('attempt-[0-9][0-9]'))


def public_event(event):
    """Keep CLI's observable events; exclude internal reasoning at acquisition."""
    item = event.get('item', {})
    if item.get('type') in ('reasoning', 'analysis'):
        return None
    if item.get('role') in ('system', 'developer') or item.get('channel') == 'analysis':
        return None
    def clean(value):
        if isinstance(value, dict):
            return {k: clean(v) for k, v in value.items()
                    if k not in ('encrypted_content', 'reasoning', 'analysis', 'base_instructions',
                                 'developer_instructions', 'system_instructions')}
        if isinstance(value, list):
            return [clean(v) for v in value]
        return value
    return clean(event)


ENV_KEYS = ['PATH', 'HOME', 'USER', 'LOGNAME', 'SHELL', 'TMPDIR', 'LANG', 'LC_ALL',
            'LC_CTYPE', 'TZ', 'JAVA_HOME', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY',
            'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy']


def common_env():
    # No inherited CODEX_*, MC_KNOWLEDGE_*, API keys, PYTHONPATH or project settings.
    return {k: os.environ[k] for k in ENV_KEYS if k in os.environ}


def env_audit(env):
    return {k: ({'present': True, 'sha256': sha_text(v), 'value_exported': False}
                if 'proxy' in k.lower() else v) for k, v in sorted(env.items())}

# Minecraft 知识 CLI 优化实现说明

实现文件已冻结；本文仅记录接口、边界和验证依据，不改变代码或实验输入。

## 兼容与输出契约

- 未指定格式时仍默认 JSON；只有显式设置 `MC_KNOWLEDGE_FORMAT=compact` 才改变环境默认，命令行 `--format` 优先。`--format json` 明确选择 JSON。
- 未设总预算且 `--provenance reference` 的 JSON 直接沿用原序列化路径，不写出处证据文件、不移除新增字段、不施加 6000 字符限制。原有正文 `--max-chars` / `--max-lines` 仍有效。
- 无总预算 JSON 的 `find-class` 保留原 `source` / `matches` / `total_matches` / `truncated` 结构，正文计数为 0；空文本保留原范围身份。需要预算的候选列表才生成 JSONL 正文。
- `--format compact` 输出一个单行 JSON 元数据头，接一个 LF、原样正文和最终 LF。正文没有再次经过 JSON 字符串转义；头部 `format=compact`，`metrics.body_chars` 精确界定正文字符数，最终 LF 不属于正文。解析时应按第一个 LF 分离头部，再读取指定长度的正文。
- 紧凑头保留版本、edition、side、真实完整性状态、类／成员／descriptor 或资源／Wiki 修订身份、准确范围、告警、截断与续读位置、可复核出处入口。显式提供 JAR 时，side 标记为 requested / unverified，不冒称验证了实际服务端／客户端身份。

## 预算与分页

- `--max-chars` 限制查询正文页；`--max-output-chars N` 限制整个 stdout，包括头部、元数据、完整告警、JSON 转义、正文和最终换行。计量单位为 Unicode 字符，不是 UTF-8 字节。
- compact 默认总预算 6000 字符；JSON 仅显式指定 `--max-output-chars` 才施加总预算。所有预算计算在已获取的页面上完成，不为寻找合适长度重新反编译。
- 总预算只缩短正文前缀，更新准确的 `returned_range`、`next`、`truncated`、完整性状态和适用时的 `returned_chars`。不剪坏 JSON 结构，不通过删除告警来塞入预算。
- 安全头、全部告警和最少正文无法放入时返回非零状态、`OUTPUT_BUDGET_TOO_SMALL` 与 `minimum_required_chars`。若连错误本身也放不进 stdout，则 stdout 为空，stderr 给出完整结构化诊断；不伪装为成功。
- Unicode、单行、CRLF、其他 Python 行分隔符及 JSON 转义均按原字符续读；起点恰好落在行末时规范化为实际下一行，避免无效下一页或空页。空文本单独保留旧范围语义。
- Wiki 因总预算产生新的截断时也携带固定的 `oldid` 和 `section`／`toc`，避免续读回到 latest。候选列表的 JSONL 文本允许单条候选跨页；预算 JSON 的 `matches` 仅含当前完整可见条目。`total_matches` / `matches_limited` 区分候选数量限制与正文分页；到达当前列表末尾后不生成无法推进的 `next`。

## 出处与失败处理

- compact、显式总预算或 `--provenance full` 使用内容寻址证据文件：`<cache-dir>/evidence/<完整 SHA-256>.json`。文件包含完整可用查询上下文、原始响应及可获得的反编译出处。
- 紧凑显示使用 hash 前 16 位作为短定位 ID，并提供完整证据路径；完整 JAR 校验值留在证据中。短 ID 不替代完整 hash 校验。`--provenance full` 展开出处资料。
- 证据先完整写入临时文件，再通过不覆盖的原子链接发布；同一身份已有内容必须一致。没有“首次显示过即可省略”的隐式会话状态。
- 嵌套反编译告警完整保留；证据写入权限错误、磁盘／校验异常在主入口转换为结构化 `OUTPUT_FAILED`，保留告警、返回非零，并继续遵守 stdout 总预算或改用 stderr 完整诊断。

## 错误恢复与参数约束

- 类歧义、方法重载和继承成员错误提供 `next_commands`：结构化 `argv` 优先，同时提供 `shlex.join` 的安全 POSIX shell 展示。
- 恢复命令保留原 project、解析后的目标 version、显式 jar、cache、Gradle／JDK／反编译器定位和相关输出选项。重载必须显式选择 descriptor，不静默挑选。
- 继承错误返回声明类线索；遍历最多 8 层、32 个类。覆盖屏蔽沿祖先路径传播，近父类同签名不会再作为等价祖先候选返回，独立接口线索仍可保留。只提供线索，不自动用父类方法替代请求方法。
- 所有根 parser、公共 parser 和子命令 parser 均关闭 argparse 参数缩写。构造器／静态初始化器默认字节码、版本／JAR 校验及缓存隔离不变。

## 变更与验证

变更文件：

- `scripts/mc-knowledge.py`
- 新增 `scripts/mc_knowledge_output.py`
- 新增 `scripts/test_mc_knowledge_output.py`

验证命令及结果：

- `python3 -m unittest discover -s scripts -p 'test_mc_knowledge*.py'`：50 项通过。覆盖既有 CLI／Wiki 约束，以及默认 JSON、格式覆盖、完整 stdout 预算、原样正文、无损续读、极小预算、完整告警、证据身份／异常、Wiki 固定修订、候选分页、恢复命令与继承限制等回归。
- `python3 -m py_compile scripts/mc-knowledge.py scripts/mc_knowledge_output.py scripts/test_mc_knowledge_output.py`：通过。
- 额外确定种子随机测试：compact 与 JSON 共 400 组字符串往返，包含中文、希腊字母、emoji、CR、LF、CRLF 组合、U+2028、NEL、引号、反斜杠、NUL 和 Tab；逐页检查整个 stdout 预算并拼接还原原文。
- 本地实际 JAR 数据查询 smoke test：`data damage_type/mob_attack --format compact --max-output-chars 1800` 成功，输出 815 字符，保留 Minecraft 26.3 服务端 bundle SHA-256 校验身份。

本次验证针对 CLI、格式与证据处理，不涉及游戏玩法或客户端呈现，无需启动游戏客户端。未进行浏览器／真实客户端操作，未提交 Git。本文不读取或评价模型实验参与者的答案。

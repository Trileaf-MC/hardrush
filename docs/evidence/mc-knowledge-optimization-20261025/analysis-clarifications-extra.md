# 分析补充澄清：预算错误JSON fallback

本澄清在15次中期过程审查后形成，只修正派生的格式采用归因；不改预注册、题目、skill、冻结CLI、参与者输入、原始调用/返回、status或最终答案，不触发模型重跑，也不改变评分量表。

发现：run-07 / item_16在compact预设下请求NearestAttackableTargetGoal构造器，没有显式异格式参数或环境覆盖。CLI的完整stdout是顶层`ok:false`、`error.code=OUTPUT_BUDGET_TOO_SMALL`的单行JSON诊断（budget=6000、minimum_required_chars=9045）。这是已有renderer在预算无法容纳最小完整封装时的错误输出契约。真实wire格式应继续记录JSON，但不能把CLI自己的诊断fallback归为参与者格式违规。

最小分析器修正位于harness/format_audit.py的独立CLI事件审计，须同时满足：

1. command_execution.aggregated_output能够整体解析为一个完整JSON对象；额外正文、任意子串或嵌套结果不满足。
2. 顶层`ok`严格为false，`error`是对象且`error.code`严格等于`OUTPUT_BUDGET_TOO_SMALL`。
3. 仅一个可静态辨认且可确认格式的简单CLI调用；多指令/重定向等仍不借此解禁。
4. 组预设与expected_format仍为compact，显式format及命令环境均没有异格式覆盖。同格式的显式compact允许。

符合条件时新增`documented_budget_error_fallback=true`，保留`visible_output_format=json`，并将该项的参与者`protocol_deviation`设为false。若存在显式异格式或异环境覆盖，仍保留参与者协议偏离，不因错误字符串而豁免。

汇总保留原可辨调用数和真实wire格式数量；fallback另列`documented_budget_error_fallbacks`，不进入`confirmed_default_adoption_rate`的采用率分母。新增`adoption_eligible_invocations`、`adoption_eligible_confirmed_visible_format`明确该分母。doctor/help仍单列排除，unknown仍保留。原native外层审计不因任意错误字符串放宽，继续保持原有保守归因；本修正依赖CLI事件的精确完整stdout。

这不是排除一次失败试验：该调用、错误、后续调查、整个样本的时间/token/答案仍全部保留。只区分“wire格式确实为JSON”与“参与者是否覆盖格式”这两个不同事实。

合成测试覆盖正常fallback、显式同格式、显式异格式、异环境覆盖、其他错误码、ok=true、嵌套同名错误、尾随额外输出；全部15项harness测试通过。没有为验证本修正重读全量中期日志或执行模型。

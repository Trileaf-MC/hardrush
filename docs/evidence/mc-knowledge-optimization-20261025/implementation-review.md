# 实现独立审查记录

审查范围为CLI输出、预算分页、出处引用与错误引导，不包含运行游戏。

1. 发现默认JSON find-class因新增JSONL text改变body_chars语义；修复为只有compact或显式总预算才添加分页正文。
2. 独立审查发现start_char等于当前行长度时分页next越界；修复为归一化实际起始行，按该行计算max_lines。LF/CRLF回归复核通过。
3. 独立审查发现证据缓存权限/完整性异常绕过结构化输出；修复为OUTPUT_FAILED、保留告警、非零退出且遵守预算，极小预算完整诊断写stderr。PermissionError/ValueError×两格式×两预算复核通过。
4. Wiki预算切割后returned_chars同步正文长度，oldid/section与告警保留。
5. 未发现自动选择同名重载或静默替换继承声明的行为。

全部修复发生于正式模型样本冻结之前。正式27次中不根据结果修订CLI或skill。

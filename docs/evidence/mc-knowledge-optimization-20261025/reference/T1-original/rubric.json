{
  "rubric_version": "1.0",
  "created_date": "2026-10-25",
  "game": {
    "edition": "Java",
    "version": "26.3",
    "scope": "vanilla-server",
    "jar_sha256": "a362163eec5d1612d520772bc16e5b39c09e3b234fdc045f56bf544284ee8ae6"
  },
  "max_score": 100,
  "subscores": {
    "factual_accuracy": 90,
    "evidence_quality": 10
  },
  "rules": [
    "逐项明确表达且正确则取得对应子项全部分数；缺失、含糊到无法判断、或同一答案含未纠正的相反断言则该子项为0；不再自由给半分。",
    "只评分最终交付答案，不从隐藏推理或工具日志补全缺失事实；证据核查可读取答案引证。",
    "约 7+15=22 秒、约 440 tick 的通常概述可得时间分；未声明精确性不因实现边界1—2 tick而扣分。",
    "声明精确 tick 数必须给计数起点／状态／持续true处理条件；精确441或442只在相应跟踪器调用序列条件下接受。",
    "可选深挖不额外加分；错误单列。若它直接否认必答正确事实，相关子项不得分。",
    "两位盲评者独立评分，报告事实90分和来源10分；分歧按冻结基准裁决，不根据组别调整。",
    "来源形式不偏好 skill、CLI、Wiki 或特定路线；依据实际支持力度评分。"
  ],
  "items": [
    {
      "id": "V1",
      "label": "适用范围",
      "max_points": 5,
      "subcriteria": [
        {
          "criterion": "明确 Java 26.3 原版，未将结论直接当成 HardRush 运行结果",
          "points": 5
        }
      ],
      "evidence_files": [
        "doctor.json"
      ]
    },
    {
      "id": "S1",
      "label": "转化结果",
      "max_points": 5,
      "subcriteria": [
        {
          "criterion": "普通 Skeleton 在细雪中可转为 Stray／流浪者",
          "points": 5
        }
      ],
      "evidence_files": [
        "skeleton.json"
      ]
    },
    {
      "id": "S2",
      "label": "完整阶段与名义时间",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "先累计 140 tick，约 7 秒",
          "points": 4
        },
        {
          "criterion": "之后 300 tick 设定的转化／颤抖阶段，约 15 秒；不把 300 次后续调用当成精确完成点",
          "points": 4
        },
        {
          "criterion": "总计约 22 秒或约 440 tick 的惯常概述；明确秒数按 20 TPS，或全程只用 tick；不得声称从几何进入起普遍精确 440 tick",
          "points": 2
        }
      ],
      "evidence_files": [
        "skeleton.json",
        "conversion_tracker.json",
        "conversion_tick_bytecode.json"
      ]
    },
    {
      "id": "S3",
      "label": "第一阶段离开细雪",
      "max_points": 5,
      "subcriteria": [
        {
          "criterion": "未开始转化时离开会清除累计，重新进入重算",
          "points": 5
        }
      ],
      "evidence_files": [
        "conversion_tracker.json"
      ]
    },
    {
      "id": "S4",
      "label": "第二阶段离开细雪",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "已经颤抖／转化时离开也取消，不能离开后自动完成",
          "points": 7
        },
        {
          "criterion": "再次进入要重新经历第一阶段，并非只续跑剩余转化倒计时",
          "points": 3
        }
      ],
      "evidence_files": [
        "conversion_tracker.json"
      ]
    },
    {
      "id": "S5",
      "label": "AI 条件",
      "max_points": 5,
      "subcriteria": [
        {
          "criterion": "NoAI 时不推进转化，或明确只有 AI 开启才处理",
          "points": 3
        },
        {
          "criterion": "转化中仍可运行普通 AI，并非因颤抖自动禁用 AI",
          "points": 2
        }
      ],
      "evidence_files": [
        "conversion_tracker.json",
        "skeleton.json"
      ]
    },
    {
      "id": "S6",
      "label": "转化与冻结的区别",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "Skeleton.canFreeze=false，不能描述为普通冻伤驱动转化",
          "points": 4
        },
        {
          "criterion": "ConversionTracker 计时与 TicksFrozen／完全冻结分离",
          "points": 2
        },
        {
          "criterion": "不会受到普通细雪冻结伤害",
          "points": 2
        },
        {
          "criterion": "Skeleton 的颤抖依据 isConverting；或明确颤抖是第二阶段而非进入细雪立即发生",
          "points": 2
        }
      ],
      "evidence_files": [
        "skeleton.json",
        "inside_block_effect.json",
        "living_ai_step.json"
      ]
    },
    {
      "id": "W1",
      "label": "野生狼攻击流浪者",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "野生狼主动选敌／攻击流浪者，不以先受击为前提",
          "points": 7
        },
        {
          "criterion": "理由为 AbstractSkeleton 目标覆盖 Stray，或等价说明它属于骷髅类猎物",
          "points": 3
        }
      ],
      "evidence_files": [
        "wolf_register_goals.json",
        "stray.json"
      ]
    },
    {
      "id": "W2",
      "label": "驯服后自主猎杀",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "驯服后仍会自主攻击流浪者／骷髅类，正常行动条件下不需要主人先攻击",
          "points": 7
        },
        {
          "criterion": "区分羊兔狐狸／幼龟等未驯服专属捕猎与骷髅类普通目标规则",
          "points": 3
        }
      ],
      "evidence_files": [
        "wolf_register_goals.json",
        "non_tame_target.json"
      ]
    },
    {
      "id": "W3",
      "label": "护主与协助主人攻击",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "区分主人被攻击时护主和主人攻击目标时协助这两个触发",
          "points": 6
        },
        {
          "criterion": "不泛化为无条件攻击主人附近所有敌怪；指出目标合法性、优先级或可攻击性等限制之一",
          "points": 4
        }
      ],
      "evidence_files": [
        "owner_hurt_by.json",
        "owner_hurt_target.json",
        "wolf_wants_attack.json"
      ]
    },
    {
      "id": "W4",
      "label": "命令坐下的差异",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "被命令坐下时两个主人协助目标不能启动，不因主人受击保证起身护主",
          "points": 5
        },
        {
          "criterion": "正常稳定坐下阻止普通自主追逐攻击，区分站立可行动状态；不声称坐下删除骷髅目标或永久关闭全部 AI",
          "points": 5
        }
      ],
      "evidence_files": [
        "wolf_register_goals.json",
        "sit_goal.json",
        "owner_hurt_by.json",
        "owner_hurt_target.json"
      ]
    },
    {
      "id": "E1",
      "label": "可复核证据",
      "max_points": 10,
      "subcriteria": [
        {
          "criterion": "骷髅结论有可识别且支持本版本主张的原版证据：类与成员、官方数据路径或可验证对应版本的网页出处",
          "points": 5
        },
        {
          "criterion": "狼结论有可识别且支持本版本主张的原版证据；仅引资料入口但内容无法支持主张不得分",
          "points": 5
        }
      ],
      "evidence_files": [
        "evidence_manifest.json"
      ]
    }
  ],
  "optional_deep_dives": [
    "afflictionTime 初值0与重置-1导致140/141次合格true调用差异",
    "conversionTime从300递减到负数需301次后续合格调用",
    "Skeleton.tick与super.tick／细雪标记时序",
    "NoAI只暂停处理，不自行清除状态；不 tick 不按墙钟累计",
    "冻结伤害／冻结减速／方块陷入减速区分",
    "流浪者freeze_immune_entity_types标签",
    "orderedToSit与坐姿、canUse与canContinueToUse区分",
    "自卫目标与护主目标区分",
    "NO_WOLF_RETALIATION及wantsToAttack例外",
    "选敌不保证路径可达或立即攻击成功"
  ],
  "known_wrong_claims": [
    "转化开始后离开细雪仍会完成（26.3错误）",
    "NoAI骷髅正常推进自然转化",
    "Skeleton靠普通TicksFrozen满140后直接变为Stray",
    "驯服狼绝不主动攻击流浪者，必须主人先攻击",
    "所有Wolf骷髅类目标都是NonTameRandomTargetGoal",
    "命令坐下后仍保证因主人受击自动起身护主",
    "坐下删除所有选敌／关闭全部AI／赋予无敌"
  ]
}

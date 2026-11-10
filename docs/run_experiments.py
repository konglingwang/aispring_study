#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Runs the prompt experiments from prompt-experiments.md against the local app.
Each request gets a fresh conversationId (no memory pollution).
Run: 2026-11-10 (30 requests; results -> experiment_results.json)."""
import json, time, urllib.request, urllib.parse, uuid, sys, io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

BASE = "http://localhost:8080/ai"

COT_A = "一个商店打8折后再打9折,相当于打几折?"
COT_B = "一个商店打8折后再打9折,相当于打几折?请一步一步思考,先算出8折后的价格系数,再算出叠加后的最终系数,最后给出结论。"

FEW_A = "把这句话转成JSON:昨天下午三点,北京机房的空调报警,情况严重。"
FEW_B = """把故障描述转成JSON。

示例1:
输入:今早八点,上海机房服务器宕机
输出:{"time":"今早八点","site":"上海机房","component":"服务器","severity":"严重"}

示例2:
输入:昨晚十一点,广州网络丢包率30%,轻微影响
输出:{"time":"昨晚十一点","site":"广州机房","component":"网络","severity":"轻微"}

现在处理:昨天下午三点,北京机房的空调报警,情况严重。"""

HALO_BASE = "介绍一下Java的垃圾回收机制。"
HALO_FAKE = "介绍一下Java 17中新增的FiberManager类的设计原理。"
HALO_SEMIFAKE = "Spring AI 2.0 中 DeepSeekChatOptions 的 artifactId 参数是做什么的?"
HALO_GUARD = "仅基于你确定知道的事实回答。如果不确定或不知道,直接说\"我不知道\",不要编造。\n"

MATRIX_Q = "排查一个Spring Boot应用内存持续上涨问题的完整思路是什么?"
MATRIX_COT = MATRIX_Q + "请一步一步思考:先列现象与怀疑点,再给每个怀疑点的验证方法,最后总结排查顺序。"
MATRIX_FEW = """按照下面示例的结构回答问题。

示例结构:
【现象】一句话描述问题
【怀疑点】列出3-4个可能原因
【验证方法】每个怀疑点对应的验证手段
【排查顺序】按优先级排序并说明理由

问题:排查一个Spring Boot应用内存持续上涨问题的完整思路是什么?"""

def ask(prompt, model):
    qs = urllib.parse.urlencode({"userInput": prompt, "conversationId": uuid.uuid4().hex, "model": model})
    with urllib.request.urlopen(f"{BASE}?{qs}", timeout=300) as r:
        return r.read().decode('utf-8')

def run(label, prompt, model, n=1):
    out = []
    for i in range(n):
        try:
            resp = ask(prompt, model)
        except Exception as e:
            resp = f"<ERROR {e}>"
        out.append(resp)
        print(f"[{label} #{i+1}] {len(resp)} chars")
        time.sleep(0.5)
    return out

results = {}

MODELS = ["qwen2.5:7b", "deepseek-coder:6.7b"]

# ---- Experiment 1: CoT, 2 prompts x 2 models x 3 repeats
for m in MODELS:
    results[f"1A|{m}"] = run(f"1A noCoT {m}", COT_A, m, 3)
    results[f"1B|{m}"] = run(f"1B CoT {m}", COT_B, m, 3)

# ---- Experiment 2: few-shot, 2 prompts x 2 models x 1 repeat
for m in MODELS:
    results[f"2A|{m}"] = run(f"2A zero-shot {m}", FEW_A, m, 1)
    results[f"2B|{m}"] = run(f"2B few-shot {m}", FEW_B, m, 1)

# ---- Experiment 3: hallucination
for m in MODELS:
    results[f"3-base|{m}"] = run(f"3 GC baseline {m}", HALO_BASE, m, 1)
    results[f"3-fake|{m}"] = run(f"3 fake noGuard {m}", HALO_FAKE, m, 1)
    results[f"3-fake-guard|{m}"] = run(f"3 fake guard {m}", HALO_GUARD + HALO_FAKE, m, 1)
results["3-semi|qwen2.5:7b"] = run("3 semi guard qwen", HALO_GUARD + HALO_SEMIFAKE, "qwen2.5:7b", 1)

# ---- Experiment 4: matrix 3 strategies x 2 models
for m in MODELS:
    results[f"4-plain|{m}"] = run(f"4 plain {m}", MATRIX_Q, m, 1)
    results[f"4-cot|{m}"] = run(f"4 cot {m}", MATRIX_COT, m, 1)
    results[f"4-few|{m}"] = run(f"4 few {m}", MATRIX_FEW, m, 1)

with open("experiment_results.json", "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=1)
print("DONE -> experiment_results.json")

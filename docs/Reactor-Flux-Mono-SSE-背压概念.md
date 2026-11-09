# Reactor Flux/Mono、SSE 协议格式、背压概念

> 本工程流式输出(`/ai/stream`)背后的三个知识点,全部对照工程真实代码讲解。
> 属于 LEARNING_PLAN.md 第 1 周复习要点。

---

## 1. Reactor Flux / Mono —— Java 响应式流的两个核心类型

一句话:**Mono = 装 0 或 1 个数据的异步管道,Flux = 装 0 到 N 个数据的异步管道**。

```java
Mono<String> mono = webClient.getData();      // 一个结果(或空/异常)
Flux<String> flux = chatClient.prompt()       // 多个结果——SSE 流式
            .stream()
            .content();                       // 本工程的真实代码
```

核心思维转变:调用 `stream()` 那一刻**什么都没发生**,它只是把管道拼好了(惰性)。数据真正流动是在 Spring 订阅(subscribe)这个 Flux 的时候——即浏览器发起请求、Spring MVC 检测到返回值是 Flux、自动订阅并把每个元素写成 SSE 事件的那一刻。

### 基本操作速查(面试常问,记住这几个就够)

| 操作符 | 作用 | 场景 |
|---|---|---|
| `map` | 逐个转换 | `flux.map(String::toUpperCase)` |
| `filter` | 过滤 | 剔除空 chunk |
| `flatMap` | 异步展开(每个元素再发请求) | 检索多个文档片段并发查询 |
| `onErrorResume` | 出错降级 | 模型挂了返回兜底文案 |
| `timeout` | 超时控制 | 本地模型卡死保护 |
| `doOnNext` | 副作用钩子 | 每块到达时打日志 |

在 Spring AI 里几乎不会手写复杂链,框架把 Reactor 封装掉了——这正是"Java 版流式输出半天完成"的原因:**复杂度被框架吃掉了**。

---

## 2. SSE 协议格式 —— 服务器到底往网络上写了什么

SSE(Server-Sent Events)是 HTML5 标准的服务器→浏览器单向推送协议,基于普通 HTTP:

- 响应头 `Content-Type: text/event-stream`
- **永不关闭连接,持续写文本帧**

线上实际传输的样子(每条消息的格式):

```
data:第一块文字

data:第二块文字

data:第三块文字

data:[DONE]
```

规则只有三条:

1. 每帧以 `data:` 开头,内容跟在后面,**两个换行 `\n\n` 表示一帧结束**
2. 一行 `data:` 的内容不能含裸换行(要换行得拆成多帧)——本工程前端手工实现了 SSE 解析:
   ```js
   let chunk = decoder.decode(value, { stream: true });
   for (const line of chunk.split('\n')) {
       const payload = line.startsWith('data:') ? line.slice(5) : line;
   ```
   处理 `slice(5)` 去掉 `data:` 前缀、按 `\n` 拆帧,就是 SSE 的解析逻辑。(正常该用 `EventSource` API,但它不支持自定义 URL 参数,所以用 fetch 手读)
3. 其他字段(`event:`、`id:`、`retry:`)本场景用不到,知道存在即可

### 和 WebSocket 的区别(面试常考)

| | SSE | WebSocket |
|---|---|---|
| 方向 | 单向(服务器→客户端) | 双向 |
| 基础 | 普通 HTTP | 独立协议 |
| 重连 | 浏览器自动重连 | 需自己实现 |
| 数据 | 文本 only | 支持二进制 |

**"服务器生成、客户端只收"的场景用 WebSocket 属于杀鸡用牛刀** —— LLM 打字机输出就是 SSE 的教科书场景。

---

## 3. 背压(Backpressure)—— 慢消费者问题

### 问题是什么

Ollama 每 10ms 吐一块,浏览器网络慢、每 100ms 才消化一块——**生产快于消费,数据在内存里堆积**。

### 背压怎么解

消费端反过来告诉生产端"你慢点"。Reactor 的模型里数据不是被"推"给订阅者的,而是订阅者通过 `request(n)` 主动"拉" n 条——**想要才拉,天然不会堆积**。

直观类比:食堂阿姨不是把菜全扣你盘子里,而是你吃完一盘喊一声再来一盘。

### 为什么"理解即可"

背压的传导链要求全链路都是响应式(`Flux` 一路到网络层)。本场景中:

- 消费者是 HTTP 响应流,Spring 底层在 TCP 拥塞时自动减速
- 真实场景里 LLM 本地推理 20 token/s,比网络慢得多,**生产者才是瓶颈,背压根本不会触发**

什么时候它会真登场?第 2 个月 RAG 里 `flatMap` 并发查询向量库、下游数据库被打爆的时候——那时再回来重新看这个概念。

---

## 串起来:本工程流式输出的真实链路(面试可直接讲)

```
浏览器 fetch → Spring 订阅 Flux → Reactor 从 Ollama SSE 端点拉 chunk
→ 每个 chunk 经 content() 提取 → 写成 SSE 帧(data:xxx\n\n)推给浏览器
→ 前端 ReadableStream 逐块读 → 打字机效果
```

三个知识点分别对应这条链的:**数据模型(Flux/Mono)**、**线上格式(SSE 帧)**、**流量控制(背压)**。

面试被问"流式输出怎么实现",把这条链讲出来,比背概念有说服力得多。

---

## 巩固方式(20 分钟)

在本工程上手玩一次:

```bash
curl -N "http://localhost:8080/ai/stream?userInput=用三个段落介绍Java"
```

`-N` 禁用 curl 缓冲,会亲眼看到 `data:` 帧一块块蹦出来,和前端解析代码对上号——这个知识点就长在经验里而不是笔记里了。

# 讲下目前 Spring AI 架构原理,感觉目前封装是不是做的太好了

> 属于 LEARNING_PLAN.md 第 1 周复习要点的延伸笔记。
> **创建**:2026-11-07 | **最后复习**:____(每次复习后填写)

## 一、整体架构:五层洋葱

```
┌─ 你的代码          MyController / ChatClientConfig         (你写的)
├─ 第1层  ChatClient        prompt().user().call() 高级API     (Fluent 门面)
├─ 第2层  Advisor 链        MessageChatMemoryAdvisor          (请求/响应拦截器)
├─ 第3层  ChatModel         DeepSeekChatModel(每厂商一个)      (协议适配核心)
├─ 第4层  Api Client        DeepSeekApi(RestClient封装)      (HTTP 事实层)
└─ 第5层  网络协议           OpenAI 兼容 HTTP + SSE            (线上字节)
```

对照本工程里出现过的东西,每层都实际"摸到过":

| 层 | 工程里的对应物 |
|---|---|
| ChatClient | `chatClient.prompt().user(x).stream().content()` |
| Advisor | `MessageChatMemoryAdvisor` + `ChatMemory.CONVERSATION_ID` |
| ChatModel | `DeepSeekChatOptions.builder().model(...)`(模型路由在这层生效) |
| Api Client | **404 那次**:默认 `/chat/completions` vs Ollama 要 `/v1/...` —— 改的 `completions-path` 就是这层的配置 |
| 协议 | curl 直连 Ollama 的 `data:` SSE 帧 |

**关键设计思想:每层只做一件事,且都能单独替换**。换模型厂商 → 换 ChatModel 实现(配置级);换向量库 → 换 VectorStore 实现;加横切逻辑(记忆、安全、日志)→ 加 Advisor,不碰核心链路。这就是为什么双模型路由只需要 `.options()` 一行。

## 二、"封装太好"的三笔账

### 好处(已在享受)

1. **出错时错误信息是分层的** —— `NonTransientAiException: HTTP 404` 带着 HTTP 状态码上来,`GlobalExceptionHandler` 直接映射 502。如果封装真"太厚",只会拿到一个 `RuntimeException`
2. **心智减负** —— 半天完成流式;Python 侧要自己管 async generator、SSE 编码、重连
3. **生态互通** —— Spring 的观察体系(Micrometer)、重试(Spring Retry)对 ChatModel 天然生效

### 代价(值得警惕的两点)

1. **默认值坑**:默认 `completions-path=/chat/completions` 这种"帮你定好的默认值",对接非 DeepSeek 官方端点时就是坑 —— **封装的默认值是按官方云服务调的**,本工程接的是 Ollama。这也是为什么每次都要反编译 jar 核实 API 而不是信教程
2. **调试隔层**:出问题时要跨 5 层往下挖(500 那次实际经历了 Controller→ChatModel→HTTP→Ollama 全链)

### 但它不是黑盒:三个"逃生舱"

**逃生舱 1 —— 绕过 ChatClient 直用 ChatModel**:

```java
DeepSeekChatModel model; // 直接注入
model.call(new Prompt(List.of(new UserMessage("hi")), options));
```

ChatClient 只是 ChatModel 的语法糖,随时可以退到下一层。

**逃生舱 2 —— 拿到底层 HTTP**:

```java
DeepSeekApi.Builder().completionsPath("/v1/chat/completions")  // 本工程配过的
```

甚至换成 WebClient/RestClient 手写请求 —— 排查 500 时 curl 直连 Ollama 就是在这一层之下验证的。

**逃生舱 3 —— Advisor 就是 Spring AOP 的翻版**:

`before(request)/after(response)` 的签名,和 `HandlerInterceptor`、Servlet Filter 是同构的。**在用 Java Web 十几年的直觉理解 Spring AI** —— 概念没有一个是新的,只是名字换了。这正是它对 Java 工程师的价值。

## 三、问题的答案

**"封装是不是做得太好" —— 判断标准不是"厚不厚",而是"能不能按需降层"。**

Spring AI 的答案是可以:本工程往下随时能挖到 HTTP 帧,往上可以用 Spring 全家桶,这叫"分层封装"。真正危险的是黑盒式封装(某些托管 Agent 平台,连 prompt 都不让看)。

## 四、后续两个行动(衔接学习计划)

1. **第 2 周 Prompt 工程做完后,做一次"拆层实验"**:写一个直连 Ollama `/v1/chat/completions` 的裸 WebClient 版 `/ai/raw` 接口(不用 Spring AI 任何类),和现有 `/ai/stream` 对比 —— 会对"框架到底替你做了哪几件事"(SSE 解析、消息组装、重试、记忆注入)有肌肉级的理解,面试讲"流式输出原理"时直接碾压背概念的人
2. **读源码的顺序**:别从 ChatClient 读(全是 fluent 语法糖,读着无聊),从 `MessageChatMemoryAdvisor` 读起(150 行,已读过一半),再跳 `DeepSeekApi`(看 HTTP 怎么发)—— 这条路径正好从"用过的"到"没见过的"

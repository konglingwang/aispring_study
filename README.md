# demo — Spring AI + 本地 DeepSeek 对话服务

基于 Spring Boot 4.1.2 + Spring AI 2.0.1 的 AI 对话 Demo:后端通过 DeepSeek starter 调用本地 Ollama 运行的 `deepseek-coder:6.7b` 模型,提供带会话历史的 HTTP 接口;前端为内置聊天页面;另附 frp 内网穿透配置,可将服务暴露到公网。

> 本工程同时是"大模型应用开发学习计划"的载体,每周学习产出的功能直接落在本工程上,详见 [LEARNING_PLAN.md](LEARNING_PLAN.md)。
> **最后更新**:2026-11-10(前端 v8:本地托管 Markdown 渲染/规范 SSE 帧解析;Prompt 对照实验完成)

## 技术栈

| 组件 | 说明 |
|---|---|
| Java 17 / Spring Boot 4.1.2 (war) | Web 框架,内置 Tomcat 部署 |
| Spring AI 2.0.1 (`spring-ai-starter-model-deepseek`) | AI 模型接入层 |
| Ollama | 本地模型运行时,OpenAI 兼容端点 `http://localhost:11434/v1` |
| `deepseek-coder:6.7b` | 代码专用模型(显存约 8GB,量化版 4–5GB) |
| frp | 内网穿透(可选) |

## 项目结构

```
src/main/java/com/example/demo/
├── SpringAiDemoApplication.java   # 启动类
├── MyController.java              # /ai 对话接口(@RestController)
└── ChatClientConfig.java          # ChatClient Bean 配置
src/main/resources/
├── application.properties         # DeepSeek / Ollama 连接配置
└── static/chat.html               # 前端聊天页面(无依赖,原生 JS)
```

## 核心配置(application.properties)

```properties
spring.application.name=demo

# DeepSeek - local Ollama (OpenAI-compatible endpoint)
spring.ai.deepseek.base-url=http://localhost:11434
spring.ai.deepseek.api-key=ollama
# Default path is /chat/completions (DeepSeek official API format), Ollama needs /v1 prefix
spring.ai.deepseek.chat.completions-path=/v1/chat/completions
spring.ai.deepseek.chat.model=deepseek-coder:6.7b
spring.ai.deepseek.chat.temperature=0.7
```

### 踩坑记录(重要)

1. **`completions-path` 必须显式指定** — Spring AI DeepSeek 模块默认请求 `/chat/completions`(DeepSeek 官方 API 格式,无 `/v1` 前缀),打到 Ollama 上返回 404,最终表现为接口 500(`NonTransientAiException: HTTP 404`)。Ollama 的 OpenAI 兼容端点在 `/v1/chat/completions`,必须覆盖此配置。
2. **属性名用新前缀** — Spring AI 2.0 中 `spring.ai.deepseek.chat.options.model/temperature` 已过时,应使用 `spring.ai.deepseek.chat.model` / `spring.ai.deepseek.chat.temperature`。
3. **控制器必须加 `@RestController`** — 只有 `@GetMapping` 不会注册端点,访问 404。
4. **Properties 中文注释乱码** — Properties 默认按 ISO-8859-1 读取,注释一律用英文(或 IDEA 中设置 `File Encodings → Properties Files` 为 UTF-8)。
5. **Ollama 直连正常但接口 500** — 先用相同参数直连验证模型侧,再查应用日志:
   ```bash
   curl http://localhost:11434/v1/chat/completions \
     -H "Content-Type: application/json" -H "Authorization: Bearer ollama" \
     -d '{"model":"deepseek-coder:6.7b","messages":[{"role":"user","content":"hello"}]}'
   ```
6. **通过 frp 公网 HTTP 地址访问前端报 `Cannot access 'conversationId' before initialization`** — `crypto.randomUUID()` 只在安全上下文(https 或 localhost)可用,普通 HTTP 下是 undefined,初始化语句抛异常中断脚本,后续读取该变量即 TDZ 报错。已在前端加 `Math.random()` 降级生成器(`uuid()` 函数),任何 HTTP 来源都可正常工作。
7. **jsdelivr CDN 在本地网络不可达导致 Markdown 渲染失效/发消息无反应** — 实测 `cdn.jsdelivr.net` 连接失败(000),v5 页面因 `marked` 未加载抛 `ReferenceError`。修复:库文件本地托管 `static/vendor/`(marked.min.js / highlight.min.js / github.css),加载失败时回落 cdnjs,再失败时降级纯文本 —— 三层保底。**教训:全本地化方案(本地模型)的前端不该依赖外部 CDN**。
8. **`@RestControllerAdvice` 兜底 `Exception.class` 会吞掉静态资源 404** — 不存在的静态文件由 Spring 抛 `NoResourceFoundException`(本应 404),被 catch-all 捕获后变成 500。后果之一:前端 `<link onerror>` 回落逻辑失效、缺失的 CSS 不会触发 CDN 兜底。修复:显式 `@ExceptionHandler(NoResourceFoundException.class)` 返回 404,**catch-all 只留给真正的意外异常**。这个坑拖了代码高亮的后腿(高亮 span 生成了但 CSS 没加载,显示为灰底黑字)。
9. **手写 SSE 解析器丢换行符 → Markdown 渲染崩坏(灰底白字)** — SSE 规范中,载荷内的换行编码为多条 `data:` 行(空 `data:` 行即换行本身),帧以 `\n\n` 结尾。天真写法"按 `\n` 拆行、跳过空行"会把模型输出里的**所有换行丢掉**,拼成无结构的一整行,marked 认不出代码围栏(围栏必须在行首),整个渲染崩坏。纯文字回复不露馅,代码块必现。正确做法:**缓冲 + 按帧(`\n\n`)切分,帧内多条 `data:` 行用 `\n` 重新连接**还原创文,末尾不足一帧的残片单独 flush。这个坑也是 `docs/Reactor-Flux-Mono-SSE-背压概念.md` 里"SSE 三条规则"第 2 条的实战注脚。
10. **流式接口的异常不能走 `@RestControllerAdvice` 的 JSON 错误体** — SSE 响应的 Content-Type 已定为 `text/event-stream`,异常进入 advice 后试图用 JSON converter 写 Map 错误体 → `No converter ... with preset Content-Type 'text/event-stream'` → `HttpMessageNotWritableException`,再被 catch-all 捕获形成二次异常循环。修法两处:①流接口用 `.onErrorResume(ex -> Flux.just("[ERROR] ..."))` 把错误作为 SSE 事件写进流;②advice 里显式处理 `AsyncRequestNotUsableException`(客户端中途断开)并静默吞掉 —— 浏览器都没了,写什么都失败。**原则:错误处理必须与响应类型匹配,流式响应的报错也是流的一部分**。

## 后端接口

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| GET | `/ai?userInput=...` | `userInput`(必填)、`conversationId`(可选,默认 `default`)、`model`(可选,默认 `deepseek-coder:6.7b`) | 阻塞式:带会话历史调用本地模型,返回完整回复文本 |
| GET | `/ai/stream?userInput=...` | 同上 | **SSE 流式**:逐块推送回复文本,前端打字机效果 |

```java
@RestController
public class MyController {

    // Models available in the local Ollama instance
    private static final String MODEL_CODER = "deepseek-coder:6.7b";
    private static final String MODEL_QWEN = "qwen2.5:7b";

    private final ChatClient chatClient;

    public MyController(@Qualifier("defaultChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/ai")
    String generation(@RequestParam String userInput,
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(defaultValue = MODEL_CODER) String model) {

        // Resolve requested model; unknown values fall back to the coder model
        String targetModel = MODEL_QWEN.equalsIgnoreCase(model) ? MODEL_QWEN : MODEL_CODER;

        return this.chatClient.prompt()
                .user(userInput)
                // conversationId selects which history window the advisor reads/writes
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                // per-request model override: same Ollama endpoint, different model
                // (note: options() takes a ChatOptions.Builder, not a built instance)
                .options(DeepSeekChatOptions.builder().model(targetModel))
                .call()
                .content();
    }
}
```

### 双模型调用说明

所有模型都在同一个本地 Ollama 实例里,走同一个 `base-url`,因此**不需要第二个 starter/ChatClient**,通过 `DeepSeekChatOptions.builder().model(...)` 在每次请求时覆盖模型即可:

- `deepseek-coder:6.7b`(默认)— 代码专用:写码/改码/解释代码,多轮代码上下文延续能力强
- `qwen2.5:7b` — 通用对话:日常聊天、记住个人信息、指令跟随

```bash
curl "http://localhost:8080/ai?userInput=我叫小明,记住我的名字&conversationId=t1&model=qwen2.5:7b"
curl "http://localhost:8080/ai?userInput=我叫什么名字?&conversationId=t1&model=qwen2.5:7b"   # qwen 能答出
curl "http://localhost:8080/ai?userInput=我叫什么名字?&conversationId=t1"                    # coder 默认答不出
```

### 会话历史(内存存储)

`ChatClientConfig` 中装配,采用 Spring AI 2.0 新 API:

- `InMemoryChatMemoryRepository` — 内存存储,重启即清空(需持久化时换成别的 `ChatMemoryRepository` 实现)
- `MessageWindowChatMemory` — 每个会话只保留最近 20 条消息(滑动窗口),防止 prompt 无限膨胀
- `MessageChatMemoryAdvisor.builder(chatMemory).build()` — 挂到 ChatClient 的 advisor 链上,每次请求自动:读取该 `conversationId` 的历史 → 拼进 prompt → 调用后将本轮 user/assistant 消息写回记忆

同一个 `conversationId` 连续请求即形成多轮上下文,例如:

```bash
curl "http://localhost:8080/ai?userInput=我叫小明&conversationId=demo1"
curl "http://localhost:8080/ai?userInput=我叫什么名字?&conversationId=demo1"   # 能答出"小明"
curl "http://localhost:8080/ai?userInput=我叫什么名字?&conversationId=demo2"   # 不知道,新会话
```

⚠️ 注意:多轮历史使 prompt 变长,本地 6.7b 模型响应会随轮数增多逐渐变慢。

⚠️ **验证记忆时用代码追问,别用"记住我的名字"式测试** — 实测 `deepseek-coder:6.7b` 即使收到完整历史也答不出"我叫小明/秘密词是什么"这类闲聊记忆题(直连 Ollama 验证过,与 Spring AI 无关);但"把刚才的函数改成减法"这类代码上下文延续表现正确。判断记忆是否生效的可靠测试:`conversationId` 固定,先让它写个函数,再让它修改这个函数。

## 前端页面

访问 `http://localhost:8080/chat.html`(重启后生效;改名为 `index.html` 可作为根路径欢迎页)。

- 气泡式聊天界面:右侧用户消息、左侧模型回复
- 回车或点击按钮发送,请求期间显示"思考中...",失败显示红色错误提示
- **会话历史**:页面加载时生成随机 `conversationId` 随请求发送;刷新页面即开启新会话
- **双模型切换**:输入框旁下拉框选择 `deepseek-coder` / `qwen2.5`,随请求发送 `model` 参数
- **手机适配**:`<600px` 布局重构,输入栏 `position:fixed` 钉在屏幕底部(避开移动端 `100vh` 视口坑),控件 44px 触控高度,输入框 16px 字号防 iOS 聚焦缩放,下拉框去原生外观自绘箭头
- 纯原生 HTML/JS,零依赖,`fetch('/ai?userInput=...&conversationId=...&model=...')` 与后端交互

## 启动步骤

```bash
# 1. 启动 Ollama(确认模型已拉取)
ollama list                        # 应列出 deepseek-coder:6.7b
curl http://localhost:11434/v1/models   # 验证服务

# 2. 启动应用(IDE 中运行 SpringAiDemoApplication,或)
mvn spring-boot:run                 # 项目无 mvnw wrapper,需系统安装 Maven

# 3. 验证
curl "http://localhost:8080/ai?userInput=hello"
# 浏览器打开 http://localhost:8080/chat.html
```

## frp 内网穿透(可选)

`D:\frp\frpc.toml` 已配置:本机 8080 → 服务器 `47.92.193.101:8101`。

```bash
# 前台运行(测试)
D:\frp\frpc.exe -c D:\frp\frpc.toml

# 注册为 Windows 服务(长期,需管理员)
D:\frp\frpc.exe service install -c D:\frp\frpc.toml
net start frpc
```

启动后公网访问 `http://47.92.193.101:8101/chat.html`。

⚠️ 前提:服务器端 frps 放行 7000(通信)与 8101(代理)端口的安全组/防火墙规则。
⚠️ 暴露公网后 `/ai` 接口对任何人开放,注意不要一并穿透敏感服务。

## 后续可改进

- [x] 加会话历史(传入历史 messages,`ChatClient` 的 `advisors` + 内存存储)— 已完成,详见"会话历史"一节
- [x] 双模型调用(`deepseek-coder:6.7b` / `qwen2.5:7b` 按请求切换)— 已完成,详见"双模型调用说明"
- [x] 升级为流式响应(`stream()` + SSE `/ai/stream`,前端逐字显示)— 已完成
- [ ] 前端支持 Markdown 渲染(代码高亮)
- [ ] 会话历史持久化(自定义 `ChatMemoryRepository` 落库,重启不丢)
- [ ] RAG:文档上传 → 分块 → Embedding → 向量库 → 检索问答(第 2 个月学习主线)

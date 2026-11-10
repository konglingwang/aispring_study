# 问题解决:本地 IDEA 关联不上 GitHub

**日期**:2026-11-10
**症状**:IDEA 反复关联不上 GitHub(OAuth 授权页按钮点不动、token 报权限不足),多轮排查
**环境**:Windows 11 / IDEA / Bitz Net 代理 / 命令行 Git

---

## 一、根因链(四个问题叠加,逐层暴露)

| 层 | 现象 | 根因 | 解法 |
|---|---|---|---|
| ① 网络 | IDEA/git 连 GitHub 全超时 | 直连 `github.com` 不通(HTTP 000 超时),Bitz Net 代理只覆盖了浏览器通道 | 找到本地代理端口 `127.0.0.1:7897`,给 git 配 GitHub 专属代理 |
| ② OAuth | 授权页 "Authorize JetBrains Account" 按钮点不了 | OAuth 浏览器回调环节卡死(刷新/换浏览器/Enter 均无效) | 放弃 OAuth,改用 Token 方式(平级官方方案) |
| ③ Token 权限 | `incorrect credentials, insufficient scopes granted to token` | fine-grained token 生成时漏勾 Contents 权限或 Repository access 范围 | 重新生成补齐权限,或直接换 classic token |
| ④ Token 类型 | 精细权限反复漏勾 | fine-grained 按"仓库 × 权限"勾选,死角多 | **改用 classic token,勾 `repo` scope 一个即全覆盖** |

**诊断命令留档**(下次同类问题直接跑):

```bash
# 网络层:直连是否通(000 = 不通,200 = 通)
curl -s -o /dev/null -w "%{http_code}" https://github.com --max-time 10

# 找本地代理端口:查 127.0.0.1 的 LISTENING 端口(netstat 输出里 7897 = Clash 系默认混合端口)
netstat -ano | grep "LISTENING" | grep "127.0.0.1"

# 代理是否可达 GitHub
curl -s -o /dev/null -w "%{http_code}" --proxy http://127.0.0.1:7897 https://github.com

# git 走代理的终极验证(能列出远程 HEAD = 全通)
git ls-remote https://github.com/git/git HEAD
```

---

## 二、最终方案(已验证,完整步骤)

### 1. git 命令行代理(只对 github.com,不影响内网 git)

```bash
git config --global http.https://github.com.proxy http://127.0.0.1:7897
git config --global https.https://github.com.proxy http://127.0.0.1:7897
```

### 2. IDEA 自身 HTTP 代理(IDE 登录/内置 GitHub 操作不读 git 配置)

```
IDEA → File → Settings → Appearance & Behavior → System Settings → HTTP Proxy
  → Manual Proxy Configuration → Host: 127.0.0.1, Port: 7897
```

### 3. Classic Token 生成

```
github.com → 头像 → Settings → Developer settings
→ Personal access tokens → Tokens (classic)
→ Generate new token (classic):
    Note: IDEA
    Expiration: 90 days
    Scopes: ✅ repo(必须,clone/push/私有公有仓库全覆盖)
           (workflow / user 可选按需)
→ Generate → 立刻复制(ghp_ 开头,离开页面永久不可见)
```

### 4. IDEA 添加账号

```
Settings → Version Control → GitHub → 删除旧账号
→ + → Use Token → 粘贴(首尾不得带空格/换行)→ Add Account
```

**成功标志**:GitHub 面板出现头像 + 用户名 + 仓库列表。

---

## 三、关键认知点(为什么这么选)

1. **"网页能访问但工具不行" = 代理只覆盖了浏览器** —— Bitz Net 的 TUN/系统代理接管了浏览器流量,但 git 和 IDEA 的 HTTP 客户端各自独立,必须分别配
2. **OAuth 与 Token 是平级方案,不是绕过** —— "Use Token" 是 JetBrains 官方支持的标准入口,安全性等价(只授勾选的权限、可随时作废),只是省掉了爱卡死的浏览器回调环节
3. **Classic vs Fine-grained token 的取舍**:
   - classic:勾一个大 scope(`repo`)全覆盖,**无漏勾死角**,IDEA 兼容性多年验证 → 本场景首选
   - fine-grained:按"仓库×权限"精细勾,适合最小授权,但漏勾 Contents/Repository access 就报 insufficient scopes → 桌面工具场景易踩坑
4. **Token = 密码级凭证** —— 设过期时间、用后可删、永远不贴给任何人/工具/AI/代码库

---

## 四、通用排障模板(同类问题直接套)

```
"某工具连不上 X 服务,但浏览器可以"
→ ① curl 直连测试:不通 = 网络层问题
→ ② netstat 找本地代理端口 → curl --proxy 验证代理可达
→ ③ 给工具配代理(git 用 config,IDE 用自身 HTTP 设置,各管各的)
→ ④ 认证环节:OAuth 卡死 → 换 Token;报 scopes 不足 → 检查勾选,拿不准就用 classic 大 scope
→ ⑤ 粘贴凭证时首尾不留空白(高频翻车点)
```

---

## 五、遗留事项

- [ ] 清理排查过程中的旧测试 token(github.com/settings/tokens 中 Delete 作废)
- [ ] git 代理 7897 与 Bitz Net TUN 全局模式并存时冗余但无害;**代理工具关闭后 git 会直连失败,需同步撤销**:`git config --global --unset http.https://github.com.proxy`
- [ ] `demo` 工程推 GitHub 云端备份(先写 .gitignore 排除 target/、.idea/,再 init + push)

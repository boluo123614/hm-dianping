---
name: hmdp-local-verify
description: 在黑马点评（hm-dianping）本机环境上启动服务、并对其接口 / Redis 状态做端到端验证。当用户说「验证登录」「验证缓存」「看看 Redis 里的 key」「接口跑不通」「起一下环境」，或需要在本项目里实测某个接口行为（登录、缓存、秒杀、分布式锁等）时使用。
agent_created: true
---

# hmdp 本机环境启动与端到端验证

本机环境的探测手段有限（`wsl.exe`、`sc.exe` 被沙箱拦截，bash 内建命令常缺失），
且 JDK 版本远高于框架预期。以下步骤已实测跑通，直接照做即可，不要重新摸索。

## 0. 环境事实（实测，勿凭记忆假设）

| 项 | 值 |
|---|---|
| JDK | `E:\java\jdk25`（25.0.2） |
| Maven | `E:\java\apache-maven-3.9.4`，本地仓库 `E:\java\apache-maven-3.9.4\maven_repo` |
| MySQL | **9.6.0**，`E:\java\mysql-9.6.0-winx64`，3306 |
| Redis | 8.0.5，跑在 **WSL2 Ubuntu**，Windows 侧 `127.0.0.1:6379` 可连，`password=1234` |
| nginx | `E:\javaweb_code\hm-dianping\nginx-1.18.0`，8080 → 代理 8081 |
| 后端 | 8081，`HmDianPingApplication` |

**可用工具**：绝对路径调用的 `python.exe` / `java.exe` / `mvn.cmd`、`netstat`、`findstr`、`tasklist`、`taskkill`。
**不要用**：`ls` / `grep` / `tail` / `head` / `sleep`（bash 内建缺失）→ 改用 `Glob` / `Grep` / `Read` 或 python 脚本。

Python 解释器：`C:\Users\86157\.workbuddy\binaries\python\versions\3.13.12\python.exe`

## 1. 启动顺序（不可颠倒）

1. **Redis**（由用户在 WSL 里起；用第 2 步的探测脚本确认）
2. **后端**（见下，或用户在 IDEA 里启动）
3. **nginx**（8080）

### 启动后端（两个参数都是必需的）

```bash
cd "E:/javaweb_code/hm-dianping" && export JAVA_HOME="E:/java/jdk25" && \
"E:/java/apache-maven-3.9.4/bin/mvn.cmd" -B spring-boot:run \
  "-Dmaven.compiler.proc=full" \
  "-Dspring-boot.run.arguments=--server.port=8081" \
  > .workbuddy/tmp/backend.log 2>&1
```

用 `run_in_background: true` 启动。两个参数的来历：

- `-Dmaven.compiler.proc=full` —— **JDK 23 起 javac 默认不再自动运行注解处理器**，不加则 Lombok 全部失效，报 26 个「找不到符号」（`log` / getter / 构造器）。见第 4 节。
- `--server.port=8081` —— 沙箱注入了环境变量 `SERVER__PORT=55334`，Spring 宽松绑定会把它当 `server.port`，导致 Tomcat 起在 55334 并报 «Port 55334 already in use»。命令行参数优先级最高，可覆盖。

### 启动 nginx

```bash
cd "E:/javaweb_code/hm-dianping/nginx-1.18.0" && ./nginx.exe
```

必须 `run_in_background: true`（前台跑会被 SIGTERM）。停止：`./nginx.exe -s stop`。

### 等待就绪

不要用 `sleep`，用 python 轮询端口：

```python
import socket, time
t0 = time.time()
while time.time() - t0 < 120:
    try:
        socket.create_connection(("127.0.0.1", 8081), timeout=2).close()
        print("ready"); break
    except OSError:
        time.sleep(2)
```

## 2. 探测 Redis：**不要依赖 redis-cli**

Windows 上没有 `redis-cli`（只有一个 WSL 里的实例），直接手写 RESP 协议。
现成脚本：`.workbuddy/tmp/redis_probe.py`（认证、PING、INFO、DBSIZE、KEYS、SET/GET 自检）。
最小可用片段：

```python
import socket
s = socket.create_connection(("127.0.0.1", 6379), timeout=5)
def cmd(*a):
    p = [f"*{len(a)}\r\n".encode()]
    for x in a:
        b = str(x).encode(); p.append(f"${len(b)}\r\n".encode() + b + b"\r\n")
    s.sendall(b"".join(p)); time.sleep(0.1); return s.recv(65536)
```

**坑**：读数组回复时若把 key 解码成 str 再回传，含非 UTF-8 字节的 key 会因编码往返而查不到（返回 `TYPE none`）。
生产级用法见 `.workbuddy/tmp/e2e_verify.py` 里的 `Redis` 类（递归解析 RESP）。

## 3. 端到端验证接口

现成脚本：**`.workbuddy/tmp/e2e_verify.py`** —— 直接跑，输出 `18/18` 即全绿。

关键技巧：

- **验证码只能从后端日志拿**（没有真实短信）。`sendCode` 里 `log.info("发送验证码成功，验证码为：{}", code)`，
  用正则 `验证码为：(\d{6})` 从 `.workbuddy/tmp/backend.log` 抓最后一条。
- **不带 token 应 401**；带 `authorization: {token}` 请求头应 200。
- **滑动过期**要这样测才准：先 `EXPIRE key 100` 把 TTL 压短，再发一次请求，看是否回弹到 1800s。
  只等待不发请求时 TTL 应自然递减 —— 两者都验到才能证明是「靠 expire 刷新」而非别的机制。
- **经 nginx 走一遍**：`http://localhost:8080/api/user/me`，验证 `authorization` 头能透传。

## 4. 疑难排查手册

### 想验证「某个实体类在 Hutool / Jackson 下的序列化行为」而不启动整个应用
直接用 `target/classes` 里已编译的实体类，配上 jar 跑单文件 Java，几秒出结果：

```bash
cd "E:/javaweb_code/hm-dianping"
H="E:/java/apache-maven-3.9.4/maven_repo/cn/hutool/hutool-all/5.7.17/hutool-all-5.7.17.jar"
J="E:/java/apache-maven-3.9.4/maven_repo/com/fasterxml/jackson/core/jackson-annotations/2.11.4/jackson-annotations-2.11.4.jar"
"E:/java/jdk25/bin/java.exe" -cp "target/classes;$H;$J" .workbuddy/tmp/SomeProbe.java
```
（单文件源码模式，`-cp` 里带依赖即可。报「未知的枚举常量 IdType.AUTO」这类 mp 注解缺失警告可忽略。）

现成脚本：`.workbuddy/tmp/HutoolJsonProbe.java`（普通 POJO + LocalDateTime 往返）、
`.workbuddy/tmp/ShopTypeJsonProbe.java`（真实实体 + 链式 setter + Jackson 注解）。

**已实测结论**：
- Hutool `JSONUtil` 对 `LocalDateTime` 可无损往返，存成**毫秒时间戳**；裸 `new ObjectMapper()` 会抛 `InvalidDefinitionException`
- `@Accessors(chain = true)` 的链式 setter，Hutool 反序列化**能**正常赋值
- **`@JsonIgnore` 是 Jackson 注解，Hutool 不认** → 走 Hutool 缓存的 JSON 会带上被 `@JsonIgnore` 的字段（如 `createTime`/`updateTime`）。不影响正确性，只是多占空间

### 症状：`mvn compile` 报一堆「找不到符号」（log / getId / 构造器）
**根因**：Lombok 注解处理器没跑。**先验证假设**：加 `-Dmaven.compiler.proc=full` 重编，若能过就坐实。
**根治**：在 `pom.xml` 的 `maven-compiler-plugin` 里配 `<proc>full</proc>` 与 `annotationProcessorPaths`（指向 `${lombok.version}`）。
注意 IDEA 自带注解处理开关（默认开），所以「IDEA 能跑但命令行不能跑」是这个问题的典型特征。

### 症状：`HikariPool-1 - Starting...` 之后日志再无输出，接口挂起
**根因**：MySQL 不可用。**不要去怀疑驱动版本**（5.1.47 与 MySQL 9.6.0 实测兼容）。
**判别顺序**：
1. `tasklist /FI "IMAGENAME eq mysqld.exe"` —— 进程是否在
2. `netstat -ano | findstr LISTENING | findstr ":3306"` —— 是否在监听
3. 读错误日志 `E:\java\mysql-9.6.0-winx64\data\LAPTOP-15AITHCS.err`：**若最后一条是 `ready for connections` 而没有对应的 `Shutdown complete`，说明是异常终止而非正常关闭**
4. 想让用户重启 MySQL —— **不要自己启动 mysqld**，根目录没有 `my.ini`，直接拉可能初始化到错误的 datadir

MySQL 恢复后 **Hikari 池会自行恢复**，不需要重启后端。

### 症状：Tomcat 起在奇怪的端口（如 55334）并报端口占用
**根因**：沙箱环境变量 `SERVER__PORT` 被 Spring 宽松绑定接管。
用 `-Dspring-boot.run.arguments=--server.port=8081` 覆盖。用户从 IDEA 启动无此问题。

### 日志里哪些是「真错误」、哪些是新 JDK 的噪音
- **噪音，不用管**：`A restricted method in java.lang.System has been called`（Tomcat native / Jansi）
  、`terminally deprecated method in sun.misc.Unsafe`（Netty）
  、`Multiple Spring Data modules found, entering strict repository configuration mode!`（INFO）
- **真错误**：`APPLICATION FAILED TO START`、`Port xxx already in use`、`HikariPool ... Starting...` 后无后续

## 5. 收尾

验证做完后，**把承载 8081 的进程 kill 掉**，把端口让给用户在 IDEA 里启动：

```bash
netstat -ano | findstr LISTENING | findstr ":8081"   # 拿 PID
taskkill /PID <PID> /T /F
```

nginx / Redis / MySQL 可以保留运行。

## 6. 与用户协作的边界

- 用户明确说过 **「我自己写，你先别动」**：改业务代码前先确认范围；探针脚本写在 `.workbuddy/tmp/`，不要污染 `src/`。
- 统一用 Hutool（`BeanUtil` / `UUID` / `StrUtil` / `RandomUtil`），**不要用 Spring 的 `BeanUtils`**（其 `copyProperties` 返回 void）。
- 讲日志时区分「真错误」与「新 JDK 无害提示」，别把用户带偏。

# 黑马点评（hm-dianping）学习项目 · 会话交接文档

> 用途：把本项目当前的学习进度、已做的代码/配置改动、踩过的坑和待办交接给下一个 agent（或用于自己复习）。
> 工作区：`E:\javaweb_code\hm-dianping`（后端工程；前端为黑马点评官方前端，独立部署）
> 文档状态：基于对工作区的实际读取生成（不是凭记忆），如与代码不一致，以代码为准。

---

## 1. 项目与技术栈

| 项 | 值 |
|---|---|
| 项目 | 黑马点评（hmdp），Spring Boot 单体后端 |
| Spring Boot | 2.3.12.RELEASE（Spring 5.2.15） |
| Java 语言级别 | 1.8（`<java.version>1.8</java.version>`） |
| ORM | MyBatis-Plus 3.4.3（`BaseMapper` / `IService` / 链式 `.query()`，分页插件已在 `MybatisConfig` 注册） |
| 工具类库 | Hutool 5.7.17（**本课程统一使用 Hutool 的 `BeanUtil`，不是 Spring 的 `BeanUtils`**） |
| 数据库 | MySQL 8（库名 `hmdp`，账号 root/1234，建表脚本 `src/main/resources/db/hmdp.sql`） |
| 驱动 | mysql-connector-java **5.1.47**（老驱动，见第 5 节配置改动） |
| Redis | Spring Data Redis + Lettuce（连接池 commons-pool2） |
| 构建 | Maven 3.9.4 |
| 启动类 | `com.hmdp.HmDianPingApplication`（`@SpringBootApplication` + `@MapperScan("com.hmdp.mapper")`） |
| 后端端口 | 8081 |
| 前端 | 官方前端，nginx 部署在 **8080**，`/api` 反向代理到 8081 |

---

## 2. 运行环境（实测）

- OS：Windows
- JDK：**25**（`E:\java\jdk25`）——注意，项目用 JDK 25 运行 Boot 2.3.12 属于"新 JDK 跑老框架"，见第 7 节
- Maven：`E:\java\apache-maven-3.9.4`，本地仓库 `E:\java\apache-maven-3.9.4\maven_repo`
- IDE：IntelliJ IDEA 2025.3.2

**启动顺序（必须）**：

1. **Redis（6379）** —— 登录功能已改为 Redis 版，Redis 不启动会直接抛连接异常；
2. **后端**（IDEA 运行 `HmDianPingApplication`，8081）；
3. **nginx 前端**（8080）→ 浏览器访问 `http://localhost:8080`。

> 撰写本文档时的实测端口状态：**只有 MySQL(3306) 在监听**，6379 / 8080 / 8081 均未启动。

---

## 3. 已完成的工作（按文件）

### 3.1 `pom.xml`

- **新增 `<lombok.version>1.18.42</lombok.version>`（重要，勿删）**
  原因：Boot 2.3.12 默认管理 Lombok 1.18.12，该版本在 JDK 17+ 上编译必崩（`java.lang.ExceptionInInitializerError` / `com.sun.tools.javac.code.TypeTag :: UNKNOWN`）。1.18.40+ 才支持 JDK 25。
- `commons-pool2` 依赖保留（曾删除 → 又加回，最终保留，使 Lettuce 走连接池模式）。

### 3.2 `src/main/resources/application.yaml`

- `datasource.url` 追加了 `&allowPublicKeyRetrieval=true`
  原因：MySQL 8 默认认证插件 `caching_sha2_password` + 驱动 5.1.47，报 `Public Key Retrieval is not allowed`。
- Redis 下保留连接池配置：`max-active: 10` / `max-idle: 10` / `min-idle: 1` / `time-between-eviction-runs: 10s`。
- `logging.level.com.hmdp: debug`（用于查看 MyBatis-Plus 生成的 SQL）。

### 3.3 登录功能：已从 Session 版改造为 **Redis 版**（课程"短信登录 → 登录优化"）

**`service/impl/UserServiceImpl.java`**

- `sendCode`：校验手机号 → 生成 6 位验证码 → 存 Redis `login:code:{phone}`（TTL `LOGIN_CODE_TTL` = 2 分钟）→ 日志打印验证码（**验证码要从 IDEA 控制台日志抄**）。
- `login`：手机号校验 → **从 Redis 取验证码**比对 → 按手机号查用户（无则 `createUserWithPhone` 自动注册）→ 生成 token → 存 Redis → 返回 token：
  ```java
  String token = UUID.randomUUID().toString(true);   // Hutool UUID，去横线
  Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
          CopyOptions.create()
                  .setIgnoreNullValue(true)
                  .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
  String tokenKey = LOGIN_USER_KEY + token;          // login:token:{token}
  redisTemplate.opsForHash().putAll(tokenKey, userMap);
  redisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
  return Result.ok(token);                            // 前端拿到 token
  ```
  要点：`StringRedisTemplate` 要求 key/value 都是 String，`UserDTO.id` 是 `Long`，所以必须用 `fieldValueEditor` 把值 `toString()`，否则类型转换异常。

**`utils/RefreshTokenInterceptor.java`（新建，拦截器一，`order(0)`，拦全部路径）**

- 取请求头 `authorization` → 空则放行；
- `opsForHash().entries(login:token:{token})` → 空则放行；
- `BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false)`（自动把 String 的 id 转 Long）→ `UserHolder.saveUser(userDTO)`；
- `expire(..., LOGIN_USER_TTL, TimeUnit.MINUTES)` **刷新有效期（滑动过期）**；
- `afterCompletion` 里 `UserHolder.removeUser()` 清理 ThreadLocal。

**`utils/LoginInterceptor.java`（改造，拦截器二，`order(1)`，只负责"是否登录"）**

```java
if (UserHolder.getUser() == null) { response.setStatus(401); return false; }
return true;
```

**`config/MvcConfig.java`**

```java
registry.addInterceptor(new LoginInterceptor())
        .excludePathPatterns("/shop/**", "/voucher/**", "/upload/**",
                             "/blog/hot", "/user/login", "/user/code", "/shop-type/**")
        .order(1);
registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
```
要点：`RefreshTokenInterceptor` **不加白名单**（默认 `/**`，需要对所有请求生效以填充 `UserHolder`）；`order` 值小的先执行，所以刷新 token 的必须在前。

**`utils/UserHolder.java`**：`ThreadLocal<UserDTO>`，`saveUser` / `getUser` / `removeUser`。

### 3.4 已修复过的 bug（**防止重新踩**）

| # | 现象 | 根因 | 现状 |
|---|---|---|---|
| 1 | 登录后点右下角"我的"就"登录失效" | `LoginInterceptor.preHandle` 在"已登录"分支写成了 `return false`（拦截 + 响应为空，Controller 没执行、`afterCompletion` 也不回调） | 已改 `return true` |
| 2 | 发验证码接口返回 401，根本无法登录 | `MvcConfig` 白名单里 `/user/code` 被误写成重复的 `/user/login` | 已修正为 `/user/code` |
| 3 | 编译报 `java: 不兼容的类型: void无法转换为com.hmdp.dto.UserDTO` | 用了 Spring 的 `org.springframework.beans.BeanUtils.copyProperties(user, UserDTO.class)`——Spring 的该方法返回 **void**（任何版本都没有返回实例的重载） | 已改用 Hutool `BeanUtil.copyProperties(user, UserDTO.class)` |
| 4 | 编译报 `ExceptionInInitializerError` / `TypeTag :: UNKNOWN` | Lombok 1.18.12 与 JDK 25 不兼容 | 已在 pom 覆盖 `lombok.version=1.18.42` |
| 5 | 访问前端报 `Public Key Retrieval is not allowed` | MySQL 8 认证插件 + 老驱动 | 已在 URL 加 `allowPublicKeyRetrieval=true` |

---

## 4. 待办 / 已知风险（下一个 agent 优先看）

1. **登录链路尚未做端到端验证**（当前 Redis / 后端 / 前端都没启动，且本 agent 环境无法执行构建命令）。建议验证：
   起 Redis → 起后端 → 起 nginx → 浏览器登录 → 点"我的"能返回用户信息 → 清掉 Redis 里的 `login:token:*` 后应回到 401。
2. **`LoginInterceptor` 里有残留的无用 import**（`BeanUtil`、`StrUtil`、`StringRedisTemplate`、`HttpSession`、`Map`、`TimeUnit`、`LOGIN_USER_KEY`、`User`）——改造后已不需要，建议清理（不影响编译，仅整洁）。
3. **`LOGIN_USER_TTL = 36000L` 配 `TimeUnit.MINUTES` = 25 天**。课程原版是 `30L`（= 30 分钟滑动窗口）。想跟课程一致就改成 `30L`；想保留 36000 而语义为 10 小时则应改用 `TimeUnit.SECONDS`。
4. **`HttpSession session` 参数已无用**：`IUserService`、`UserServiceImpl`、`UserController` 三处都还带着，课程后续会去掉；若要去掉需三处同步修改。
5. 学习进度：**短信登录章节**已完成（Session 版 → Redis 版）；后续章节（商户查询缓存/缓存穿透雪崩击穿、优惠券秒杀、分布式锁、消息队列、达人探店、UV 统计、用户签到、GEO）尚未开始。
6. 项目**不是 git 仓库**（`git status` 报 `not a git repository`），所有改动没有版本历史，改动前请自行备份或用文件读取确认现状。

---

## 5. 关键约定速查

| 项 | 值 |
|---|---|
| 验证码 key | `login:code:{phone}`，TTL 2 分钟（`LOGIN_CODE_TTL`） |
| 登录 token key | `login:token:{token}`，类型 Hash，TTL `LOGIN_USER_TTL` |
| Hash 字段 | `id` / `nickName` / `icon`（全部以 String 存储） |
| token 传递方式 | 请求头 `authorization: {token}`（由前端自动携带，见 `RefreshTokenInterceptor`） |
| 白名单（不校验登录） | `/shop/**`、`/voucher/**`、`/upload/**`、`/blog/hot`、`/user/code`、`/user/login`、`/shop-type/**` |
| 当前登录用户 | `UserHolder.getUser()`（ThreadLocal，由拦截器填充） |
| 缓存/锁等 key 前缀 | 见 `utils/RedisConstants.java`（`cache:shop:`、`lock:shop:`、`seckill:stock:`、`blog:liked:`、`feed:`、`shop:geo:`、`sign:`） |

---

## 6. 常用验证手段

```bash
# 端口是否就绪（Redis 6379 / 后端 8081 / nginx 8080 / MySQL 3306）
netstat -ano | grep LISTENING | grep -E ":6379|:8080|:8081|:3306"

# 发验证码：应返回 200（若是 401 说明被拦截器误拦 / 白名单有问题）
curl -i -X POST "http://localhost:8081/user/code?phone=13800000000"

# 未登录访问受限接口：应 401
curl -i "http://localhost:8081/user/me"

# 带 token 访问：应 200 且返回用户 JSON
curl -i -H "authorization: {token}" "http://localhost:8081/user/me"
```

Redis 侧（`redis-cli`）：

```bash
keys login:token:*                      # 登录会话
hgetall login:token:{token}             # 用户字段（应全为字符串）
ttl login:token:{token}                 # 剩余秒数；再访问一次接口应被刷回满值（滑动过期）
keys login:code:*                       # 未过期的验证码
```

浏览器 F12：登录后看请求头 `authorization`、看 `Set-Cookie`（Session 版残留的 JSESSIONID 已不再使用）。

---

## 7. 环境坑清单（JDK 25 特有）

1. **Lombok 版本**（已在 pom 解决，见 3.1）——改动 pom 后需在 IDEA 里 Reload Maven 重新下载依赖。
2. **Tomcat native access 警告**：`WARNING: A restricted method in java.lang.System has been called ... java.lang.System::load ... org.apache.tomcat.jni.Library`（JDK 24+ 限制 FFI）。无害，Tomcat 回退到纯 Java NIO。想消除：VM options 加 `--enable-native-access=ALL-UNNAMED`。
3. **Netty Unsafe 警告**：`WARNING: A terminally deprecated method in sun.misc.Unsafe has been called ... io.netty.util.internal.PlatformDependent0$3`（JDK 23+ 起）。无害（netty-common 4.1.65 较老）。
4. `Multiple Spring Data modules found, entering strict repository configuration mode!` / `Found 0 Redis repository interfaces` —— 都是 **INFO 不是错误**。
5. **建议**：这个老框架（Boot 2.3 / Spring 5.2 / Netty 4.1.65）官方只测到 JDK 13 左右；长期学习更省心的做法是把运行 JDK 换成 **8 / 11 / 17**（lombok 的版本覆盖可以保留，不影响）。

---

## 8. 课程进度

- ✅ 短信登录：基于 Session 实现 → 改造为基于 Redis（token）实现
- ✅ 登录拦截器：`RefreshTokenInterceptor`（刷新 token + 填充 `UserHolder`）+ `LoginInterceptor`（登录校验）
- ⏭️ 下一步方向（课程顺序）：商户查询缓存（缓存穿透 / 雪崩 / 击穿）→ 优惠券秒杀 → 分布式锁 → 消息队列 → 达人探店 → UV 统计 → 用户签到 → GEO

---

## 9. 给下一个 agent 的协作建议

1. **用户偏好**：以"讲清原理 + 给代码骨架"为主，用户明确表达过 **"我自己写，你先别动"**——改代码前先确认范围，不要擅自扩大改动。
2. **代码风格对齐课程**：统一用 Hutool（`BeanUtil`、`UUID`、`StrUtil`、`RandomUtil`），不要用 Spring 的 `BeanUtils`（踩过坑，见 3.4 #3）。
3. **交流语言**：中文。
4. **本 agent 环境限制（实测）**：`mvn` / `java` / `gradle` 等构建命令会被工作流拦截而无法执行；可用的是 `node`、`python`、`git`、`docker`、`netstat`、`curl`。因此**编译与启动验证需要用户在 IDEA 里完成**，agent 侧只能做静态核验 + 接口/端口层面的探测。
5. **文件编辑流程**：先 `read_file` 读取目标文件当前内容，再进行 `edit_file`（同一文件的多处修改要分次进行，同批多次编辑会因"内容已变更"失败）。
6. 与用户交流时，**日志里的警告要区分"真错误"和"新 JDK 的无害提示"**（见第 7 节），避免把用户带偏。

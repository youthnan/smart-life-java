# hm-dianping 项目结构与函数说明

> 目的：帮助后续快速定位代码、理解职责边界、低风险修改。  
> 范围：`src/main/java/com/hmdp` 与 `src/main/resources` 关键文件。

## 1. 项目概览

- 项目类型：Spring Boot 单体后端（Maven）。
- 技术栈：Spring Web、MyBatis-Plus、MySQL、Redis、Redisson、AOP。
- 分层结构：`controller -> service -> mapper -> db`，并辅以 `dto/entity/utils/config`。
- 启动入口：`src/main/java/com/hmdp/HmDianPingApplication.java`。

---

## 2. 当前系统功能一览

从 **HTTP 接口与业务能力** 角度归纳本项目当前已实现（或可感知）的功能；类级与函数级说明见第 5 节。

| 模块 | 能力说明 | 主要入口 |
|------|----------|----------|
| 用户与登录 | 手机号格式校验、验证码生成并写入 Redis、验证码登录、首次登录自动注册、token 写入 Redis 与刷新、ThreadLocal 持有当前用户、登出占位、查询当前用户与指定用户简要信息 | `UserController`、`UserServiceImpl`、`RefreshTokenInterceptor`、`LoginInterceptor` |
| 店铺 | 店铺新增/更新/详情；详情走缓存（穿透空值、互斥重建、逻辑过期异步重建）；按类型分页；可选经纬度的 GEO 距离排序分页；按名称关键词分页；更新后删缓存 | `ShopController`、`ShopServiceImpl`、`CacheClient` |
| 店铺类型 | 全部分类列表（带缓存） | `ShopTypeController`、`ShopTypeServiceImpl` |
| 探店笔记 | 发布笔记并推送到粉丝收件箱（Redis）；热门/我的/按用户分页；详情；点赞/取消点赞（Redis Set）；点赞用户列表；关注 Feed 滚动分页 | `BlogController`、`BlogServiceImpl` |
| 社交关注 | 关注/取关、是否已关注、共同关注（Redis 集合） | `FollowController`、`FollowServiceImpl` |
| 优惠券 | 新增普通券、新增秒杀券（秒杀库存预热到 Redis）、按店铺查询券列表（含秒杀扩展信息） | `VoucherController`、`VoucherServiceImpl` |
| 秒杀下单 | Lua 在 Redis 内原子完成库存与一人一单校验、扣库存、订单消息入 Stream；应用内异步消费 Stream、pending 补偿、用户维度分布式锁、`@Transactional` 落库 | `VoucherOrderController`、`VoucherOrderServiceImpl`、`seckill.lua` |
| 图片上传 | 上传图片到本地目录、按文件名删除 | `UploadController` |
| 笔记评论 | **仅有控制器骨架**，`/blog-comments` 下尚无具体 REST 方法（实体与 Mapper 预留扩展） | `BlogCommentsController` |
| 横切与基础设施 | 全局异常统一 `Result`；MyBatis-Plus 分页；Redisson；简单 Redis 锁 + Lua 安全释放；雪花式 ID 等工具 | `WebExceptionAdvice`、`MvcConfig`、`MybatisConfig`、`RedissonConfig` 等 |

**自动化测试（当前仓库）**：`CacheClientTest`、`VoucherOrderServiceImplTest` 为单元测试；`HmDianPingApplicationTests` 默认 `@Disabled`，需本机 Redis/MySQL 与有效环境变量时再启用做集成验证。

---

## 3. 目录结构与职责

### `src/main/java/com/hmdp`

- `config`：框架配置（拦截器、MyBatis、Redisson、异常处理）
- `controller`：接口路由层（参数接收、调用 service、统一返回）
- `service`：业务接口定义
- `service/impl`：业务实现（缓存策略、秒杀流程、关注推送等核心逻辑）
- `mapper`：数据访问层（MyBatis-Plus BaseMapper + 少量自定义 SQL）
- `entity`：数据库实体映射模型
- `dto`：接口入参与返回对象
- `utils`：通用基础能力（锁、缓存工具、拦截器、ID 生成器、正则等）

### `src/main/resources`

- `application.yaml`：端口、激活的 profile、Jackson、MyBatis-Plus 别名、根日志级别等公共项。
- `application-dev.yaml` / `application-test.yaml` / `application-prod.yaml`：各环境下的数据源、Redis、日志级别（敏感信息走环境变量）。
- `db/hmdp.sql`：数据库初始化脚本
- `mapper/VoucherMapper.xml`：自定义 SQL 映射
- `seckill.lua`：秒杀原子脚本（库存/一人一单/消息写入）
- `unlock.lua`：分布式锁安全释放脚本

---

## 4. 请求与数据主链路

1. 请求进入 `controller`。
2. `controller` 调用 `service` 接口。
3. `service/impl` 执行业务规则（缓存、鉴权上下文、事务、分布式锁）。
4. 通过 `mapper` 访问 MySQL，或通过 Redis/Lua 访问缓存与消息流。
5. 返回 `dto.Result` 给前端。

---

## 5. 类与函数详细说明（按包）

## 5.1 根包

### `HmDianPingApplication`

- `main(String[] args)`：Spring Boot 启动入口。

---

## 5.2 `config` 包

### `MvcConfig`
- `addInterceptors(InterceptorRegistry registry)`：注册并编排拦截器顺序（先 token 刷新，再登录校验）。

### `MybatisConfig`
- `mybatisPlusInterceptor()`：配置 MyBatis-Plus 分页拦截器。

### `RedissonConfig`
- `redissonClient()`：创建 Redisson 客户端实例，供分布式锁与高级 Redis 操作使用。

### `WebExceptionAdvice`
- `handleBadRequestException(Exception e)`：统一处理参数/校验类异常（如 `IllegalArgumentException`、`ConstraintViolationException`、`MethodArgumentTypeMismatchException`、`BindException`），返回「请求参数错误」。
- `handleRuntimeException(RuntimeException e)`：捕获未单独声明的 `RuntimeException`，记录错误日志并返回「服务器异常」。
- `handleException(Exception e)`：兜底捕获其余受检异常与未分类异常，记录日志并返回「服务器异常」。

---

## 5.3 `controller` 包

### `BlogCommentsController`
- 当前为控制器骨架，暂无显式接口方法实现。

### `BlogController`
- `saveBlog(Blog blog)`：发布探店笔记并推送到粉丝收件箱。
- `likeBlog(Long id)`：点赞/取消点赞笔记。
- `queryMyBlog(Integer current)`：分页查询当前登录用户的笔记。
- `queryHotBlog(Integer current)`：分页查询热门笔记。
- `queryBlogById(Long id)`：按 ID 查询笔记详情。
- `queryBlogLikes(Long id)`：查询点赞用户列表。
- `queryBlogByUserId(Integer current, Long id)`：分页查询指定用户的笔记。
- `queryBlogOfFollow(Long max, Integer offset)`：滚动分页读取关注流。

### `FollowController`
- `follow(Long followUserId, Boolean isFollow)`：关注或取关用户。
- `isFollow(Long followUserId)`：判断是否已关注目标用户。
- `followCommons(Long followUserId)`：查询共同关注用户。

### `ShopController`
- `queryShopById(Long id)`：查询店铺详情。
- `saveShop(Shop shop)`：新增店铺。
- `updateShop(Shop shop)`：更新店铺并触发缓存失效。
- `queryShopByType(Integer typeId, Integer current, Double x, Double y)`：按类型分页查询店铺，支持坐标检索。
- `queryShopByName(String name, Integer current)`：按关键字分页查询店铺。

### `ShopTypeController`
- `queryTypeList()`：查询店铺分类列表（缓存优先）。

### `UploadController`
- `uploadImage(MultipartFile image)`：上传图片并返回路径。
- `deleteBlogImg(String filename)`：删除图片文件。
- `createNewFileName(String originalFilename)`：生成按日期分层且唯一的新文件名。

### `UserController`
- `sendCode(String phone, HttpSession session)`：发送登录验证码。
- `login(LoginFormDTO loginForm, HttpSession session)`：验证码登录并发放 token。
- `logout()`：登出占位接口（当前逻辑较轻）。
- `me()`：获取当前登录用户信息。
- `info(Long userId)`：查询用户扩展信息。
- `queryUserById(Long userId)`：查询并返回用户简化信息。

### `VoucherController`
- `addVoucher(Voucher voucher)`：新增普通优惠券。
- `addSeckillVoucher(Voucher voucher)`：新增秒杀券。
- `queryVoucherOfShop(Long shopId)`：查询店铺优惠券。

### `VoucherOrderController`
- `seckillVoucher(Long voucherId)`：秒杀下单入口。

---

## 5.4 `service` 接口包

### `IBlogCommentsService`
- 继承通用 CRUD，无额外方法。

### `IBlogService`
- `queryBlogById(Long id)`：查笔记详情。
- `queryHotBlog(Integer current)`：查热门笔记分页。
- `likeBlog(Long id)`：点赞/取消。
- `queryBlogLikes(Long id)`：查点赞用户。
- `saveBlog(Blog blog)`：发布笔记并推送。
- `queryBlogOfFollow(Long max, Integer offset)`：查关注流。

### `IFollowService`
- `follow(Long followUserId, Boolean isFollow)`：关注/取关。
- `isFollow(Long followUserId)`：是否关注。
- `followCommons(Long id)`：共同关注。

### `ISeckillVoucherService`
- 继承通用 CRUD，无额外方法。

### `IShopService`
- `queryById(Long id)`：查店铺详情。
- `update(Shop shop)`：更新店铺并删缓存。
- `queryShopByType(Integer typeId, Integer current, Double x, Double y)`：类型+地理分页查询。

### `IShopTypeService`
- `queryTypeList()`：店铺类型列表查询。

### `IUserInfoService`
- 继承通用 CRUD，无额外方法。

### `IUserService`
- `sendCode(String phone, HttpSession session)`：发送验证码。
- `login(LoginFormDTO loginForm, HttpSession session)`：验证码登录。

### `IVoucherOrderService`
- `seckillVoucher(Long voucherId)`：秒杀下单流程入口。
- `createVoucherOrder(VoucherOrder voucherOrder)`：创建订单（事务方法）。

### `IVoucherService`
- `queryVoucherOfShop(Long shopId)`：查店铺优惠券。
- `addSeckillVoucher(Voucher voucher)`：新增秒杀券并同步库存到 Redis。

---

## 5.5 `service/impl` 实现包

### `BlogCommentsServiceImpl`
- 仅继承默认 CRUD，无显式业务方法。

### `BlogServiceImpl`
- `queryBlogById(Long id)`：查笔记并补充作者与点赞状态。
- `queryBlogUser(Blog blog)`：填充作者昵称/头像。
- `queryHotBlog(Integer current)`：热门笔记分页，逐条补齐展示信息。
- `likeBlog(Long id)`：更新点赞数并维护 Redis 点赞集合。
- `queryBlogLikes(Long id)`：按点赞顺序取用户列表。
- `saveBlog(Blog blog)`：保存笔记并写入粉丝收件箱。
- `queryBlogOfFollow(Long max, Integer offset)`：按时间戳滚动拉取关注流。
- `isBlogLiked(Blog blog)`：判断当前用户是否已点赞。

### `FollowServiceImpl`
- `isFollow(Long followUserId)`：查关注关系。
- `follow(Long followUserId, Boolean isFollow)`：关注/取关并更新 Redis 关注集合。
- `followCommons(Long id)`：求交集得到共同关注用户。

### `SeckillVoucherServiceImpl`
- 仅继承默认 CRUD，无显式业务方法。

### `ShopServiceImpl`
- `queryById(Long id)`：店铺查询主入口（使用缓存策略）。
- `queryWithLogicalExpire(Long id)`：逻辑过期方案，过期后异步重建。
- `queryWithMutex(Long id)`：互斥锁重建缓存方案。
- `queryWithPassThrough(Long id)`：缓存穿透兜底方案（空值缓存）。
- `update(Shop shop)`：更新数据库并删除缓存。
- `trylock(String key)`：尝试获取缓存重建锁。
- `unlock(String key)`：释放缓存重建锁。
- `saveshop2Redis(Long id, Long expireSeconds)`：缓存预热/手动重建逻辑过期数据。
- `queryShopByType(Integer typeId, Integer current, Double x, Double y)`：按类型 + GEO 距离分页查店铺。

### `ShopTypeServiceImpl`
- `queryTypeList()`：查分类列表并写入缓存。

### `UserInfoServiceImpl`
- 仅继承默认 CRUD，无显式业务方法。

### `UserServiceImpl`
- `sendCode(String phone, HttpSession session)`：手机号校验、生成验证码并写 Redis。
- `login(LoginFormDTO loginForm, HttpSession session)`：验证码登录、首次自动注册、token 缓存。
- `createUserWithPhone(String phone)`：自动创建默认昵称用户。

### `VoucherOrderServiceImpl`
- `init()`：初始化异步订单处理器与 Redis Stream 消费组。
- `VoucherOrderHandler.run()`：循环消费订单消息。
- `handlePendingList()`：处理 pending-list 中未确认消息。
- `handleVoucherOrder(VoucherOrder voucherOrder)`：加用户锁并执行创建订单逻辑。
- `seckillVoucher(Long voucherId)`：执行 Lua 脚本完成资格判断并投递消息。
- `createVoucherOrder(VoucherOrder voucherOrder)`：校验一人一单、扣库存、保存订单（事务）。

### `VoucherServiceImpl`
- `queryVoucherOfShop(Long shopId)`：查店铺优惠券（含秒杀扩展字段）。
- `addSeckillVoucher(Voucher voucher)`：新增券、新增秒杀信息、初始化 Redis 库存。

---

## 5.6 `mapper` 包

> 多数 Mapper 继承 `BaseMapper<T>`，因此基础 CRUD 由 MyBatis-Plus 提供。

### 无额外方法（仅 BaseMapper）
- `BlogCommentsMapper`
- `BlogMapper`
- `FollowMapper`
- `SeckillVoucherMapper`
- `ShopMapper`
- `ShopTypeMapper`
- `UserInfoMapper`
- `VoucherOrderMapper`

### 有显式方法
- `UserMapper`
  - `sendCode(String phone, HttpSession session)`：接口中声明的方法；项目内无对应 XML/SQL 实现，实际发码逻辑在 `UserServiceImpl`，此处可视为遗留声明，后续可删除或补齐映射。
- `VoucherMapper`
  - `queryVoucherOfShop(Long shopId)`：按店铺查询券信息（配合 XML 联表查询）。

---

## 5.7 `dto` 包

### `LoginFormDTO`
- 登录表单对象（手机号、验证码、密码等字段载体），无显式业务方法。

### `Result`
- `ok()`：返回成功（无 data）。
- `ok(Object data)`：返回成功（含 data）。
- `ok(List<?> data, Long total)`：返回成功（含分页总数）。
- `fail(String errorMsg)`：返回失败结果。

### `ScrollResult`
- 关注流滚动分页结果对象，无显式业务方法。

### `UserDTO`
- 登录态用户简化对象，无显式业务方法。

---

## 5.8 `entity` 包

- `Blog`
- `BlogComments`
- `Follow`
- `SeckillVoucher`
- `Shop`
- `ShopType`
- `User`
- `UserInfo`
- `Voucher`
- `VoucherOrder`

说明：均为数据模型，主要由字段、注解、Lombok 组成，通常无显式业务函数。

---

## 5.9 `utils` 包

### `CacheClient`
- `CacheClient(StringRedisTemplate stringRedisTemplate)`：构造注入 Redis 客户端。
- `set(String key, Object value, Long time, TimeUnit unit)`：写普通缓存。
- `setWithLogicalExpire(...)`：写逻辑过期缓存。
- `queryWithPassThrough(...)`：缓存穿透防护查询模板。
- `queryWithLogicalExpire(...)`：逻辑过期查询模板，支持后台重建。
- `trylock(String key)`：尝试加锁。
- `unlock(String key)`：释放锁。

### `ILock`
- `tryLock(Long timeoutSec)`：尝试在超时时间内获取锁。
- `unlock()`：释放锁。

### `LoginInterceptor`
- `preHandle(...)`：登录态校验，不通过则返回 401。

### `PasswordEncoder`
- `encode(String password)`：加盐并编码密码。
- `encode(String password, String salt)`：按指定盐计算摘要。
- `matches(String encodedPassword, String rawPassword)`：验证密码是否匹配。

### `RedisConstants`
- Redis key 前缀、TTL 等常量定义类，无显式方法。

### `RedisData`
- 逻辑过期缓存包装对象（`expireTime + data`），无显式方法。

### `RedisWorker`
- `nextID(String keyPreFix)`：全局唯一 ID 生成（时间戳 + 自增序列）。

### `RefreshTokenInterceptor`
- `RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate)`：构造器注入。
- `preHandle(...)`：解析 token -> 加载用户 -> 刷新 TTL -> 放入 `UserHolder`。
- `afterCompletion(...)`：请求结束清理线程变量，防内存泄漏。

### `RegexPatterns`
- 正则模式常量（手机号、邮箱、验证码等），无显式方法。

### `RegexUtils`
- `isPhoneInvalid(String phone)`：手机号格式是否非法。
- `isEmailInvalid(String email)`：邮箱格式是否非法。
- `isCodeInvalid(String code)`：验证码格式是否非法。
- `mismatch(String str, String regex)`：通用正则校验。

### `SimpleRedisLock`
- `SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name)`：构造锁实例。
- `tryLock(Long timeoutSec)`：`SETNX + EX` 获取分布式锁。
- `unlock()`：调用 Lua 脚本安全释放锁（校验锁归属）。

### `SystemConstants`
- 系统常量定义（分页大小、上传目录、默认昵称前缀等），无显式方法。

### `UserHolder`
- `saveUser(UserDTO user)`：保存当前线程用户。
- `getUser()`：获取当前线程用户。
- `removeUser()`：清理当前线程用户。

---

## 6. 核心资源文件说明

### `application.yaml`
- 配置服务端口、`spring.profiles.active`、应用名、Jackson 非空序列化、MyBatis-Plus 别名包、根日志级别等。
- **数据源与 Redis** 已拆到 `application-{profile}.yaml`（如 `dev`/`test`/`prod`），敏感项通过环境变量注入，勿在仓库中写死生产密码。

### `db/hmdp.sql`
- 初始化数据库结构与演示数据。

### `mapper/VoucherMapper.xml`
- 自定义 SQL（主要用于优惠券相关联表查询）。

### `seckill.lua`
- 在 Redis 内原子完成：库存校验、重复下单校验、库存扣减、订单消息入流。

### `unlock.lua`
- 原子校验锁持有者并删除锁，避免误删他人锁。

---

## 7. 后续修改建议（实操向）

- 先改接口行为：从 `controller -> service 接口 -> service 实现` 顺序跟踪。
- 涉及缓存逻辑时，优先检查 `CacheClient`、`ShopServiceImpl`、`RedisConstants`。
- 涉及登录态问题，优先检查 `RefreshTokenInterceptor`、`LoginInterceptor`、`UserHolder`。
- 涉及秒杀链路，重点看 `VoucherOrderServiceImpl` + `seckill.lua` + `unlock.lua`。
- 改 SQL 前，先确认是否走 `BaseMapper` 还是 XML（`VoucherMapper.xml`）。
- 涉及并发与一致性，确认是否已在事务内、是否加锁、是否有幂等校验（一人一单）。

---

## 8. 维护记录

- 文档创建日期：2026-04-17
- 2026-04-18：新增「当前系统功能一览」表；校正 `WebExceptionAdvice`、`application.yaml` 说明；章节编号顺延。
- 用途：作为项目结构总览与函数定位手册，可按模块持续增量更新。

---

## 9. 环境配置使用说明

- 默认环境：`dev`（由 `application.yaml` 中 `spring.profiles.active=dev` 指定）。
- 开发环境配置：`src/main/resources/application-dev.yaml`。
- 测试环境配置：`src/main/resources/application-test.yaml`。
- 生产环境配置：`src/main/resources/application-prod.yaml`。
- 生产环境必须通过环境变量注入配置，不要写死密码：
  - `SPRING_DATASOURCE_URL`
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`
  - `SPRING_REDIS_HOST`
  - `SPRING_REDIS_PORT`
  - `SPRING_REDIS_PASSWORD`
- 启动示例：
  - `SPRING_PROFILES_ACTIVE=prod java -jar hm-dianping.jar`

---

## 10. 最小回归清单（每次改动后）

- 启动应用并确认无配置报错（尤其是数据库和 Redis 连接）。
- 登录链路：发送验证码 -> 登录 -> 访问 `me` 接口。
- 店铺链路：店铺详情查询、按类型分页查询（含坐标时的 GEO 查询）。
- 秒杀链路：正常下单、重复下单拦截、库存不足拦截。
- 缓存链路：店铺缓存命中、缓存重建日志正常、空值缓存生效。

# ms-demo 学习速记（docs/learn-notes.md）

> 从 README 拆分出的深度学习内容：实验步骤、原理、FAQ、面试速记。
> README 只做"是什么 + 怎么跑 + 导航"，深入细节都在这。

---

## 一、服务注册与发现（最小闭环）

- **order-service**（8088/8089）：服务提供者，注册到 Nacos，`GET /order/{id}` 返回带 `instance` 端口的 JSON。
- **user-service**（8082）：消费者，用**服务名** `http://ms-order-service/order/1` 调用，不写 IP/端口——这就是服务发现的意义。
- `instance` 字段标明本次由哪个实例处理，是"负载均衡真的分流了"的证据。

## 二、负载均衡实验

### 默认轮询（Round Robin）
未配置时 Spring Cloud LoadBalancer 默认轮询，多打几次 `curl localhost:8082/user/1`，`instance` 在 8088/8089 间交替。

### 权重分流（本项目重点）
默认 `8088: weight=2.0`、`8089: weight=1.0`，理论约 2:1。

**方式一（控制台）**：Nacos 控制台 → 服务管理 → order-service → 详情 → 编辑实例权重。

**方式二（OpenAPI）**：
```bat
REM 先查实例拿真实注册 IP
curl "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=order-service"
REM 设权重（ip 填上面查到的）
curl -X PUT "http://127.0.0.1:8848/nacos/v1/ns/instance?serviceName=order-service&ip=<注册IP>&port=8088&weight=2"
```

> ⚠️ 权重改动约 **11~13 秒后**才生效：Nacos 实例列表接口有 `cacheMillis=10000`，消费者读旧权重，等一个周期再看。

### 自定义加权随机负载均衡器（为什么默认轮询不认权重）
Spring Cloud 2020+ 移除 Ribbon，官方 LoadBalancer 默认**轮询、不认 Nacos 权重**。本项目在 user-service 实现 `WeightedRandomLoadBalancer`：
- **必须 `implements ReactorServiceInstanceLoadBalancer`**（只实现 `ReactorLoadBalancer` 会被框架当"没实现"，取到 null）
- `DiscoveryClient.getInstances(serviceId)` 拿实例列表
- 从 `metadata["nacos.weight"]` 读 Nacos 权重
- 用**累计区间加权随机**：权重 2:1 → 选中概率 2:1

接线：
```java
// UserServiceApplication.java
@LoadBalancerClient(name = "order-service", configuration = LoadBalancerConfig.class)

// LoadBalancerConfig.java
@Bean
ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(DiscoveryClient dc) {
    return new WeightedRandomLoadBalancer(dc, "order-service");
}
```
配套 `spring.cloud.loadbalancer.cache.enabled: false`，关默认缓存（避免启动初期缓存"空实例列表"→ `No instances available`）。

## 三、关键代码三句话

1. **服务发现**：`RestTemplateConfig` 给 `RestTemplate` 打 `@LoadBalanced`，它才认识服务名。
2. **按服务名调用**：`UserController` 里 `String url = "http://ms-order-service/order/" + id;`。
3. **负载均衡挑实例**：自定义均衡器从 Nacos 拿实例、按权重挑一个，再直连。

## 四、FAQ（注册发现）

**Q：`No instances available for order-service`？**
确认 Nacos 已启动且 order-service 实例数为 2；别删 `loadbalancer.cache.enabled: false`；自定义均衡器是 `implements ReactorServiceInstanceLoadBalancer` 而非 `ReactorLoadBalancer`。

**Q：权重改了比例不立刻变 2:1？**
Nacos `cacheMillis=10000`，约 11~13 秒后消费者才读到新权重。

**Q：为什么有时连续两次打同一实例？**
加权**随机**保"占比"不保"节奏"，允许连号。要严格节拍用加权轮询（平滑加权轮询生产更常用）。

## 五、网关功能线（gateway 模块，端口 9090）

### 已实现功能（沿 git 提交链）

| 功能 | 说明 |
| --- | --- |
| 路由 + `lb://` | `/user/**`→user-service、`/order/**`→order-service，网关服务端负载均衡 |
| 统一文档门户 | springdoc 聚合，网关 `/v3/api-docs/{service}` 聚合各服务接口 |
| 门户首页 | `index.html`，导航各演示入口 |
| 请求日志 | `RequestLogGlobalFilter`，记上游 Pre / 下游 Post 的 downstream、status、cost |
| JWT 认证 | `/auth/login` 签发 HMAC-SHA256 token，网关验签 + 查过期，非法 401 |
| 白名单 | 首页 / swagger / webjars / `/auth/**` 免鉴权，业务接口校验 |
| RBAC 角色授权 | 路径→角色规则（`/order/delete`→admin），角色不足 403 |
| 刷新令牌 | access(2h)+refresh(7天) 双 token，`/auth/refresh` 换新，网关拒 refresh 当业务 token |
| CORS | 曾实测预检带 `Access-Control-Allow-Origin:*`，后按需移除（走 nginx 同源） |
| 限流 / 超时重试 / 熔断降级 | 见 learn-docs 07/08/09 教学台 |

### 演示账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| admin | 123456 | [admin]（可调删除类管理接口） |
| guest | 123456 | [guest]（登录成功但无 admin 权限 → 403） |

> 注：这是网关 JWT 鉴权演示账号；门户/demo 的登录密码是另一套（见前端登录）。

### 面试速记（关键机制）

- **JWT 三段式**：`Header.Payload.Signature`，Signature = 前两段用密钥 HMAC-SHA256 签名，防篡改、防伪造。
- **认证 vs 授权**：认证管"你是谁"（401 拦未登录/无效 token），授权管"你能干嘛"（403 拦角色不足）。登录成功 ≠ 有权限。
- **双 token 刷新**：access 短效随请求走（泄露损失小）、refresh 长效只走刷新接口（生产放 httpOnly Cookie 防 XSS）；过期 → refresh 换新 → 重放原请求。
- **网关一个关键坑**：网关自身暴露的 Controller **不走网关过滤器链**，需要权限的接口必须放下游服务、让请求走路由，网关在转发前统一校验。

### gateway FAQ

**Q：网关鉴权拦不住自己模块的 Controller？**
对——gateway 自己 `@RestController` 的接口不走过滤器链（那条链只处理匹配到路由、要转发下游的请求）。需要权限的接口应放下游服务。

**Q：Nacos 里 gateway 订阅者 count=0、order-service 只有"自订阅"一行正常吗？**
正常。实例注册时客户端自订阅自己；真正调用方走 `selectInstances`（拉取）不记入订阅者；WebFlux 网关注册时不建自我订阅。

**Q：请求日志里有些行 `downstream=?`？**
下游读取必须在 `chain.filter(exchange).then(...)` 回调（Post 段）做，因为 lb 选实例发生在过滤器链中途；被鉴权 401 短路的请求不转发，`downstream=? cost=0ms` 是短路的直接证据。

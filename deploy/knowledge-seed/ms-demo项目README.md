# ms-demo — 微服务学习教学工程（Nacos + Spring Cloud Gateway）

用「亲手搭 + 看证据」的方式学习微服务。**代码按架构组织，学习按章节组织**：本 README 只做"是什么 + 怎么跑 + 导航"，深入细节看 [docs/learn-notes.md](docs/learn-notes.md) 和各教学页。

- **order-service**（提供者）：注册到 Nacos，对外提供订单接口
- **user-service**（消费者）：用**服务名**调 order-service，演示服务发现 + 负载均衡
- **gateway**（网关）：统一入口，叠加路由 / JWT 鉴权 / 限流 / 熔断降级
- 额外演示：Nacos 实例权重作用于负载均衡分流（自定义加权随机 LoadBalancer）

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.3.5 |
| Spring Cloud / Alibaba | 2023.0.3 / 2023.0.3.2 |
| Nacos | 2.5.4（standalone） |
| Java | JDK 22 编译，字节码 target 17 |
| 构建 | Maven 多模块 |

## 目录导航

```
ms-demo/
├── ms-gateway/          # 网关(9090)：路由lb://、JWT鉴权、RBAC、限流、熔断降级
├── ms-user-service/     # 消费者(8082)：服务名调用 + 加权随机LoadBalancer
├── ms-order-service/    # 提供者(8088/8089)：订单接口，双实例做负载均衡
├── demo/      # 门户单体(8081)：天气/AI/吃什么/热点墙/访问量等生活工具
├── frontend/         # 门户前端
├── learn-docs/       # 教学页（章节导航，见下方"学习导航"）
├── docs/
│   ├── evidence/     # 实验证据 txt，按 ch01~ch05 章节归档
│   └── learn-notes.md# 学习速记：实验步骤/FAQ/面试速记（从README拆出）
├── deploy/           # 部署脚本
│   ├── local/        #   本地/沙箱执行（打包上传）
│   ├── server/       #   云机执行（启停微服务/单体）
│   └── conf/         #   密钥配置（deploy.env）
├── nginx/ ops-panel/ # 反向代理配置 / 运维面板
└── pom.xml           # 父POM聚合 order-service / user-service / gateway
```

## 快速启动

**1. 启动 Nacos（单机）**
```bat
cd nacos\bin && startup.cmd -m standalone
```

**2. 构建全部模块**
```bat
mvn clean package -DskipTests
```

**3. 启动服务**
- 本地 Windows：`os/01_local_windows11/start-all.cmd  restart`（Nacos→user→order→gateway，自动等端口就绪）
- 云机（101.43.45.217）：`sudo bash /root/tools/ms-demo/deploy/server/10-start-msdemo.sh  restart`

**4. 验证**
`curl http://localhost:8082/user/1` → 返回带 `instance:order-service:8088` 的 JSON，即服务发现 + 负载均衡已生效。

## 服务与端口

| 服务 | 端口 | 角色 |
| --- | --- | --- |
| Nacos | 8848 | 注册中心（默认账号 `nacos`，密码见仓库约定） |
| gateway | 9090 | 网关：统一入口、路由、鉴权、文档聚合 |
| user-service | 8082 | 消费者，接口 `/user/**` |
| order-service | 8088 / 8089 | 提供者，接口 `/order/**`（双实例做负载均衡） |
| demo | 8081 | 门户单体后端 |

## 页面入口

| 页面 | URL（网关 9090 下） | 演示 |
| --- | --- | --- |
| 门户首页 | `/` | 所有入口导航 |
| 登录/鉴权 | `/login.html` | JWT 签发、三段签名、带/不带 token、RBAC |
| 文档聚合 | `/swagger-ui.html` | 各服务接口 |

演示账号：`admin/123456`（admin 角色）、`guest/123456`（guest 角色，访问受限接口→403）。

## 学习导航

| 章 | 主题 | 入口 |
| --- | --- | --- |
| 01 | Nacos 注册与发现 | [architecture.html](learn-docs/architecture.html) + [证据](docs/evidence/ch01-nacos/) |
| 02 | 负载均衡（加权随机） | [速记](docs/learn-notes.md#二负载均衡实验) + [证据](docs/evidence/ch02-loadbalance/) |
| 03 | 网关路由与日志 | [gateway-nginx.html](learn-docs/gateway-nginx.html) |
| 04 | JWT 认证与 RBAC | [nacos-auth.html](learn-docs/nacos-auth.html) + [证据](docs/evidence/ch04-auth/) |
| 05 | 双 token 刷新 | [swagger-token.html](learn-docs/swagger-token.html) |
| 06 | CORS 开发/生产 | [10-CORS教学台](learn-docs/10-CORS开发与生产配置教学台.html) |
| 07 | 网关限流 | [07-限流教学台](learn-docs/07-网关限流教学台.html) |
| 08 | 超时与重试 | [08-超时重试教学台](learn-docs/08-网关超时与重试教学台.html) |
| 09 | 熔断与降级 | [09-熔断教学台](learn-docs/09-网关熔断与降级教学台.html) |
| 10 | 部署与反代 | [reverse-proxy-pitfalls.html](learn-docs/reverse-proxy-pitfalls.html) |
| 11 | 小程序 Bearer 认证 | [11-Bearer教学台](learn-docs/11-小程序Bearer认证教学台.html) |

每章配合四段式复盘：解决什么问题 → 最小实验 → 我踩过的坑 → 一句话原理（素材集中在 [docs/learn-notes.md](docs/learn-notes.md) 和 [docs/evidence/](docs/evidence/)）。

## 仓库约定

- 构建产物（`target/`、`*.jar`、`*.log`、`logs/`、`BOOT-INF/`）不入库，见 `.gitignore`
- **Nacos 密码**：服务端密码与 yml 默认值统一为 `fd`；yml 用占位符 `${NACOS_PASSWORD:fd}`，可环境变量覆盖（仓库私用，学习方便优先；若将来开源记得收口）
- 学习约定：实验证据直接落 `docs/evidence/chNN-主题/`，不堆根目录

# 云机多项目部署与管理设计

> **本仓库 deploy 脚本归档位置**：已按执行环境迁入 `os/` 多环境对照树——
> 云机脚本 `server/*.sh`、`conf/*` → `os/03_product_opencloudos/root/tools/ms-demo/deploy/`（镜像生产 `/root/tools/ms-demo/deploy/`）；
> 本地/开发机脚本 `local/*.sh` → `os/02_sandbox_linux/deploy/local/`。下文涉及的 `./deploy/local/` 均指上述归档位置。

> 目标：这台腾讯轻量云（2C2G，`101.43.45.217`）同时承载**两个自学项目**——
> **demo**（单机/单体学习项目 = 服务器监控台）和 **ms-demo**（微服务学习项目），
> 让两者**互不影响、各自独立启停、统一在 nginx 入口聚合**，方便自学 + 面试展示。

---

## 一、核心思路：四层隔离

把"代码 / 部署 / 运行时 / 入口"拆开，各管各的，谁也不碰谁：

```
┌───────────────────────────────────────────────────────┐
│  ① 代码仓库层   Gitee：demo(单机) + ms-demo(微服务)     │   ← 两个独立仓库
├───────────────────────────────────────────────────────┤
│  ② 部署脚本层   deploy/local/*、deploy/server/*       │   ← 按执行环境分文件夹+序号
├───────────────────────────────────────────────────────┤
│  ③ 运行时层     进程 + 端口 + 日志（完全隔离）           │   ← 关键：端口隔离
├───────────────────────────────────────────────────────┤
│  ④ 入口层       nginx(80) 统一对外，按路径分发           │   ← 唯一对外出口
└───────────────────────────────────────────────────────┘
```

---

## 二、端口规划（互不冲突是底线）

| 项目 | 组件 | 端口 | 说明 |
| --- | --- | --- | --- |
| **入口** | nginx | 80 / 8080 | 统一对外入口 |
| **demo（单机）** | 前端(静态) | 由 nginx 80 直接托管 | `/usr/share/nginx/html/` |
| | 后端 demo.jar | **8081** | nginx 将 `/api` `/ws` `/tool` 等转发给它 |
| **ms-demo（微服务）** | Nacos | **8848** | 注册中心（仅内网，不对外） |
| | user-service | **8082** | |
| | order-service | **8088** | |
| | gateway | **9090** | 对外唯一入口（安全组放行） |

> 每个服务端口唯一 → 进程、日志、启停天然隔离，互不干扰。

---

## 三、目录规划（云机）

```
/root/
├── tools/                    # 运行时依赖（与业务代码分开）
│   ├── nacos/                #   Nacos 安装目录
│   ├── apache-maven-3.9.9/   #   Maven
│   └── jdk...                #   JDK17（系统自带亦可）
├── tools/ms-demo/            # 微服务工程（从 Gitee clone）
│   ├── gateway/ user-service/ order-service/
│   ├── deploy/server/10-start-msdemo.sh   # 微服务一键管理（部署到云机后放这里）
│   └── logs/                 #   微服务运行日志（统一放这里）
├── demo/                     # demo 单体（独立目录，运行目录+日志+密钥绑定）
│   ├── demo-0.0.1-SNAPSHOT.jar
│   ├── start-demo.sh         #   demo 单体管理脚本（scp 自 deploy/server/20-start-demo.sh）
│   ├── deploy.env            #   密钥（天气/AI key）
│   └── app.log               #   demo 运行日志
└── scripts/                  # （可选）跨项目聚合脚本/状态面板
```

> **路径选择说明**：`tools/` 语义是"运行时依赖"（jdk/maven/nacos），ms-demo 放其下可接受；
> demo 是独立单体，放 `/root/demo`（不在 tools，也不散在 /root 根下），运行目录/日志/密钥
> 都绑在这里，已稳定运行。**不推荐**把源码直接散到 `/root` 根下（会和系统文件混在一起）。

---

## 四、脚本分工（deploy/ 按「执行环境」分子文件夹 + 数字序号标顺序）

> 现在**只维护 `ms-demo` 一个代码库**。`deploy/` 下按"在哪执行"分两个文件夹：
> - `local/`   —— 本地/沙箱执行（打包、上传）
> - `server/`  —— 云机执行（部署后直接在云机跑）
> 文件名带**数字序号前缀**表示发布流程中的先后顺序；密钥配置统一放 `conf/`。

| 脚本 | 归属 | 职责 | 执行环境 |
| --- | --- | --- | --- |
| `local/10-deploy-demo.sh` | demo 单体 | mvn 打包→scp /tmp→sudo 落位→重启→轮询 /calendar 到 200 | 本地/沙箱 |
| `local/20-deploy-frontend.sh` | frontend | Vue 构建→部署到云机 nginx html | 本地/沙箱 |
| `server/10-start-msdemo.sh` | ms-demo 微服务 | `status/start/stop/restart/update/log` 管理 Nacos+user+canary+order+gateway（含 demo 子命令） | 云机 `/root/tools/ms-demo/deploy/server/` |
| `server/20-start-demo.sh` | demo 单体 | `start/stop/restart/status/log` 管理 demo.jar(8081)，启动自动注入 key | 云机 `/root/demo/`（`sudo bash`） |
| `server/40-deploy-msjar.sh` | ms-demo 微服务 | 云机部署新 ms-* jar（停旧进程→放jar→覆盖脚本→启动） | 云机 |
| `server/50-verify-msdemo.sh` | ms-demo 微服务 | 云机验证（ps 看 ms-* jar + 网关 200/401） | 云机 |
| `conf/deploy.env` | 全站 | 密钥（天气/AI key，个人私有库允许提交；若公开仓库改回 gitignore 排除） | 参考/部署 |
| `conf/deploy-demo.env.example` | demo | 密钥模板 | 参考用 |

> **互不影响的关键**：两套脚本操作的是**不同端口、不同目录、不同 jar**，
> 停 demo 不会动微服务，反之亦然。重启任一项目都不影响另一个。

> 🐞 **2026-10-03 修掉的两个部署脚本坑**（都发生在 `local/10-deploy-demo.sh`）：
> ① **仓库根路径上溯层数写错**——脚本在 `os/02_sandbox_linux/deploy/local/` 下，
>    到仓库根要上溯 **4 层**，原来到只写了 2 层 → 指向不存在的 `os/02_sandbox_linux/demo`，
>    `set -e` 下静默退出（和 9.1 里 `20-deploy-frontend.sh` 是同一类 bug）。
>    结论：**别照抄别的脚本的层数**，按自己所在目录数；统一 `cd "$(dirname "$0")"` 后用
>    pwd 拼绝对路径。
> ② **ssh 会话挂住不退出**——云机脚本里 `nohup+setsid` 拉起的 JVM 虽然重定向了输出，
>    `ssh "… one demo restart"` 仍会挂住几分钟（部署其实早已完成）。
>    规避：重启命令整体 `< /dev/null > /dev/null 2>&1`（连 JVM 可能继承的 fd 一起指向
>    /dev/null），然后用**轮询 /calendar 直到 200** 确认真正就绪，不依赖 ssh 退出时机。
> ③ 顺带：本机脚本里**别写死 JAVA_HOME**（原脚本写死沙箱 Linux 的 JDK17 路径，
>    Windows 下直接报路径不存在），交给 PATH 里的 mvn/java，编译版本由 pom 控制。

---

## 五、nginx 入口层（唯一对外出口）

```
用户浏览器 ──▶ nginx:80
                ├── /            → demo 监控台前端（静态）
                ├── /api /ws     → demo 后端 8081
                └── 9090(独立端口)→ ms-demo gateway（首页右上角"微服务学习"入口跳转）
```

- nginx 配置独立文件，只做**分发**，不参与业务逻辑
- 微服务通过独立端口 9090 进入，与 demo 的路径互不重叠
- 后续若想"统一走 80 子路径"，可给 gateway 加 `context-path=/ms` 并配 nginx `location /ms`，但需验证页面相对资源路径

---

## 六、更新/发布流程（Gitee 中转）

```
开发机(沙箱/本地)  →  git push  →  Gitee  →  云机:
   cd /root/tools/ms-demo && ./deploy/server/10-start-msdemo.sh update
   # 内部: git pull + mvn package + 重启
```

按子项目单独部署：
- demo 单体：本地 `./deploy/local/10-deploy-demo.sh`（打包→上传→云机重启）
- 前端门户：本地 `./deploy/local/20-deploy-frontend.sh`
- 微服务：云机 `./deploy/server/10-start-msdemo.sh restart`

---

## 七、自学/面试视角的加分点

1. **命名与注释**：脚本中文注释、端口规划表、分层结构，面试能直接讲"我是怎么设计部署的"
2. **环境敏感配置与代码分离**：JVM 内存、端口、路径集中在脚本顶部可配置段，体现"配置化"
3. **隔离思想**：能讲清"单体项目 + 微服务项目共存一台机器，靠端口/目录/脚本三层隔离"——这是很实的架构问题
4. **自动化意识**：`update` 一键完成"拉取→构建→重启"，体现 DevOps 基本功

---

## 八、安全组与访问

- 对外只放行：`80`、`9090`（微服务入口）、`22`(SSH，建议改端口或密钥登录)
- 其余端口（8848/8082/8088/8081）**不对外**，仅内网回环访问

---

## 九、2026-10-03 门户改版：内嵌工作台 + 首页瘦身（上线步骤）

> 改了什么：首页方格不再 `target="_blank"`，改为**页内标签（iframe 工作台）**；
> 前端按路由拆包 + Monitor/Jobs 懒加载；nginx 缓存与压缩策略修正。
> 详见 `frontend/src/Workbench.vue`、`frontend/src/data/tools.js`、`frontend/src/store/auth.js` 的头注释。

**发布总览：改前端跑 ①，改 nginx 跑 ②，两个都改就都跑。都在本地 Git Bash 里执行。**

```bash
# ① 前端门户（构建 + 上传 + 落位，自带旧版备份）
bash os/02_sandbox_linux/deploy/local/20-deploy-frontend.sh
bash os/02_sandbox_linux/deploy/local/20-deploy-frontend.sh --skip-build   # 复用现有 dist

# ② nginx 配置（diff -> 上传 -> 备份 -> nginx -t -> reload，校验失败自动回滚）
bash os/02_sandbox_linux/deploy/local/25-deploy-nginx.sh
bash os/02_sandbox_linux/deploy/local/25-deploy-nginx.sh --check           # 只看差异不改动
```

发完照 9.3 的验证清单逐条 curl 一遍。

### 9.1 前端（本地执行）

> ⚠️ **Windows 用户注意**：`20-deploy-frontend.sh` 是 bash 脚本，**必须用 Git Bash 跑**，
> 不能双击、也不能在 cmd / PowerShell 里 `bash xxx.sh`（会用到 `set -euo pipefail`、for 循环、`dirname $0`）。
> 打开方式：仓库目录右键 → *Git Bash Here*，或开始菜单搜 *Git Bash*。
> 依赖已确认具备：Git Bash 自带 `bash`/`ssh`/`scp`；`~/.ssh/config` 里已有 `Host msdemo`；
> 云机 `admin` 免密 sudo 可用（本地**不需要** rsync，脚本只在云机端用 rsync，且带 cp 兜底）。

```bash
# 在 Git Bash 里，从仓库根目录执行（一条命令搞定构建+上传+落位）
bash os/02_sandbox_linux/deploy/local/20-deploy-frontend.sh

# 只想重发已有 dist、跳过构建：
bash os/02_sandbox_linux/deploy/local/20-deploy-frontend.sh --skip-build
```

> 🐞 **2026-10-03 修正**：脚本原先写 `cd "$(dirname "$0")/../../frontend"`，
> 只上溯 2 层 → 实际指向不存在的 `os/02_sandbox_linux/frontend`，
> 在 `set -e` 下**静默退出、什么都不做**。已改为上溯 4 层并用 `pwd` 解析绝对路径。
> 也就是说这条命令在你之前跑过的话，它一直是"看着成功了其实没部署"。

### 9.2 nginx（本地执行，脚本一键）

> 真相源 = 仓库里的 `os/03_product_opencloudos/etc/nginx/conf.d/nginx-server.conf`。
> **要改 nginx 就改仓库这份文件，再跑脚本**；不要直接 vim 服务器上的，否则下次部署整份被覆盖，改动会丢。

```bash
# 一键：diff 线上 -> 上传 -> 时间戳备份 -> nginx -t（失败自动回滚）-> reload
bash os/02_sandbox_linux/deploy/local/25-deploy-nginx.sh

# 只对比线上与仓库差异，不改动任何东西（改配置前后都建议先跑这个）
bash os/02_sandbox_linux/deploy/local/25-deploy-nginx.sh --check

# 落位并校验，但先不 reload（想观察一下再用）
bash os/02_sandbox_linux/deploy/local/25-deploy-nginx.sh --no-reload
```

脚本保护措施：跑之前先 diff（发现线上被手改会打印出来）；`nginx -t` 不通过会**自动回滚**到备份，绝不让坏配置生效；回滚命令在脚本结束时打印。

手动等价流程（脚本不可用时照这个来）：

```bash
scp os/03_product_opencloudos/etc/nginx/conf.d/nginx-server.conf msdemo:/tmp/
ssh msdemo "sudo cp /etc/nginx/conf.d/nginx-server.conf /etc/nginx/conf.d/nginx-server.conf.bak-ws-$(date +%Y%m%d%H%M%S) \
  && sudo cp /tmp/nginx-server.conf /etc/nginx/conf.d/nginx-server.conf && sudo nginx -t"
# nginx -t 必须显示 syntax ok / test is successful 再继续
ssh msdemo "sudo nginx -s reload"
```

### 9.3 上线后验证清单（逐条跑）

```bash
# ① hash 资源长缓存 + immutable
curl -sI http://101.43.45.217/assets/$(curl -s http://101.43.45.217/ | grep -oE 'index-[A-Za-z0-9_-]+\.js') \
  | grep -i cache-control      # 期望: public, max-age=31536000, immutable

# ② index.html 不缓存（保证每次发布都能被用户拿到）
curl -sI http://101.43.45.217/ | grep -i cache-control   # 期望: no-cache

# ③ Vary 头补上了
curl -sI -H 'Accept-Encoding: gzip' http://101.43.45.217/ | grep -i vary  # 期望: Accept-Encoding

# ④ /tool 等 4 个 Spring 页 gzip 恢复且 sub_filter 顶栏还在（★ 唯一需要人工确认的项）
curl -s -H 'Accept-Encoding: gzip' --compressed http://101.43.45.217/tool | grep -c gtop
#   ≥1 = 正常；0 = 顶栏丢了 → 把 nginx-server.conf 里 /tool /eat /hub /calendar 四处的
#        「gzip 开回默认 on」注释下面恢复成 `gzip off;`，重新 reload 即可回滚

# ⑤ 首页体积（应明显小于原来的 124KB）
curl -s http://101.43.45.217/ | grep -oE 'assets/[A-Za-z0-9_-]+\.js'
```

### 9.4 本次顺带发现、需要另外处理的部署缺口

- **`/learn/demo/热点加载优化.html` 线上 404**：文件在仓库
  `os/03_product_opencloudos/usr/share/nginx/html/learn/demo/` 里，但服务器上没有这个目录，
  首页对应方格是死链。补传：
  ```bash
  scp -r "os/03_product_opencloudos/usr/share/nginx/html/learn/demo" msdemo:/tmp/
  ssh msdemo "sudo cp -r /tmp/demo /usr/share/nginx/html/learn/"
  ```
- **`cat.jpg`(717KB) 已从 `frontend/public/` 删除**：全仓与所有线上页面均无引用（死文件），
  每次 deploy 都白白上传。仍需恢复时 `git checkout -- frontend/public/cat.jpg`。
- 首页新增了漏掉的「11-小程序 Bearer 认证」教学台方格（线上页面本就存在）。

---

## 十、2026-10-03 活动页实时化：新增 activity-service（上线步骤）

> 改了什么：`/events.html` 从"写死的 11 条活动"变成**实时数据**。
> 新增微服务 `ms-activity-service`（8085）：定时采集活动行（huodongxing.com）南京活动，
> 合并人工精选条目，通过网关 `/activity/**` 对外提供接口；前端先渲染离线兜底、
> 再拉实时数据，接口挂了自动降级。

### 10.0 架构一览

```
events.html ──> nginx /ms/activity/** ──> gateway(9090) ──> activity-service(8085)
                                                              ├─ 定时采集活动行（cron 6/12/18/23 点）
                                                              ├─ 人工精选 seed-activities.json（打包内置）
                                                              └─ 落盘缓存 data/activities-cache.json（重启不丢）
```

### 10.1 部署步骤

```bash
# ① 微服务：打包 + 上传 4 个 jar（含新增的 activity）
bash os/02_sandbox_linux/deploy/local/30-build-upload-msdemo.sh
ssh msdemo "sudo bash /tmp/40-deploy-msjar.sh"

# ② 前端：构建 + 上传（events.html 已挪进 frontend/public/，构建自动带上）
cd frontend && npm run build && bash ../os/02_sandbox_linux/deploy/local/20-deploy-frontend.sh

# ③ nginx 不用动：/ms/ → 网关的反代早已存在，新路由在网关 yml 里
#    ★ 但网关 jar 更新了（加了 /activity/** 路由），①里的 40-deploy 会重启它
```

### 10.2 上线后验证清单

```bash
# ① 服务活着（期望 200，且返回活动条数）
curl http://101.43.45.217/ms/activity/ping

# ② 数据新鲜度（updatedAt 应是最近一次采集时间；seedOnly 应为 false）
curl -s http://101.43.45.217/ms/activity/status

# ③ 列表接口：total 应远大于 11（精选 11 + 采集几十条）
curl -s http://101.43.45.217/ms/activity/list | head -c 300

# ④ 前端页面：打开 http://101.43.45.217/events.html
#    页头应有绿色「实时」标签；如果显示黄色「离线」说明接口没通，查 ①②
```

### 10.3 日常维护

```bash
# 手动触发一次采集（不用等 cron）
curl -X POST http://101.43.45.217/ms/activity/refresh

# 看采集日志（服务器上）
ssh msdemo "tail -50 /root/tools/ms-demo/logs/activity-8085.log"

# 加人工精选活动：改 ms-activity-service/src/main/resources/seed-activities.json
# （加一条 = 多一场，pinned 自动置顶），重新打包部署即可

# 调采集参数：ms-activity-service application.yml 的 activity.*（页数/频率/城市）
```

### 10.4 本次实现里值得知道的坑（教学价值）

- **年份推断**：活动行列表页只给"月/日"不给年，且**靠后的页面混着已结束的历史活动**
  （拿详情页核对过：列表写 04/18，实际是半年前）。规则用"今年/明年两个候选，
  取离今天近的那个"，两个方向都能对。
- **SERVER__PORT 环境变量**：本机 IDE 注入了 `SERVER__PORT=53012`，Spring Boot 的
  relaxed binding 会把它当成 `server.port` 覆盖掉 yml —— 本地起服务时显式加
  `--server.port=8085` 才稳。服务器上没有这个变量，不受影响。
- **record 反序列化**：Activity 用 record，自建 ObjectMapper 不会注册
  ParameterNamesModule，反序列化会报 no property-based Creator——必须用 Spring
  注入的那个。
- **网关白名单**：只加了路由（`application.yml` 的 `activity-route`）**不够**，还必须把
  `/activity/**` 加进 `AuthGlobalFilter.WHITELIST`。否则请求先被鉴权过滤器拦下 → 前端拿到
  **401** → 页面永远走离线兜底。注意 nginx 的 `location /ms` 会剥掉 `/ms` 前缀，
  所以网关侧真实路径就是 `/activity/**`（不是 `/ms/activity/**`）。
  自查：`curl http://101.43.45.217/ms/activity/ping` 期望 200，返回 401 就是这个原因。
- **采集源按 IP 拒绝（环境限制，非代码 bug）**：`huodongxing.com` 对本机（家宽）返回 200，
  对云机（腾讯云 IP）**无论带不带 User-Agent 一律 403**（已用 `curl` 双向对比确认）。
  所以线上 `collected=0`、`seedOnly=true`，页面只能展示人工精选的 11 条。
  服务本身不报错、接口照常 200，属于**采集降级**；要恢复"真实时"需换数据源或换出口 IP。
  为此前端加了区分：[`events.html`](../frontend/public/events.html) 只有 `updatedAt` 非空
  才打绿色「实时」，否则打蓝色「精选」，避免"接口通了就等于实时"的误导。
- **Windows 打包脚本上溯层级**：`ms-demo` 是独立仓库根，`os/01_local_windows11/`
  下的脚本到仓库根只需上溯 **2 层**（`%~dp0..\..`）。写成 3 层会落到
  `C:\Users\<用户>`，`mvn` 找不到 `pom.xml` 直接失败——脚本已修正并加 `pom.xml` 存在性校验。


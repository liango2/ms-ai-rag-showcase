# ms-demo 项目长期备忘

## 项目概况
- 个人微服务学习项目（Spring Boot 3.3.5 + Spring Cloud Alibaba 2023.0.3.2 + Nacos 2.5.4）
- Maven 多模块：order-service(8088/8089)、user-service(8082)、gateway(8080) + demo-legacy 单体(8081) + Vue 前端门户 + nginx 反代，已部署公网服务器
- 学习轨迹：Nacos注册发现→加权负载均衡→网关路由/JWT/RBAC/双token→限流/超时重试/熔断→CORS→nginx部署→小程序Bearer认证
- learn-docs/ 是教学页目录（编号 07~12 已建），用户用"实验闭环+证据留存"方式自学，代码注释密度很高
- 用户同时在学 AI，意向方向：AI 应用工程（Spring AI / AI 网关 / RAG）

## 重要约定 / 教训
- 🔴 已发现 yml 中 Nacos 密码硬编码并提交 git（2026-10-03 报告中指出），待用户改为环境变量注入并换密码
- 根目录曾有 116+ 实验证据 txt/log 杂物，建议方案：docs/evidence/chNN-主题/ 归档 + .gitignore 补 *.log、BOOT-INF、work/
- 对话原则：代码按架构组织（不要按学习章节命包名），文档按学习路径组织（docs/chapters/ 每章 README 四段模板）

## 前端门户架构（2026-10-03 起）
- `frontend/src/data/tools.js` = 首页方格唯一数据源：加工具=加一条记录（kind: vue/frame/ext），不要往 Portal.vue 硬编码 <a>
- 页面打开方式：同源页面走 Workbench iframe 页内标签；外部站点(ext) 必须 target="_blank"（对方 X-Frame-Options 拒绝内嵌）
- vite.config.js 有 `__VUE_OPTIONS_API__: false`：新组件必须用 <script setup>，写 Options API 会静默不渲染
- nginx hash 资源 immutable 1y / index.html no-cache 的约定已固化在 os/03_product_opencloudos/etc/nginx/conf.d/nginx-server.conf
- 教学页源文件在 os/03_product_opencloudos/usr/share/nginx/html/learn/，改完要 scp 上云机（没有对应 deploy 脚本，历史上有漏传导致 404）
- 门户改版已于 2026-10-03 上线（页内工作台 + 拆包 + nginx 缓存策略），验证清单固化在 os/DEPLOY.md 第九节
- deploy 脚本踩坑：20-deploy-frontend.sh 的相对路径曾写错 2 层导致静默不部署，现已改为 SCRIPT_DIR+pwd 解析 4 层；以后写 deploy 脚本优先用 pwd 绝对路径
- 🔴 路径层级别照抄层数：ms-demo 是**独立仓库根**。`os/01_local_windows11/xxx.cmd` → 上溯 **2 层**；`os/02_sandbox_linux/deploy/local/xxx.sh` → 上溯 **4 层**。写错会落到 C:\Users\<用户>，mvn 找不到 pom.xml
- 🔴 加网关路由必须同步改 `AuthGlobalFilter.WHITELIST`，否则请求被鉴权拦成 401（nginx `location /ms` 会剥前缀，网关侧路径不含 /ms）
- 🐞 云机 IP 被活动行(huodongxing.com) 403（家宽 200 / 腾讯云 403，带不带 UA 都一样）→ 线上采集只能是 0 条，靠人工精选；属于环境限制不是代码 bug
- deploy 脚本一律 **前台执行 + dangerouslyDisableSandbox**：后台模式无法提权，ssh 会因读不到 ~/.ssh/config 而报 "Could not resolve hostname msdemo"
- 🔴 `ssh "云机脚本重启 nohup 服务"` 会**挂住几分钟不退出**（部署其实已完成）：重启命令整体 `< /dev/null > /dev/null 2>&1`，再用轮询 curl 确认就绪；且**先停进程再 cp 覆盖 jar**，顺序别反
- 🛠 可复用调试工具：`work/shot-el.cjs <url> <选择器> <out.png> [scale]`（元素级截屏，必须 `captureBeyondViewport:true`）、`work/eval-el.cjs <url> <js>`（页面执行 JS）；Chrome 启动参数加 `--no-proxy-server`（本机代理会拦 localhost）。本地验证 Spring 页面不必起服务：`work/caltest/` 把 text block HTML 抽出 + 桩 fetch 喂线上 JSON
- 首页方格选择器：卡片=a.card-link，标签栏=App.vue 的 .tabbar（首页标签是第一个 .tab，无独立类名）

## activity-service（2026-10-03 起）
- 活动实时数据链路：events.html → /ms/activity/** → gateway → activity-service(8085)；采集源=活动行（服务端渲染可抓），人工精选在 seed-activities.json（pinned 自动置顶）
- 改采集行为只动 ms-activity-service 的 application.yml（pages/cron/keep-past-days/enabled）；加精选活动改 seed-activities.json 后重新打包
- 部署后验证：/ms/activity/status 看 updatedAt 和 seedOnly；手动采集 curl -X POST /ms/activity/refresh
- 本机起任何 Spring 服务都要显式 --server.port=xxxx：IDE 注入的 SERVER__PORT 环境变量会覆盖 yml 端口
- 采集解析规则改了必须跑 HuodongxingParserTest/CategoryMapperTest（19 条单测钉住了年份就近、分类优先级、脏卡片跳过）
- 🐞 springdoc 没有 `swagger-ui.servers` 这个 yml 属性（写了不生效）：servers 必须 OpenAPI Bean 覆盖，三个业务服务都有 OpenApiConfig（servers=/ms）

## 网关接口文档（2026-10-04 起）
- knife4j-gateway-spring-boot-starter 4.5.0（manual 策略聚合 3 服务）与 springdoc Swagger UI 共存，数据源同是 /v3/api-docs/{svc}；配置在 gateway application.yml `knife4j.gateway.*`
- 🔴 白名单成对坑：/doc.html 之外**必须**加 /swagger-config（knife4j 聚合服务列表接口），漏加则 doc.html 空分组；knife4j 该 starter 依赖干净（springdoc provided），不与 springdoc 2.6.0 冲突
- 本地无 Nacos 服务端（~/nacos 只有客户端缓存）→ 网关 failFast 起不来，本地只能打包不能冒烟，直接上云验证

## 部署细节补充（2026-10-04）
- 🔴 ssh msdemo 用户是 **admin 非 root**：cp 进 /root/tools/** 必须 `sudo`，重启用 `sudo nohup bash /root/tools/ms-demo/deploy/server/10-start-msdemo.sh one gateway restart >/tmp/x.log 2>&1 < /dev/null &` 再轮询 curl；覆盖 jar 前先 `sudo cp 原jar 原jar.bak-日期` 留回滚
- 🔴 本机代理(HTTP_PROXY=127.0.0.1:57429)会**拦 gitee push 挂死被 SIGTERM**：推送要 `git -c http.proxy= -c https.proxy= push`；curl 本机/云机都要 `--noproxy '*'`
- 🛠 shot-el/eval-el 的 CHROME_BIN 用用户级 Chrome：`C:/Users/hanliangyuan/AppData/Local/Google/Chrome/Application/chrome.exe`（Program Files 下没有）；doc.html 这类重 JS 页面要把 shot-el 的 2.5s 等待 sed 成 15s
- 🐞 shot-el/eval-el 调试端口已改随机（9300+pid%400 / 9750+pid%200）：固定端口会因上个 Chrome 子进程未退净报"DevTools 端口未就绪"

## 全站导航架构（2026-10-04 起）
- 导航唯一实现在 `os/.../learn/ms-demo/_learn-nav.js`（单文件，按 URL 自动识别层级：门户/#/ → 实验门户/ms/ → 学习目录/learn/ms-demo/ → 各教学页），渲染面包屑+返回上一层+平级上一章/下一章+高亮；iframe 内自动 target=_top
- 🔴 **新建教学页必须在 `<body>` 后加 `<script src="/learn/ms-demo/_learn-nav.js" defer></script>`**，不要再手写返回链接；网关 3 个静态页（index/login/refresh-demo）也已接入（在 jar 里，改了要重打包 gateway）
- 门户入口卡在 tools.js：📚学习路线总览（指向学习目录）、🎯HTTP 状态码速查；学习目录 index.html 有"三层导览"块
- 批量改教学页可参考 `work/patch-learn-nav.cjs`（node 一次打 13 页）

## demo 单体页面开发约定（2026-10-03）
- /tool /eat /calendar 页面都内嵌在门户工作台 iframe 里：页面自己的"返回首页"要 `window.self!==window.top` 时隐藏
- 页面 HTML 全部是 Java text block（Controller 里），改完 `cd demo && mvn -q package -DskipTests`，scp jar 到 msdemo:/tmp，远程 `nohup bash /root/tools/ms-demo/deploy/server/10-start-msdemo.sh one demo restart >/tmp/restart-demo.log 2>&1 &`（远程后台化防 ssh 挂住），再轮询线上 200
- demo 无外置 yml，配置打进 jar（weather.city=江宁、QWEATHER_KEY/DASHSCOPE_KEY 走云机 /root/demo/deploy.env 环境变量，和风分支实际生效）
- 动态天空组件（tool/eat 复用）：body.ph-night/dawn/day/dusk + #sky 图层（星/月/日/云/雨/雪/雾），时段优先用天气 API 返回的真实日出日落算
- 🐞 Java text block 里新增修饰类不要撞页面已有类名（曾因 `foglayer back` 撞上 `.back` 按钮样式画出 700px 大圆盘）
- 和风 24h 逐时预报的时间字段是 `fxTime` 不是 `time`

## 性能测量约定（2026-10-03）
- 🛠 工具：`work/perf.cjs`（单次导航时序+资源瀑布）、`work/perf2.cjs`（预热/冷热对比）、`work/perf3.cjs`（对照组实验）；跑法 `CHROME_BIN=... node work/perfX.cjs <url>`，必须 foreground + dangerouslyDisableSandbox
- 🔴 本机无头 Chrome **新会话第一个真实 HTTP 导航**有 ~3s 的"请求发出耗时"（dns/tcp 均 0），同浏览器第二次导航即降到 ~10ms → **测性能必须先预热，再和 data URL 对照**，否则误判服务端。服务端真实水平：curl TTFB 15~25ms
- 🔴 nginx `location ^~ /ms/` 里写了 `gzip off`（为 sub_filter 留）→ **/ms/ 下所有 JSON 不压缩**（/ms/activity/list 5959B，gzip-9 仅 2294B）。`/tool` 那段已改回 gzip on，/ms/ 是漏网的
- 线上 nginx 只 `listen 80`，**无 HTTP/2**；`/assets/*` immutable 1y 已验证生效（重复访问 transfer=0）
- 新增静态页要显式给缓存头（events.html 走了 `location /` 兜底，无 Cache-Control，每次条件请求）；`frontend/index.html` 无 favicon 声明会白多一个 /favicon.ico 请求

## 服务器学习台（2026-10-04 上线）
- /#/monitor = 5 tab 学习台（请求的一生/JVM内存/GC/线程池与锁/socket与IO），数据源 demo 单体 JvmProbe/JvmController + MonitorHub；前端逻辑在 frontend/src/monitor/（QaCard 答案默认折叠=刻意教学设计）
- 🔴 G1 分代判断按 GC bean 名不是池名；G1 Concurrent GC 单列，不算 Full；Metaspace 无 max 显示"—"不显示百分比
- 🔴 上云 jar 必须是 repackage 过的（MANIFEST 有 JarLauncher）；"no main manifest attribute"=薄 jar
- 🔴 ssh 重启 demo 正确姿势：`sudo -n nohup bash 10-start-msdemo.sh one demo start >/tmp/x.log 2>&1 </dev/null &`，脚本内 start_demo 自带 setsid；`restart`+`&` 曾被 SIGHUP 打断成 502
- 排查 jar 问题用 `unzip -p xxx.jar META-INF/MANIFEST.MF`；.gitattributes 已锁 *.sh eol=lf（CRLF 上云会让 stop 静默失效）

## 🔴🔴 杀进程只认 java，绝不能 pgrep -f（2026-10-04 钉死）
- `pgrep -f 'xxx.jar'` 会匹配到调用方 ssh 会话自己的 shell（命令行里就含 jar 名）→ kill 自杀 → 部署静默半成品/服务起不来。历史多次"RESTART_DONE 不出现/502"的真凶
- 正确姿势：`pgrep -x java` 后逐个 `tr '\0' ' ' </proc/PID/cmdline | grep -qF "$pat"`（已封装成 10-start-msdemo.sh 的 java_pids_matching）
- 部署后必须校验：远端 jar 字节数 == 本地（10-deploy-demo.sh 第 3b 步），否则视为失败
- 判 CRLF 用 `tr -dc '\r' | wc -c`，别用 grep -q $'\r'（会假阴性）

## 内存全貌接口（2026-10-04）
- /api/mem/nest = 宿主 /proc/meminfo + 进程 /proc/self/status VmRSS + 各池 committed 三级口径，JvmProbe.memNest() 直接吐树给前端画真实比例嵌套图
- 口径：进程内用 committed 不是 used；线程栈=线程数×-Xss 是上界；RSS−已知部分 挂"其他"（RSS≠堆+元空间 本身就是结论）
- 前端 frontend/src/monitor/MemoryNest.vue（slice-and-dice 递归布局，百分比绝对定位）；堆内分代单独画一条（G1 下 Survivor 才 1MB，塞嵌套图里不足 1 像素）

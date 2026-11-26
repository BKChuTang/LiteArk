# LiteArk 开发环境搭建与工程经验（第三方 Agent 必读）

> 目标：让接手的 agent 在**不读全量源码**的情况下，30 分钟内跑通开发环境、打出可用 jar、避开已知陷阱。
> 本文只写"怎么做"和"为什么这么做"，功能语义见 `功能说明.md`，配置项见 `配置说明.md`。

---

## 0. 最短路径（TL;DR）

```bash
# 前提：JDK 8、Node 18+、Maven 3.6+、Python 3（可选，跑测试用）
cd LiteArk
chmod +x build.sh && ./build.sh          # 前端构建 → 注入 static → mvn package
java -jar target/liteark.jar             # 默认 8080，首次启动自动建库
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/   # 期望 200
```

改前端代码时不要每次打 jar：

```bash
cd web && npm ci && npm run dev          # 5173，/api 自动代理到 8080
# 另开终端：mvn spring-boot:run（后端 8080）
```

---

## 1. 环境依赖与版本基线

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **必须 1.8** | Spring Boot 2.7.18 + `java.version=1.8`；用 11/17 编译会在 `maven-compiler-plugin` 或反射处出问题 |
| Maven | 3.6+（本机 3.9.9） | 首次构建需联网拉依赖；离线环境需预置 `~/.m2/repository` |
| Node.js | **18+（推荐 20/22）** | Vite 6.0.11 要求；本机 Node v22.22.2 / npm 10.9.7 |
| Python | 3.8+ | 仅跑接口测试需要，`pip3 install requests` |
| SQLite | 无需单独安装 | `sqlite-jdbc 3.45.3.0` 内嵌；调试可用 `sqlite3` CLI |
| LibreOffice | 可选 | 装了则 Office 走 PDF 高保真预览；没装则 docx/xlsx/pptx 走 POI HTML 兜底，旧版 doc/xls/ppt 仍需 LibreOffice |

本机（macOS）工具路径示例 — **不要写死进代码**，仅用于本机命令：

```
JDK8    /Users/ChuTang/.liteark-tools/jdk8/Contents/Home
Maven   /Users/ChuTang/.liteark-tools/apache-maven-3.9.9/bin/mvn
Settings /Users/ChuTang/.liteark-tools/settings.xml
```

第三方机器请自行设置 `JAVA_HOME` 并把 `$JAVA_HOME/bin` 加入 `PATH`，再执行 `mvn -v` 确认显示 `1.8.x`。

---

## 2. 源码包结构与"哪些是生成物"

```
LiteArk-V1.1-source/
├── README.md                  # 产品与生产部署说明（先读）
├── build.sh                   # 一键构建脚本（本文配套）
├── pom.xml                    # finalName=liteark → target/liteark.jar
├── src/
│   ├── main/java/com/liteark/ # controller / service / mapper / entity / security / util / config
│   └── main/resources/
│       ├── application.yml    # 内置默认配置
│       ├── schema.sql         # 10 张表，启动自动建表
│       └── static/            # ⚠ 前端构建产物（随包附带，可重新生成）
├── web/
│   ├── package.json / package-lock.json / vite.config.js / index.html
│   ├── public/                # logo.svg / favicon.svg / favicon.ico / apple-touch-icon.png
│   └── src/                   # Vue3 源码（views / components / api / stores / utils / styles）
├── deploy/conf/application.yml# 生产外置配置模板
├── docs/                      # 功能、权限、网盘、配置、测试方案/记录/报告、本文
└── tests/run_full_test.py     # 96 条接口回归用例
```

**明确排除的生成物**：`node_modules/`、`target/`、`data/`（数据库+用户文件）、`logs/`、`web/dist/`、根目录历史 `LiteArk-V1.1-*.zip`。
包内 `src/main/resources/static/` 是随包附带的**预构建前端**，保证不装 Node 也能直接 `mvn package`；一旦改了前端源码必须重建，否则 jar 里是旧界面。

---

## 3. 三种运行方式

### 3.1 前后端分离开发（改代码时用）

终端 A：
```bash
cd web && npm ci && npm run dev      # http://localhost:5173
```
`vite.config.js` 已把 `/api` 代理到 `http://localhost:8080`，**不要**在前端写死后端地址。

终端 B：
```bash
export JAVA_HOME=<JDK8路径> && export PATH=$JAVA_HOME/bin:$PATH
mvn spring-boot:run                  # http://localhost:8080
```

> 验证新前端无需重新打 jar：dev server 直接热更新，只有最终交付才需要构建。

### 3.2 单 fat jar 本地运行（验证最终产物）

```bash
./build.sh
java -Dfile.encoding=UTF-8 -jar target/liteark.jar
# 指定数据目录 / 端口 / 日志：
java -jar target/liteark.jar --server.port=9090 --liteark.data-dir=/tmp/liteark-data
```

### 3.3 内网生产部署

```bash
mkdir -p /opt/liteark/{conf,data,logs}
cp target/liteark.jar /opt/liteark/
cp deploy/conf/application.yml /opt/liteark/conf/
java -jar /opt/liteark/liteark.jar \
  --spring.config.location=/opt/liteark/conf/application.yml
```

首次启动自动：建表（`schema.sql` + `DataInitializer` 迁移）、初始化角色与账号。起点：`/`、`/admin/overview`、`/s/{token}`。

**安全动作（必须）**：改 `liteark.jwt-secret` 为长随机串、改 `admin/admin123` 与 `demo/123456` 密码。

---

## 4. 构建顺序（最容易踩坑的地方）

`build.sh` 已封装以下步骤，手工执行时**顺序不可颠倒**：

```bash
cd web && npm ci && npm run build && cd ..
rm -rf src/main/resources/static/*
cp -r web/dist/* src/main/resources/static/
mvn clean package -DskipTests
# 产物 target/liteark.jar
```

**为什么必须先前端后 Java**：jar 的静态资源来自 `src/main/resources/static`，不是 `web/dist`。只跑 `mvn package` 会得到"接口可用但页面 404/旧界面"的 jar。

**打包后必查**（曾发生过前端资源漏进 jar）：

```bash
for p in / /favicon.svg /logo.svg /assets/; do
  printf "%s " "$p"; curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080$p"
done   # 全部期望 200
```

`favicon.ico` 必须排在 `favicon.svg` 之前声明：Safari 不支持 SVG favicon，缺 ico 会导致页签图标空白。

---

## 5. 配置要点（完整表见 `docs/配置说明.md`）

- 配置优先级：命令行参数 > 外置 `--spring.config.location` > jar 内置 `application.yml`。
- `liteark.data-dir`（默认 `./data`）统一决定数据库与文件实体位置；数据源 URL 写作 `jdbc:sqlite:${liteark.data-dir}/liteark.db`，改这一个即可整体迁移。
- `spring.datasource.hikari.maximum-pool-size: 1` 是 SQLite 单写锁的**刻意设置**，不要为了"性能"调大，否则并发写入报 database is locked。
- JWT：`liteark.jwt-secret` 生产必须外置覆盖；请求头 `Authorization: Bearer <token>`。
- 接口文档默认关闭，内网需要时 `LITEARK_API_DOCS=true`。
- 后台 IP 白名单：`liteark.admin-ip-whitelist-enabled=true` 后拦截 `/admin/**` 与 `/api/admin/**`。

---

## 6. 验证清单

### 6.1 接口全量回归（推荐先跑）

```bash
pip3 install requests
cd <项目根>                    # 脚本依赖相对路径 data/liteark.db
python3 tests/run_full_test.py
# 期望：===== SUMMARY: 96/96 PASS, 0 FAIL, rate=100.0% =====
# 原始结果：docs/test-results.json；人读记录：docs/测试执行记录.md
```

脚本会创建 5 个测试用户并自动清理测试数据，**不要手动批量删 permission 表**：库中预置了 `demo`、`lisi` 等权限规则，误删后需从 `data/liteark.db.bak-*` 恢复。

测试用户（保留供回归）：`qa_owner` / `qa_view` / `qa_edit` / `qa_share` / `qa_guest`，密码 `Test@123456`。

### 6.2 UI 截图验证（无需 puppeteer）

Chrome headless 开 `--remote-debugging-port`，用 Node 22 内置 `WebSocket` 直连 CDP：

1. `Page.navigate` 到目标页；
2. `Runtime.evaluate` 注入 `localStorage.setItem('liteark_token', token)` 与用户信息；
3. `Page.captureScreenshot` 取图。

前端 dev(5173) 的 `/api` 代理已配置，验证新前端不用重新打包。

---

## 7. 工程约定与踩坑清单（重点）

### 7.1 前端

- **scoped CSS 对 `h()` 内联组件无效**：`Layout.vue` / `AdminLayout.vue` 的侧栏（`.side-inner`、`.side-brand`、`.side-menu`）由 `h()` 渲染，元素不带 scoped 的 `data-v` 属性。这类样式统一写 `src/styles/main.css`，按 `.la-side`（前台）/ `.admin-side`（后台）区分；菜单高亮同理。
- 组件根元素可继承父 scopeId，因此 `<BrandLogo class="...">` 的父级 scoped 样式能命中。
- **Vue `reactive()` 陷阱**：上传队列 `push` 原始对象后再改属性不会触发渲染。已修为 `const task = reactive(saved || {...})`，新增任务时同样要 `reactive`。
- **浏览器同域 HTTP/1.1 连接上限约 6**：多文件并行上传时分片并发数按 `Math.floor(6 / activeCount)` 摊薄，否则第 3 个文件会长时间 0%。最大并行上传数 `MAX_PARALLEL_UPLOADS = 3`。
- xlsx 多 sheet 固定走 Apache POI 渲染 HTML 页签（LibreOffice 转 PDF 没有页签）：`input[type=radio] + label + .la-panel` 纯 CSS 切换，适配 iframe CSP sandbox；支持合并单元格 `colspan/rowspan`；表格宽 `width:auto;min-width:100%`，写 `100%` 会挤成竖排文字。
- 预览 HTML 有缓存版本号 `HTML_VER = "v4"`，**改 HTML 结构必须递增**，否则命中旧缓存看不到变化。
- 品牌资源统一放 `web/public/`：`logo.svg`（通用）、`favicon.svg`（透明底小尺寸）、`favicon.ico`（7 帧 16–256）、`apple-touch-icon.png`（180）；`index.html` 已按 ico→svg→apple→mask 顺序声明。

### 7.2 后端

- **审计日志给管理员看**：`resource`/`detail` 一律写业务语言，不写内部 ID、token、权限位英文。统一走 `BaseController.audit(...)`，名称解析用 `AuditService`（`fileLabel`/`dirPath`/`userName`/`roleName`/`briefNames`/`perms`/`client`/`configLabels`）。
- 登录/注册时 JWT 尚未生效，`currentUser` 取不到人，**必须 `auditFor(user, ...)` 显式传操作人**，否则"操作人"列为空。
- IP 统一经 `ClientUtil.normalizeIp()`：`::1`/`0:0:...:1` → `127.0.0.1`，`::ffff:x.x.x.x` 去映射前缀，带端口去端口；真实公网 IPv6 保留。
- **MyBatis-Plus 默认忽略 null**：字段要清空（如 `targetUserIds`）必须 `@TableField(updateStrategy = FieldStrategy.IGNORED)`。
- **权限位六项**：`view/edit/download/upload/share_internal/share_external`，沿目录链逐层求交集；所有者恒全权。旧位 `delete→edit`、`share→view` 归一化，**但不会自动获得新的分享权**。
- 内部分享：校验登录 + 名单，创建者本人放行；分享列表/下载/预览用**真实文件所有者**定位存储（非所有者可凭权限创建分享）。
- **复制必须物理独立**：副本若复用源 `storageKey`，彻底删除副本会删掉原文件实体，且在线编辑一方会改写另一方。`copyOne()` 现调用 `copyPhysical()` 生成独立 key；`permanentDelete()`/`clearRecycle()` 删除前经 `deletePhysicalIfUnused()` 检查 `file_meta` 中是否仍有引用（含回收站行），兼容历史共享 key 的旧数据。
- **共享访问记录鉴权**：`GET /api/shares/{id}/access` 仅创建者、文件所有者、管理员可查看，其余 403。
- 上传任务：`calculateMd5()` 有 settled 幂等、异常 reject、120s 看门狗；取消任务让出并发位，排队态可暂停/取消；字节进度 120ms 节流。
- `DataInitializer` 会执行多条 `ALTER TABLE ... ADD COLUMN` 迁移（share_type、target_user_ids、source_role_id 等），**旧库启动即自动升级**，不要手工改 schema.sql 了事。

### 7.3 测试与数据

- 测试脚本的 cleanup 只删除本次测试创建的规则与用户（用户按用户名回读 id 删除）；历史上因"批量删 permission"误删过预置规则，已修，勿回退该逻辑。
- 操作日志属审计记录，测试后不删除；CSV 导出已确认不含 token。
- 彻底删除/清空回收站会回收配额（`user.used` 与 `SUM(file_meta.size)` 应保持一致）。

---

## 8. 出包与部署提醒

- 根目录 `LiteArk-V1.1-full-deploy-*.zip` 是**历史部署包，不含后续修复**（含本次两个缺陷修复、角色权限布局、内/外部分享等）。正式出包必须基于最新 `target/liteark.jar` 重新制作。
- 生产只需 jar + `conf/application.yml` + `data/`；备份打包 `data/` 即可。
- V1.1 剩余边界：OnlyOffice 在线编辑、文本存储备选模式、arm64/amd64 实机验证记录，详见 `docs/功能说明.md`。

---

## 9. FAQ

| 现象 | 原因与处理 |
|---|---|
| `mvn package` 成功但页面 404 | 前端产物没进 `static/`。按第 4 节顺序重建。 |
| 页签图标空白 | 缺 `favicon.ico`，Safari 不支持 SVG favicon。 |
| 侧栏样式不生效 | 写在 scoped 里但元素是 `h()` 渲染的；迁到 `main.css`。 |
| 上传卡在"计算文件校验值" | MD5 计算静默挂起或 raw 对象非响应式；检查 `calculateMd5()` 的 reject/看门狗与 `reactive()`。 |
| 多文件第 3 个长时间 0% | 分片并发超浏览器连接上限；按活动任务数均分。 |
| xlsx 只显示第一页 | 走了 LibreOffice PDF 通道；固定 POI HTML 页签，并递增 `HTML_VER`。 |
| 历史共享 key 的旧副本编辑同步 | 删除侧已加引用检查兜底；需彻底隔离则按 `storage_key` 分组扫描 `file_meta`，逐条物理复制并更新 key。 |
| 后台"修改权限"看不到保存按钮 | 已重构为"列表优先、点编辑后在下方展开操作区"；若仍是旧界面，说明 jar 内前端是旧产物。 |
| SQLite 并发写报 locked | 确认 `maximum-pool-size=1` 未被改动。 |

<p align="center"><img src="web/public/logo.svg" width="88" alt="LiteArk"></p>

<h1 align="center">轻舟 LiteArk · 私有网盘</h1>

<p align="center">
轻量 · 私有 · 可靠的自托管文件网盘<br/>
单 fat jar 交付，内网离线可部署，PC 与手机同一套界面
</p>

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-8-orange">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3-42b883">
  <img alt="SQLite" src="https://img.shields.io/badge/SQLite-single%20file-003B57">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-blue">
</p>

轻舟是一套可完全内网离线部署的私有网盘，采用 **单 fat jar** 交付：前端构建产物内嵌在 jar 中，内网服务器只需预装 JDK 1.8，无需 Maven、无需拉取任何依赖、无需单独部署前端或 Nginx。

- 技术基线：SpringBoot 2.7.18 + Java 8 + MyBatis-Plus 3.5.5 + SQLite（sqlite-jdbc）+ Vue3 + Element Plus
- 交付形态：`target/liteark.jar`（约 60MB，含前端 dist 与全部依赖）+ 一个外置配置目录
- 访问入口：前台网盘 `/`、管理后台 `/admin/overview`、免登录共享页 `/s/{token}`

## 一、环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| OpenJDK | **1.8.x** | 后端编译与运行。`pom.xml` 锁定 source/target 1.8，用 17+ 编译会失败 |
| Maven | 3.6+ | 后端构建 |
| Node.js | 18+ | 前端构建，推荐 20 LTS |
| npm | 9+ | 前端依赖安装 |
| LibreOffice | 7.x（可选） | Office 文档转 PDF 预览。不装则降级为 Apache POI 渲染只读 HTML（旧版 doc/xls/ppt 不支持） |

生产机只需 JDK 1.8（fat jar 已含全部依赖）；上表其余项仅开发机需要。

## 二、拉取代码与本地开发

### 2.1 拉取代码

```bash
# SSH（推荐）
git clone git@github.com:BKChuTang/LiteArk.git
cd LiteArk

# 或 HTTPS
git clone https://github.com/BKChuTang/LiteArk.git
cd LiteArk
```

### 2.2 启动后端（8080）

```bash
export JAVA_HOME=/path/to/jdk1.8      # 必须 1.8
mvn -v                                # 确认 Maven 实际使用的是 JDK8

mvn spring-boot:run
# 或先打包再运行
mvn clean package -DskipTests && java -jar target/liteark.jar
```

- 默认端口 **8080**，改端口：`--server.port=9090` 或环境变量 `LITEARK_PORT=9090`
- 首次启动自动建表、初始化角色与账号（见 3.3）
- 数据库默认落在**启动目录下的 `./data/liteark.db`**，可用 `--liteark.data-dir=/your/path` 指定
- 想清库重来：停服务，删掉 `data/` 目录即可

### 2.3 启动前端（5173，热更新）

```bash
cd web
npm install
npm run dev
```

浏览器打开 <http://localhost:5173>。Vite 已配好 `/api → http://localhost:8080` 代理，**所以后端要先起来**，否则登录和列表全部报错。

### 2.4 联调说明

- 开发态保持 base 为默认 `/`，**不要在 dev 时设置 `VITE_BASE`**——否则请求路径变成 `/liteark/api/...`，匹配不上 vite 的 `/api` 代理规则
- 前端改动热更新；后端改 Java 需重启（可自行引入 `spring-boot-devtools`）
- 此时访问 `http://localhost:8080` 会 404：`src/main/resources/static/` 是构建注入目录，没跑过前端构建就是空的，属正常现象，开发期一律走 5173

`npm install` 若报 `EALLOWREMOTE` 或试图拉取镜像站地址，在 `web/.npmrc` 固定官方源后删掉旧 lockfile 重装：

```
registry=https://registry.npmjs.org/
allow-remote=all
```

### 2.5 构建 fat jar

```bash
cd web && npm run build && cd ..
rm -rf src/main/resources/static/*
cp -R web/dist/. src/main/resources/static/
mvn clean package -DskipTests
# 产物：target/liteark.jar（约 60MB，前端 dist + 全部依赖都在里面）
```

两个容易踩的坑：

- **必须带 `clean`**。只跑 `mvn package` 会保留 `target/classes/static/` 里上一次的前端产物，新旧 chunk 一起打进 jar，表现为「改了页面却没生效」。
- `cp -R web/dist/.` 结尾的 `/.` 别省。写成 `web/dist/*` 会漏掉以点开头的文件。

## 三、生产部署

### 3.1 根路径部署（内网推荐）

```bash
# 前提：仅预装 OpenJDK 1.8（java -version 显示 1.8.x）
mkdir -p /opt/liteark/{conf,data,logs}
cp liteark.jar /opt/liteark/
cp application.yml /opt/liteark/conf/     # 来自 deploy/conf/application.yml

# 启动（外置配置优先于 jar 内置配置）
java -jar /opt/liteark/liteark.jar \
  --spring.config.location=/opt/liteark/conf/application.yml
```

首次启动自动完成：创建数据目录、建表、初始化角色与账号。浏览器访问 `http://服务器IP:8080`。

### 3.2 子路径部署（如 `https://example.com/liteark/`）

前端资源与接口路径全部跟随 vite `base`，构建时指定即可，无需改代码：

```bash
cd web
VITE_BASE=/liteark/ npm run build      # 不带该变量则默认部署在根路径 `/`
cd ..
rm -rf src/main/resources/static/* && cp -R web/dist/. src/main/resources/static/
mvn clean package -DskipTests
```

反向代理把 `/liteark/` 转发到本服务（服务本身仍监听根路径，由代理去掉前缀）：

```nginx
location /liteark/ {
    proxy_pass http://127.0.0.1:8080/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    client_max_body_size 2048m;
    proxy_request_buffering off;      # 大文件上传流式转发
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;
}
```

> 注意：`proxy_pass` 末尾的 `/` 不能省，否则前缀不会被剥离。

### 3.3 初始账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 超级管理员 |
| demo  | 123456   | 普通用户 |

**部署后第一步：修改 admin 密码，并把外置配置中的 `liteark.jwt-secret` 改为长随机字符串。**

## 四、配置

配置分三层，优先级从高到低：

1. 命令行参数：`java -jar liteark.jar --server.port=9090 --liteark.jwt-secret=xxx`
2. 外置配置：`--spring.config.location=/opt/liteark/conf/application.yml`
3. jar 内置配置：`src/main/resources/application.yml`

关键配置项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | 8080 | 服务端口（环境变量 `LITEARK_PORT`） |
| `liteark.data-dir` | ./data | 数据根目录，数据库与文件实体都在此下 |
| `liteark.files-dir` | {data-dir}/files | 文件实体目录 |
| `liteark.temp-dir` | {data-dir}/temp | 临时目录 |
| `liteark.jwt-secret` | 开发默认值 | **生产必须替换为长随机串** |
| `liteark.default-quota` | 21474836480 | 新用户默认配额（20GB） |
| `liteark.max-file-size` | 2147483648 | 单文件上限（2GB） |
| `liteark.conflict-strategy` | rename | 同名冲突策略：rename / skip / overwrite |
| `liteark.admin-ip-whitelist-enabled` | false | 后台 IP 白名单开关 |

> 注意：数据库路径由 `liteark.data-dir` 统一决定（数据源 URL 引用它），改 data-dir 即可整体迁移数据目录。目录不存在时应用会在启动前自动创建。

完整配置说明见 `docs/配置说明.md`。

## 五、功能概览

**前台网盘**：登录/注册、目录树、文件/文件夹增删改查、复制/剪切/粘贴（含文件夹递归）、回收站与还原、小文件直传与**大文件分片断点续传**（暂停/继续/取消、刷新续传、不重传已完成分片）、移动端拍照/相册上传、单文件与批量 ZIP 下载、列表/网格视图、搜索、文本在线编辑（30s 自动保存、历史版本与回滚）、图片缩略图（png/jpg/gif/webp/bmp/svg）/ PDF / 音视频（支持拖动进度）/ Markdown / CSV / Office 只读预览、中文与特殊字符文件名全程无乱码、共享链接（权限/密码/有效期/下载次数/访问记录）、**文件夹自助授权**（把自己的文件夹按查看/编辑/下载/上传授权给同事，递归生效，「我的分享」集中管理并可随时撤销）。

**管理后台**：系统概览、用户管理、角色管理（每个角色可维护**默认文件夹权限**，角色下用户自动继承）、**文件夹级细化权限**（查看/编辑/下载/上传四项，授权对象默认角色，也可对具体用户「直接指定」或「按角色授权」，递归生效、上级包含下级、支持阻断继承）、共享管控、操作日志（分页筛选 + CSV 导出）、系统配置。

**运维**：回收站/分片/过期共享/日志自动清理、后台 IP 白名单、可选接口文档（默认关闭）。

**三端响应式**：桌面（侧栏+列表）、平板（抽屉菜单）、移动（单列+底部 Tab+悬浮上传按钮），断点 320–1920px。

详细功能清单见 `docs/功能说明.md`；分册文档：`docs/网盘功能说明.md`（文件、上传下载、预览、共享）、`docs/权限说明.md`（权限模型、角色默认权限、按角色授权）。需求原文见 `LiteArk需求-V1.1.md`。

## 六、数据目录结构

```
/opt/liteark/
├── liteark.jar              # 单包交付
├── conf/application.yml     # 外置配置
├── data/
│   ├── liteark.db           # SQLite 数据库（含 WAL）
│   ├── files/{用户ID}/      # 文件实体，按用户隔离
│   └── temp/                # 临时文件
└── logs/liteark.log         # 运行日志
```

备份只需打包 `data/` 目录；迁移只需拷贝 jar + conf + data 到新机器。

## 七、安全建议

- 生产环境务必修改 `liteark.jwt-secret` 与初始账号密码
- 后台 `/admin/**` 可在 Nginx 层追加 IP 白名单，或设置 `liteark.admin-ip-whitelist-enabled=true`（已生效，拦截 `/admin/**` 与 `/api/admin/**`）
- 文件实体按用户 ID 隔离存储，接口层统一做文件夹权限校验：所有者与被授权者按各自有效权限访问，未被授权的他人目录一律 403 / 列表不可见
- OpenAPI/swagger-ui 默认关闭，内网如需开启设置 `LITEARK_API_DOCS=true`
- Office 只读预览双通道：装有 LibreOffice（含 headless）时转 PDF 高保真预览；未装时 docx/xlsx/pptx 自动用 Apache POI 渲染只读 HTML（文字/表格可读，版式简化），旧版 doc/xls/ppt 仍需 LibreOffice。PDF 由浏览器内置阅读器内联显示
- 预览接口支持 HTTP Range，音视频/大 PDF 可拖动进度；HTML/SVG 内联预览带 CSP sandbox，脚本不会执行

## 八、本版本边界

V1.1 曾列出的 10 项未实现能力已补齐 9 项（分片续传、Office 转码只读预览、细粒度 RBAC、版本回滚、自动清理、移动端编辑、共享下载限制、日志导出、IP 白名单/接口文档）。剩余边界：OnlyOffice 在线编辑（需求 Q-11 待定，本期不引入）、文本存储备选模式、amd64/arm64 实机验证记录。详见 `docs/功能说明.md` 的"需求全量对齐与剩余边界"。

## 九、许可证

[MIT](LICENSE) © 2026 BKChuTang

# InfraPilot · Ext JS 原生前端

使用官方 **Ext JS 7.0 Modern / Material** 实现 49 个功能页面及登录页。没有 React、ExtReact、Vue、Ant Design 或自定义组件兼容层；业务代码直接使用 Ext.application、ViewController、Store 和 Ext JS 组件。

仓库默认 Docker Compose 前端使用本目录。`npm run demo` 仍连接本机隔离演示后端，不修改现网 DNS、DHCP 或网关；旧 Vue 前端源码保留在 `frontend/`，不再用于默认部署。

## 原生界面

| 部分 | Ext JS 组件与布局 |
| --- | --- |
| 应用、页面与导航 | Application / ViewController / Viewport / Panel / Tree |
| 页面排版 | fit / hbox / vbox / docked / flex |
| 统计与列表 | Displayfield / Grid / Store / 原生可变行高 |
| 操作与帮助 | Toolbar / Button / Menu / Menuitem / Tooltip / MessageBox |
| 编辑与预览 | FormPanel / FieldSet / TextField / SelectField / TextArea / Dialog |
| 手机导航与编辑 | 原生浮动 Panel、modal mask 和最大化 Dialog |

字体、配色、留白、焦点、选择状态和控件外观全部由 Material 官方浅色主题提供。**没有应用自定义 CSS、主题覆盖、DOM 布局补丁、React portal 或字段同步补丁。** 登录页及全部工作页面统一使用浅色，移除主题切换及跟随系统逻辑，并清除旧主题偏好。49 个导航项各有不同的 SDK 内置图标。

页面采用原生面板标题、停靠工具栏、填满剩余空间的滚动表格和底部状态栏。保留地址表单按基本设置、网络与 DNS、网络引导、高级选项分组。窄屏使用原生操作菜单，编辑窗全屏显示，表单内部滚动。

## 启动

需要 Node.js 22.12+、Java 21+、Maven、Python 3.11+。

```sh
# 仓库根目录
mvn -f backend/pom.xml clean package -DskipTests
cd frontend-extjs
npm ci
npm run sdk
npm run demo
```

打开 <http://127.0.0.1:5181>，点击「填入演示账户」。后端监听 `127.0.0.1:18085`，使用内存数据库及临时工作目录；Ctrl+C 同时关闭前后端。可以通过 `JAVA_HOME` 指定 Java。

已有官方 SDK 压缩包时：

```sh
python3 scripts/setup-sdk.py --archive /path/to/ext-7.0.0-gpl.zip
```

下载脚本使用固定官方 URL 和 SHA-256 校验，仅安装同一 SDK 的 Modern 运行时及 Material 主题。SDK、字体与许可文件在忽略提交的 `public/vendor/ext/`，不混用其他版本的适配包。构建产物保留官方 GPL 许可文件。

已单独启动后端时运行 `npm run dev`，通过 `API_PROXY_TARGET` 指定后端地址。`npm run build` 生成独立静态站点；Java API 的权限、CSRF、版本检查和变更预览流程保持不变。

## 部署

仓库根目录执行 `docker compose up -d --build`，通过本目录的多阶段 Dockerfile 安装固定 SDK 并构建前端。已有本地构建产物时，可用 `Dockerfile.runtime` 打包 `dist/`，避免宿主机重复安装 Node.js 和下载 SDK。前端通过 Nginx 的 `/api/` 代理连接现有 `backend:8080`，不在浏览器存放生产接入密钥。

发布前保留旧前端镜像和 Compose 配置；使用 `docker compose up -d --no-deps frontend` 仅切换前端。应用回滚不覆盖数据库、DNS、DHCP 或网关配置。

## 功能

- DNS：区域切换、记录增删改、多值与 TTL、系统记录保护、主从查询对比、导出。
- DHCP：子网与地址池、保留地址及完整选项、继承值、租约和 HA；已保留客户端禁用重复保留。
- 条件转发：DNS / DoT 编辑、双节点预览、备份与恢复。
- BIND：权威区域、递归与缓存、ACL / Views、DNSSEC / 密钥、加密传输、日志统计、原生配置和运维；包含主从区域创建、TSIG 生成、配置导出、备份与执行结果。
- Application Gateway：13 类资源使用原生分组表单：节点、匹配条件、超时、健康检查、TLS、消费者凭据及插件参数均可逐项编辑；节点配置只读，保留后端校验与备份恢复。
- 登录、管理员/只读权限、操作审计。

这次重写不修改服务端业务、生产权限或数据。演示验证不等于所有 BIND 命令、网关插件组合或生产恢复流程均已通过现网验收。

## 验证

保持演示服务运行后执行：

```sh
npm run build
npm test             # 表单数据类型与密钥标记
npm run test:forms    # 网关表单、无损编辑及手机布局
npm run test:e2e      # Google Chrome
npx playwright install webkit
npm run test:webkit
```

测试在 `meta.demo=true` 时才执行写入，覆盖 DNS、保留地址及 DNS/网关选项、条件转发、BIND 配置、网关资源、各页面加载、表格边界、固定浅色主题、原生手机菜单和全屏编辑窗。可用 `PREVIEW_URL`、`SCREENSHOT_DIR` 指定地址与截图目录。WebKit 自动化不等同于 iPhone 真机验收。

参考：[Material 主题](https://docs.sencha.com/extjs/7.0.0/guides/core_concepts/material_theme.html)、[官方 GPL SDK](https://cdn.sencha.com/ext/gpl/ext-7.0.0-gpl.zip)。

UPS 集成使用官方 Sencha Charts 原生图表及原生表单；接口与验证说明见 [UPS.md](../docs/UPS.md)。

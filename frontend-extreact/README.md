# InfraPilot · ExtReact 原生组件预览

独立 React + ExtReact 前端，共 33 个功能页面及登录页。布局、导航、表格、表单、弹窗和提示均由原生 ExtReact 组件渲染；不加载 Vue、Ant Design 或 Element UI。使用 Material 主题默认配色，支持浅色、深色和跟随系统。

这是可运行的兼容性预览，尚未替换 `frontend/` 或生产站点。默认连接本机隔离演示后端，不会修改现网 DNS、DHCP 或网关。

## 组件

| 部分 | 原生组件 |
| --- | --- |
| 工作空间、响应式布局 | Container / Viewport |
| 导航和手机抽屉 | Treelist / Panel / 原生 modal mask |
| 统计与页面面板 | Panel / Component |
| 列表、排序、选择、列宽 | Grid |
| 操作栏、按钮、帮助 | Toolbar / Button / Tooltip / Dialog |
| 编辑器、选择与搜索 | Formpanel / Textfield / Textareafield / Selectfield / Searchfield |
| 校验、变更预览、备份、操作结果 | Dialog / Grid / 原生只读文本域 |

`src/ui.jsx` 只适配原生组件事件、值同步和生命周期。静态标题及表格单元格使用 Component 的 HTML 模板或 Grid renderer；交互控件、布局、遮罩和弹窗没有自行用 HTML 模拟。

## 启动

需要 Node.js 22.12+、Java 21+、Maven、Python 3.11+。

```sh
# 从仓库根目录执行
mvn -f backend/pom.xml clean package -DskipTests
cd frontend-extreact
npm ci
npm run sdk
npm run demo
```

打开 <http://127.0.0.1:5181>，点击「填入演示账户」。后端监听 `127.0.0.1:18085`，使用内存数据库和临时工作目录；Ctrl+C 同时关闭前后端。需要指定 Java 时，在启动命令前设置正确的 `JAVA_HOME`。

已下载 SDK 时，可以避免重新下载：

```sh
python3 scripts/setup-sdk.py --archive /path/to/ext-7.0.0-gpl.zip
```

如果后端已单独运行，执行 `npm run dev`。通过 `API_PROXY_TARGET` 指定后端，例如 `http://127.0.0.1:18085`；生产 API 的权限、版本校验、预览和 CSRF 仍由原 Java 服务实施。浏览器验证脚本只接受 `meta.demo=true` 的后端。

## 页面和流程

- DNS：区域切换、常规记录增删改、多值与 TTL、系统记录保护、查询对比、导出。
- DHCP：子网、地址池、保留地址完整选项、继承值、租约、HA；已保留客户端禁用重复保留操作。
- 条件转发：DNS / DoT 规则编辑、双节点预览、备份列表与恢复。
- BIND：权威区域、递归与缓存、ACL / Views、DNSSEC / 密钥、加密传输、日志统计、原生配置与运维。支持主从区域创建、原生配置模板、TSIG 生成、配置导出、备份恢复和执行结果。
- Application Gateway：13 类资源、插件 Schema、节点配置和备份恢复。复杂配置使用原生 Textareafield 编辑 JSON，交由后端校验。
- 操作审计、会话登录、管理员权限和只读保护。

本预览复用 Java API，没有迁移生产数据或修改服务端权限。上述管理入口已实现，但不代表所有 BIND 命令、APISIX 插件组合或生产恢复流程都经过现网验收。

## SDK 与版本

- `@sencha/ext-react-modern` **7.5.0**：官方 ExtReact 包，MIT 包许可。
- React / React DOM **16.13.1**：遵循该官方包声明的 peer dependencies。
- 当前本地预览使用官方 **Ext JS 7.0 GPL** Modern / Material 运行时。下载脚本固定官方 URL，并核对 SHA-256；运行时、第三方许可及字体位于忽略提交的 `public/vendor/ext/`。

这是用于试看的跨版本组合，**不应视为 Sencha 官方支持的生产版本组合**。ExtReact 包本身不代替 Ext JS SDK 许可；正式迁移需使用有相应使用权、版本匹配的 SDK，并重新验证功能。原项目的许可证和生产发布流程没有因此改变。

依据：[ExtReact npm 安装说明](https://docs.sencha.com/extreact/7.2.0/guides/using_npm/npm_help.html)、[Sencha 开源说明](https://www.sencha.com/legal/open-source-faq/)、[官方 GPL SDK](https://cdn.sencha.com/ext/gpl/ext-7.0.0-gpl.zip)。

## 验证

保持演示服务运行，在另一个终端执行：

```sh
npm run build
npm run test:e2e      # 使用已安装的 Google Chrome
npx playwright install webkit
npm run test:webkit
```

可通过 `PREVIEW_URL` 和 `SCREENSHOT_DIR` 指定预览地址和截图输出位置。测试拒绝向生产后端写入，覆盖登录、DNS 增删改、保留地址网关 / DNS 设置、网关配置提交、条件转发、BIND 原生配置、各页面加载、主题记忆和手机导航。WebKit 自动化不等于 iPhone 真机验收。

构建时官方 ExtReact 的适配层会产生 `eval` 警告；本目录没有关闭该警告，也未声称支持禁用 `unsafe-eval` 的严格 CSP。正式 SDK 和发布策略应另行核验。

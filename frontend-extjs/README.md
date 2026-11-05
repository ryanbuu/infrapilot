# InfraPilot · Ext JS 原生前端

使用官方 **Ext JS 7.0 Modern / Material** 实现 33 个功能页面及登录页。没有 React、ExtReact、Vue、Ant Design 或自定义组件兼容层；业务代码直接使用 Ext.application、ViewController、Store 和 Ext JS 组件。

当前是独立预览，尚未替换仓库中的正式 `frontend/` 或生产站点。默认连接本机隔离演示后端，不修改现网 DNS、DHCP 或网关。

## 原生界面

| 部分 | Ext JS 组件与布局 |
| --- | --- |
| 应用、页面与导航 | Application / ViewController / Viewport / Panel / Tree |
| 页面排版 | fit / hbox / vbox / docked / flex |
| 统计与列表 | Displayfield / Grid / Store / 原生可变行高 |
| 操作与帮助 | Toolbar / Button / Menu / Menuitem / Tooltip / MessageBox |
| 编辑与预览 | FormPanel / FieldSet / TextField / SelectField / TextArea / Dialog |
| 手机导航与编辑 | 原生浮动 Panel、modal mask 和最大化 Dialog |

字体、配色、留白、焦点、选择状态和控件外观全部由 Material 主题提供。**没有应用自定义 CSS、主题覆盖、DOM 布局补丁、React portal 或字段同步补丁。** 深浅色与跟随系统使用 Material 原生 `setDarkMode`。33 个导航项各有不同的 SDK 内置图标。

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

下载脚本使用固定官方 URL 和 SHA-256 校验，仅安装同一 SDK 的 Modern 运行时及 Material 主题。SDK、字体与许可文件在忽略提交的 `public/vendor/ext/`，不混用其他版本的适配包。当前为 GPL 预览运行时；正式发布须遵循相应 SDK 的许可要求。

已单独启动后端时运行 `npm run dev`，通过 `API_PROXY_TARGET` 指定后端地址。`npm run build` 生成独立静态站点；Java API 的权限、CSRF、版本检查和变更预览流程保持不变。

## 功能

- DNS：区域切换、记录增删改、多值与 TTL、系统记录保护、主从查询对比、导出。
- DHCP：子网与地址池、保留地址及完整选项、继承值、租约和 HA；已保留客户端禁用重复保留。
- 条件转发：DNS / DoT 编辑、双节点预览、备份与恢复。
- BIND：权威区域、递归与缓存、ACL / Views、DNSSEC / 密钥、加密传输、日志统计、原生配置和运维；包含主从区域创建、TSIG 生成、配置导出、备份与执行结果。
- Application Gateway：13 类资源、插件 Schema、节点配置及备份恢复；复杂配置通过原生文本域编辑 JSON，由后端校验。
- 登录、管理员/只读权限、操作审计。

这次重写不修改服务端业务、生产权限或数据。演示验证不等于所有 BIND 命令、网关插件组合或生产恢复流程均已通过现网验收。

## 验证

保持演示服务运行后执行：

```sh
npm run build
npm run test:e2e      # Google Chrome
npx playwright install webkit
npm run test:webkit
```

测试在 `meta.demo=true` 时才执行写入，覆盖 DNS、保留地址及 DNS/网关选项、条件转发、BIND 配置、网关资源、各页面加载、表格边界、主题记忆、原生手机菜单和全屏编辑窗。可用 `PREVIEW_URL`、`SCREENSHOT_DIR` 指定地址与截图目录。WebKit 自动化不等同于 iPhone 真机验收。

参考：[Material 主题](https://docs.sencha.com/extjs/7.0.0/guides/core_concepts/material_theme.html)、[官方 GPL SDK](https://cdn.sencha.com/ext/gpl/ext-7.0.0-gpl.zip)。

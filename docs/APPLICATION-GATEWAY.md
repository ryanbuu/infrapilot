# Application Gateway

使用 APISIX Admin API 维护动态资源，前端通过 InfraPilot 后端访问。实现按 APISIX 3.13 的实际 API 验证；其他版本以实例返回的 Schema 和能力为准。官方参考：[3.13 Admin API](https://apisix.apache.org/docs/apisix/3.13/admin-api/)。

## 管理范围

| 页面        | 资源 / 功能                                                                            |
| ----------- | -------------------------------------------------------------------------------------- |
| 网关概览    | 实例版本、连接状态、各类资源计数、配置导出                                             |
| 路由        | 域名、URI、方法、优先级、启停、服务/上游引用、内嵌上游、全部匹配和插件参数             |
| 服务 / 上游 | 引用与内嵌节点、权重、均衡策略、协议；JSON 支持超时、健康检查、TLS、服务发现等完整字段 |
| 消费者      | 用户、认证插件、描述及分组引用                                                         |
| 证书        | SNI、证书和私钥；JSON 保留多证书、mTLS 等字段                                          |
| 插件        | 当前实例实际加载的插件，实时查询配置 Schema                                            |
| 高级配置    | 消费者组、消费者独立凭据、全局规则、插件配置/元数据、TCP/UDP 路由、Protobuf、Secret    |
| 节点配置    | 只读展示实际挂载的 config.yaml，管理凭据和加密材料隐藏                                 |
| 变更备份    | 最近 200 份资源变更前后的元数据、恢复前值；原始备份文件保留完整字段                    |

基础表单与完整 JSON 编辑共享配置，未知字段保留。需要复杂插件参数、匹配变量、标签、TLS 或服务发现时切换 JSON。资源 ID 不可通过更新操作重命名；复制资源时使用新 ID，并重新填写隐藏的敏感值。

TCP/UDP 功能未开启时，展示实例的实际状态；不会为展示页面自动变更转发模式、监听端口或重启 APISIX。节点启动配置通过部署文件维护，此处不提供运行参数的写入和热重启。APISIX 的 etcd/系统管理、二进制升级及外部证书签发不属于 Admin API 动态资源。

## 接入

在私密 .env 中设置：

```dotenv
INFRAPILOT_GATEWAY_ENABLED=true
INFRAPILOT_GATEWAY_URL=http://192.0.2.20:9180
INFRAPILOT_GATEWAY_API_KEY=replace-with-admin-key
```

URL 可为实例根地址或以 /apisix/admin 结尾。网关地址固定在后端配置中，浏览器无法指定代理目标。后端不跟随重定向，不自动重试写请求，限制响应和配置大小。生产中应使 Admin API 只对授权管理节点开放。

可选挂载节点 config.yaml，保留原有 Compose volume 并追加：

```yaml
services:
  backend:
    environment:
      INFRAPILOT_GATEWAY_RUNTIME_CONFIG_FILE: /app/gateway/config.yaml
    volumes:
      - /path/to/apisix/config.yaml:/app/gateway/config.yaml:ro
```

挂载文件须允许应用 UID 10001 读取。管理凭据和私钥不进入仓库或前端构建。所有 /api/gateway 端点要求管理员身份，写入、预校验、恢复和导出同时要求 CSRF。只读账号继续访问原 DNS/DHCP 查询功能。

## 变更与恢复

1. 读取资源时生成完整原始节点摘要，包含资源版本。
2. 预览时重新检查摘要、解析敏感值保留标记并调用实例的 Schema 预校验。
3. 提交时再次检查版本和 Schema，将完整前后值写入私密文件，完成文件落盘后创建 PENDING 审计。
4. 执行 PUT/DELETE，回读确认内容后标记 SUCCESS。拒绝请求记录 FAILED；网络、服务端或回读结果不明时保留 PENDING，刷新后核查实际状态。
5. 恢复会先展示当前值和备份前值，检查当前版本，保存一份当前状态备份，再执行恢复。

APISIX 3.13 的 Admin API 不提供原子条件写。应用内串行提交并检查编辑版本，仍无法消除其他管理端在检查与写入之间产生的竞态；同一对象应采用一个管理入口进行变更。删除保持 APISIX 自带引用检查，不开放 force 参数。含独立凭据的消费者需先显式删除或迁移凭据，避免消费者删除隐式级联而失去独立恢复入口。

Schema 预校验不等同于完整语义检查；引用关系、证书/私钥匹配和特定插件约束可能在真正写入时被拒绝。APISIX 3.13 的通用预校验接口对 Secret 和插件元数据返回 500，应用对这两类使用实际资源接口的专属校验，并在预览中明确显示「保存时校验」。界面会显示拒绝结果，不将其标成成功。

变更文件默认在持久化 audit-data 卷的 /app/data/application-gateway 中（目录 0700、文件 0600），可通过 INFRAPILOT_GATEWAY_BACKUP_DIRECTORY 修改。应与数据库、私密部署配置一同备份。备份与网关配置不是分布式事务；审计落盘失败会阻止新的写请求，写入后审计异常需要人工核查。

APISIX 3.13 单条 SSL 查询不返回主私钥，集合返回的是实例加密后的私钥。应用使用集合中的存储值保留私钥；新私钥经 APISIX 校验证书匹配并加密后，回读验证公有配置及私钥存在。导出包含敏感值时，证书私钥仍可能是 APISIX 加密形式，跨实例恢复需要原实例的加密配置。节点配置的加密材料在界面和导出中始终隐藏，应另行保存私密部署备份。

导出默认脱敏，可显式选择包含动态资源敏感字段；导出有审计。导出 JSON 包含资源种类、ID、凭据所属消费者和完整值，节点配置始终只读且脱敏。批量跨实例导入需自行处理依赖顺序；界面当前提供单资源完整 JSON 编辑和逐次变更恢复。

## API

统一前缀 /api/gateway，沿用 Session 和 CSRF。

| 方法 / 路径                    | 用途                                                                     |
| ------------------------------ | ------------------------------------------------------------------------ |
| GET /                          | 版本、资源统计与能力                                                     |
| GET /resources/{kind}?parent=  | 资源列表；credentials 的 parent 为消费者名                               |
| POST /resources/{kind}/preview | {action, id, parent?, revision?, value?}，action 为 CREATE/UPDATE/DELETE |
| POST /resources/{kind}/changes | 提交同一结构，更新/删除必须带当前 revision                               |
| GET /plugins                   | 已加载插件                                                               |
| GET /schema/{kind}?plugin=     | 资源或指定插件 Schema                                                    |
| GET /runtime                   | 脱敏只读节点配置                                                         |
| GET /backups                   | 备份元数据                                                               |
| POST /backups/{id}/preview     | 预览备份前值恢复                                                         |
| POST /backups/{id}/restore     | {revision}，使用恢复预览返回的版本                                       |
| POST /export                   | {includeSecrets:false}，导出配置并记录审计                               |

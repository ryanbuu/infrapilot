# API

所有接口以 `/api` 开头。使用服务端 Session；前端与 API 通过同域代理访问，不把凭据放进 URL 或 localStorage。

| 方法 | 路径                                | 用途                                                    |
| ---- | ----------------------------------- | ------------------------------------------------------- |
| GET  | `/meta`                             | 公开的版本与演示模式标记                                |
| GET  | `/csrf`                             | 获取 `token` 和 `headerName`，写请求必须带对应头        |
| POST | `/login`                            | 表单编码的 username/password；成功后重新获取 CSRF token |
| POST | `/logout`                           | 清理会话                                                |
| GET  | `/session`                          | 当前用户名与 admin 标记                                 |
| GET  | `/zones`                            | 区域摘要，各区域独立报告读取失败                        |
| GET  | `/zones/{id}/records`               | 区域及 RRset 列表，包含 revision/editable/protection    |
| POST | `/zones/{id}/records`               | 新增记录集，管理员权限                                  |
| PUT  | `/zones/{id}/records`               | 编辑记录集，管理员权限，必须带 expectedRevision         |
| POST | `/zones/{id}/records/delete`        | 删除整个记录集，管理员权限，必须带 expectedRevision     |
| GET  | `/zones/{id}/probe?name=nas&type=A` | 查询配置好的主从节点                                    |
| GET  | `/audit?offset=0&limit=50`          | 管理员分页查看审计，limit 最大 100                      |

新增/编辑 JSON：

```json
{
  "name": "nas",
  "type": "A",
  "ttl": 300,
  "values": ["192.0.2.10", "192.0.2.11"],
  "expectedRevision": "编辑时填写读取结果中的 revision；新增时省略"
}
```

名称可以是 `@`、相对名称或带末尾点的完整名称。域名类 RDATA 以 DNS 文本格式解析，相对名称会补全当前区域；TXT 要带引号。编辑不能修改名称或类型，需要新建后删除旧记录集。

删除 JSON：

```json
{ "name": "nas", "type": "A", "expectedRevision": "读取结果中的 revision" }
```

响应 `{ "auditId": 123 }` 表示 DNS 更新及成功审计均已确认；使用主从查询检查副本是否完成同步。修改的是整个同名同类型 RRset，删除亦是整组删除，不是只删其中一个值。

错误：400 输入无效，401 未登录，403 权限/CSRF/受保护记录，404 区域不存在，409 并发冲突，502 DNS 拒绝或结果未确认，503 DNS 已更新但审计最终状态未完成。超时后先读实际 DNS，不要盲目重发。

所有变更审计先写 PENDING 再执行 DNS；确认后改 SUCCESS，明确失败改 FAILED；网络超时或响应签名校验失败则保持 PENDING，待查询实际记录核实。应用退出或数据库在最终落盘时失效，可能留下 PENDING，需要人工核实，不能等同于“未更新”。

## DHCP

- `GET /api/dhcp`：节点、子网、保留地址、合并租约、配置版本和可写状态。需要登录。
- `POST /api/dhcp/preview/{operation}`：双节点预校验，需要管理员和 CSRF。
- `POST /api/dhcp/changes/{operation}`：保存并持久化，需要管理员和 CSRF。
- `POST /api/dhcp/leases`：释放租约或重发 DDNS，需要管理员和 CSRF。

配置 operation 为 `subnet-save`、`subnet-delete`、`reservation-create`、`reservation-save`、`reservation-delete`。请求结构为 `{revision, subnetId, values}`。租约请求为 `{revision, address, action}`，action 为 `release` 或 `resend-ddns`。保留地址 `values.dnsMode` 为 `static` 或 `dynamic`；新建同一子网已存在的 MAC 返回 409。租约含 `reserved`、`reservationAddress`、`reservationDnsMode` 和 `reservationFqdn`，原 `hostname` 保留节点实际返回值。静态维护设备拒绝重发 DDNS，释放时保留静态解析。不开放原始 Kea 命令透传。具体限制见 [DHCP 管理说明](DHCP.md)。

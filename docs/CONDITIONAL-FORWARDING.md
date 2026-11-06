# 条件转发

入口：**DNS → 条件转发**。这里管理域名及其子域的转发策略，与「解析记录」中的权威区域分开。

支持 DNS（UDP / TCP）和 DoT、IPv4 / IPv6 上游、统一端口、TLS 校验域名、`forward only` / `forward first`、备注、搜索、提交前双节点校验、增删改和备份恢复。DoT 使用系统 CA 证书校验上游，必须配置校验域名。选择「优先转发」可能在上游不可用时绕过上游策略进行递归解析。

## 接入

后端通过 OpenSSH 访问两台节点的专用受限命令。不需要在 BIND 上新增 HTTP 服务，浏览器也不会接触 SSH 凭据。

1. 管理员在两台节点安装 root 所有的 `deploy/forwarding/agent.py` 和 `import_config.py` 到 `/usr/local/libexec/infrapilot-forwarding/`。
2. 分别运行 `python3 /usr/local/libexec/infrapilot-forwarding/import_config.py --id bind1`、`--id bind2`。该操作备份主配置，并将原有、各 view 相同的条件转发区接入专用 include。脚本比较展开后配置，拒绝改变任何非转发配置。已有不支持的转发/TLS 选项或不同 view 的差异会停止接入，不能静默丢弃。
3. 为面板生成独立 Ed25519 密钥。节点 `authorized_keys` 中的专用公钥必须限制源地址、禁止转发/PTY/用户 rc，并固定执行代理：

   ```text
   restrict,from="192.0.2.20",command="/usr/bin/python3 -I /usr/local/libexec/infrapilot-forwarding/agent.py" ssh-ed25519 <PUBLIC_KEY> infrapilot-forwarding
   ```

   管理密钥不得复用为面板凭据。私钥仅给 Java 容器的 UID 10001 读取；只读挂载到 `/app/config/forwarding/`。通过可信管理员通道收集节点公钥，配置严格的 `known_hosts`，不能关闭 SSH 主机校验。
4. 在私有 `deploy/config/application.yml` 中配置：

   ```yaml
   infrapilot:
     forwarding:
       enabled: true
       private-key-file: /app/config/forwarding/id_ed25519
       known-hosts-file: /app/config/forwarding/known_hosts
       nodes:
         - { id: bind1, label: BIND 1, host: 192.0.2.10, port: 22, user: root }
         - { id: bind2, label: BIND 2, host: 192.0.2.11, port: 22, user: root }
   ```

   Compose 使用 `INFRAPILOT_FORWARDING_ENABLED=true`；备份路径默认为持久卷 `/app/data/conditional-forwarding`。标准后端镜像包含 `openssh-client`，应用仍以非 root 用户运行。

## 保存与恢复

每次保存先读取两台节点的实际配置和版本。两个节点必须在线、规则一致、无外部修改和未完成事务。新规则不能覆盖、位于或包含现有本地权威区域。

两个代理分别生成配置代次并使用 `named-checkconf` 校验整个主配置。预览不切换代次，不重载 BIND。保存时先持久化审计和事务备份，再准备两个节点，逐台切换代次和执行 `rndc reconfig`，最后回读规则并完成事务。管理操作只写专用转发目录，不提供任意文件或命令执行接口。

两个独立 BIND 进程之间没有原子的同时重载：逐台提交期间存在短暂差异。失败时协调恢复两个节点，响应丢失也会尝试回滚。不能确认恢复时保留事务并暂停新写入，不能假报成功。后端重启后的首次访问会继续收尾已提交事务，或回滚未完成事务；也可以点击「重试恢复」。代理不自行超时撤销，避免在协调器提交中途撤销准备状态。

「变更备份 → 恢复变更前配置」会创建一次新的双节点事务，并再次备份当前值。恢复的是整份转发规则集，预览会显示完整前后配置。应用审计和事务备份应与共享 PostgreSQL、`audit-data` 卷、私有部署配置一起备份。各 BIND 节点还保存独立的配置代次和本地事务日志。

外部直接修改受管文件、增加未接入的转发区或改变 include 结构时，写入会被阻止，需管理员核对接入。现有接口不自动覆盖差异节点，也不编辑全局转发、ACL、权威区域、TSIG、DNSSEC、主从或动态更新权限。

普通用户可查看规则和节点状态；新增、预览、修改、删除、恢复和备份查看均要求管理员，写接口要求 CSRF。

## 验证

```sh
python3 -m unittest discover -s deploy/forwarding -p 'test_*.py' -v
mvn -f backend/pom.xml verify
npm --prefix frontend run test:e2e -- --grep 'conditional forwarding'
```

覆盖独立代理的离线准备/提交/恢复、外部修改、配置注入、权威区保护；Java 协调器的丢失响应、节点失联、重启恢复、并发版本检查和权限；浏览器的增删改、备份恢复、深色主题及响应式布局。触摸模拟和 WebKit 测试不能代替真实手机验证。

接口：`GET /api/forwarding`、管理员 `GET /api/forwarding/backups`、`POST /api/forwarding/preview`、`POST /api/forwarding/changes`、`POST /api/forwarding/recover`。

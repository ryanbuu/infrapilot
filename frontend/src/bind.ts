export interface BindFile {
  path: string;
  content: string;
  editable: boolean;
}
export interface BindObject {
  id: string;
  kind: string;
  name: string;
  view: string;
  file: string;
  source: string;
  editable: boolean;
  fields: Record<string, string>;
}
export interface BindCommandField {
  name: string;
  type: string;
  choices?: string[];
  optional?: boolean;
  min?: number;
  max?: number;
}
export interface BindCommand {
  id: string;
  label: string;
  fields: BindCommandField[];
  zone: boolean;
  mutating: boolean;
}
export interface BindNode {
  id: string;
  label: string;
  address: string;
  managedDirectory: string;
  online: boolean;
  error?: string;
  revision: string;
  pending?: { id: string };
  files: BindFile[];
  objects: BindObject[];
  views: string[];
  primaryView: string;
  protectedZones: string[];
  version: string[];
  status: string;
  commands: BindCommand[];
  effective: string;
}
export interface BindState {
  enabled: boolean;
  writable: boolean;
  revision: string;
  error?: string;
  nodes: BindNode[];
}
export interface BindChange {
  revision: string;
  action?: Record<string, unknown>;
  nodes?: {
    id: string;
    files: { path: string; content: string }[];
    assets: unknown[];
  }[];
}
export interface BindPreview {
  valid: boolean;
  nodesValidated: number;
  before: { id: string; files: BindFile[] }[];
  after: { id: string; files: BindFile[] }[];
}
export interface BindBackup {
  id: string;
  name: string;
  createdAt: string;
  actor: string;
  operation: string;
  phase: string;
  before: { id: string; files: BindFile[] }[];
  after: { id: string; files: BindFile[] }[];
}
export const sectionKinds: Record<string, string[]> = {
  zones: ["zone", "primaries", "masters", "remote-servers"],
  resolver: ["options", "server"],
  access: ["acl", "controls", "view"],
  dnssec: [
    "dnssec-policy",
    "trust-anchors",
    "managed-keys",
    "key",
    "keystore",
    "parental-agents",
  ],
  transport: ["tls", "http"],
  logging: ["logging", "statistics-channels"],
  advanced: [],
  operations: [],
};
export const sectionTitles: Record<string, string> = {
  zones: "权威区域",
  resolver: "递归与缓存",
  access: "访问控制与 Views",
  dnssec: "DNSSEC 与密钥",
  transport: "加密传输",
  logging: "日志与统计",
  operations: "运维工具",
  advanced: "原生配置",
};
export const sectionHelp: Record<string, string> = {
  zones:
    "管理区域、主从传送、通知、更新策略、RPZ 与 Catalog Zones。新增主从区域会同步配置两台节点，区域数据保留用于恢复。",
  resolver:
    "递归、缓存、转发、过期应答、QNAME 最小化、响应限速等选项使用当前 BIND 版本的原生语法。",
  access:
    "ACL、查询和递归权限、RNDC 控制通道以及 Views 的匹配与隔离。View 顺序由原生配置顺序决定。",
  dnssec:
    "管理签名策略、信任锚与 TSIG。已有密钥在页面上脱敏，保存时由节点恢复原值；外部使用方的密钥需协调更换。",
  transport:
    "管理 DNS over TLS、DNS over HTTPS、HTTP 通道与 TLS 校验。证书路径须指向节点已有文件。",
  logging:
    "查询统计来自节点本地接口，日志来自 named 服务。dnstap 继续使用现有采集链路与数据库。",
  operations:
    "运行固定参数的管理命令。缓存、签名和验证状态修改只在选定节点生效；持久设置请在配置页面修改。",
  advanced:
    "覆盖当前版本全部 named.conf 语法，包括 RRL、RPZ、Catalog、DLZ、插件和调优选项。配置受节点已安装模块与证书等依赖限制，提交前运行原生校验。条件转发文件在对应页面维护。",
};
export const templates: Record<string, string> = {
  acl: 'acl "trusted-extra" {\n    192.0.2.0/24;\n};',
  view: 'view "isolated" {\n    match-clients { 192.0.2.0/24; };\n    recursion no;\n};',
  zone: 'zone "example.net" {\n    type secondary;\n    primaries { 192.0.2.53; };\n    file "/var/lib/bind/secondary/example.net.zone";\n};',
  options:
    'options {\n    directory "/var/cache/bind";\n    recursion yes;\n    allow-recursion { localhost; };\n    dnssec-validation auto;\n};',
  server: "server 192.0.2.53 {\n    edns yes;\n    request-ixfr yes;\n};",
  "dnssec-policy":
    'dnssec-policy "internal" {\n    keys { csk lifetime unlimited algorithm ecdsap256sha256; };\n    dnskey-ttl 3600;\n    max-zone-ttl 86400;\n};',
  "trust-anchors":
    'trust-anchors {\n    "example.net" static-ds 12345 13 2 "REPLACE_WITH_DS_DIGEST";\n};',
  key: 'key "application.example.net" {\n    algorithm hmac-sha256;\n    secret "REPLACE_WITH_BASE64_SECRET";\n};',
  tls: 'tls "secure-upstream" {\n    remote-hostname "dns.example.net";\n    ca-file "/etc/ssl/certs/ca-certificates.crt";\n};',
  http: 'http "dns-query" {\n    endpoints { "/dns-query"; };\n};',
  logging:
    "logging {\n    channel operations { syslog daemon; severity info; print-time yes; };\n    category default { operations; };\n};",
  "statistics-channels":
    "statistics-channels {\n    inet 127.0.0.1 port 8053 allow { localhost; };\n};",
  primaries: 'primaries "upstream" {\n    192.0.2.53;\n};',
  keystore: 'keystore "local" {\n    directory "/var/lib/bind/keys";\n};',
  dlz: 'dlz "directory" {\n    database "REPLACE_WITH_INSTALLED_DRIVER_AND_SETTINGS";\n};',
};

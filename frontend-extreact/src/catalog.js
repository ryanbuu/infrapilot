export const gatewayKinds = [
  ["routes", "路由"],
  ["services", "服务"],
  ["upstreams", "上游"],
  ["consumers", "消费者"],
  ["ssls", "证书"],
  ["consumer_groups", "消费者组"],
  ["credentials", "消费者凭据"],
  ["global_rules", "全局规则"],
  ["plugin_configs", "插件配置"],
  ["plugin_metadata", "插件元数据"],
  ["stream_routes", "TCP / UDP 路由"],
  ["protos", "Protobuf"],
  ["secrets", "密钥管理"],
];
export const bindSections = [
  ["zones", "权威区域", ["zone", "primaries", "masters"]],
  ["resolver", "递归与缓存", ["options", "server"]],
  ["access", "访问控制与 Views", ["acl", "view", "controls"]],
  [
    "dnssec",
    "DNSSEC 与密钥",
    ["dnssec-policy", "key", "trust-anchors", "keystore", "managed-keys"],
  ],
  ["transport", "加密传输", ["tls", "http"]],
  ["logging", "日志与统计", ["logging", "statistics-channels"]],
  ["operations", "运维工具", []],
  ["advanced", "原生配置", []],
];
export const groups = [
  ["工作空间", [["overview", "网络概览", "tachometer-alt"]]],
  [
    "DNS",
    [
      ["records", "解析记录", "globe"],
      ["forwarding", "条件转发", "random"],
      ...bindSections.map(([id, label]) => ["bind-" + id, label, "cog"]),
    ],
  ],
  [
    "DHCP",
    [
      ["dhcp-subnets", "子网与地址池", "sitemap"],
      ["dhcp-reservations", "保留地址", "server"],
      ["dhcp-leases", "租约", "clock"],
      ["dhcp-ha", "高可用状态", "shield-alt"],
    ],
  ],
  [
    "Application Gateway",
    [
      ["gateway-overview", "网关概览", "exchange-alt"],
      ...gatewayKinds.map(([id, label]) => [
        "gateway-" + id,
        label,
        "angle-right",
      ]),
      ["gateway-plugins", "插件", "puzzle-piece"],
      ["gateway-backups", "变更备份", "history"],
      ["gateway-runtime", "节点配置", "file-code"],
    ],
  ],
  ["管理", [["audit", "操作审计", "list-alt"]]],
];
export const allRoutes = groups.flatMap(([group, items]) =>
  items.map(([id, title, icon]) => ({ id, title, icon, group })),
);
export const recordTypes = [
  "A",
  "AAAA",
  "CNAME",
  "MX",
  "TXT",
  "PTR",
  "SRV",
  "CAA",
  "TLSA",
  "SSHFP",
  "NAPTR",
  "URI",
  "SVCB",
  "HTTPS",
  "DNAME",
  "LOC",
  "RP",
  "HINFO",
  "CERT",
  "OPENPGPKEY",
  "SMIMEA",
  "IPSECKEY",
  "EUI48",
  "EUI64",
  "SPF",
];
export const reservationFields = [
  ["mac", "MAC 地址"],
  ["address", "固定 IP"],
  ["hostname", "主机名"],
  [
    "dnsMode",
    "DNS 维护方式",
    "select",
    [
      { text: "静态维护", value: "static" },
      { text: "动态注册", value: "dynamic" },
    ],
  ],
  ["router", "网关"],
  ["dnsServers", "DNS 服务器"],
  ["domain", "域名"],
  ["searchDomains", "搜索域"],
  ["ntpServers", "NTP 服务器"],
  ["mtu", "MTU"],
  ["nextServer", "引导服务器"],
  ["serverHostname", "引导主机名"],
  ["bootFileName", "引导文件"],
  ["comment", "备注"],
  ["clientClasses", "客户端分类（JSON）", "area"],
  ["extraOptions", "附加选项（JSON）", "area"],
].map(([name, label, kind, options]) => ({ name, label, kind, options }));

export const bindTemplates = {
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

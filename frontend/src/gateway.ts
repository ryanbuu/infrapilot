export type GatewayValue = Record<string, any>;
export interface GatewayItem {
  id: string;
  revision: string;
  value: GatewayValue;
}
export interface GatewayListing {
  kind: string;
  available: boolean;
  message: string;
  items: GatewayItem[];
}
export interface GatewaySummary {
  enabled: boolean;
  name: string;
  version: string;
  endpoint: string;
  demo: boolean;
  plugins: number;
  checkedAt: string;
  resources: {
    kind: string;
    label: string;
    count: number;
    available: boolean;
    message: string;
  }[];
}
export interface GatewayMutation {
  action: "CREATE" | "UPDATE" | "DELETE";
  id: string;
  parent?: string;
  revision?: string;
  value?: GatewayValue;
}
export interface GatewayPreview {
  kind: string;
  id: string;
  action: string;
  revision: string;
  validation: string;
  before: GatewayValue | null;
  after: GatewayValue | null;
}
export interface GatewayBackup {
  id: string;
  kind: string;
  resourceId: string;
  parent: string;
  action: string;
  actor: string;
  createdAt: string;
}
export const gatewayKinds = [
  { id: "routes", label: "路由" },
  { id: "services", label: "服务" },
  { id: "upstreams", label: "上游" },
  { id: "consumers", label: "消费者" },
  { id: "ssls", label: "证书" },
  { id: "consumer_groups", label: "消费者组" },
  { id: "credentials", label: "消费者凭据" },
  { id: "global_rules", label: "全局规则" },
  { id: "plugin_configs", label: "插件配置" },
  { id: "plugin_metadata", label: "插件元数据" },
  { id: "stream_routes", label: "TCP / UDP 路由" },
  { id: "protos", label: "Protobuf" },
  { id: "secrets", label: "密钥管理" },
];
export const gatewayLabel = (kind: string) =>
  gatewayKinds.find((k) => k.id === kind)?.label || kind;
export function gatewayTemplate(kind: string): GatewayValue {
  switch (kind) {
    case "routes":
      return {
        name: "",
        uri: "/*",
        hosts: [],
        status: 0,
        upstream: { type: "roundrobin", scheme: "http", nodes: [] },
      };
    case "services":
      return {
        name: "",
        upstream: { type: "roundrobin", scheme: "http", nodes: [] },
        plugins: {},
      };
    case "upstreams":
      return { name: "", type: "roundrobin", scheme: "http", nodes: [] };
    case "consumers":
      return { desc: "", plugins: {} };
    case "ssls":
      return { snis: [], cert: "", key: "", status: 1 };
    case "protos":
      return { content: 'syntax = "proto3";\n' };
    case "stream_routes":
      return { server_port: 9000, upstream: { type: "roundrobin", nodes: [] } };
    case "secrets":
      return { uri: "", prefix: "", token: "" };
    default:
      return kind === "plugin_metadata" ? {} : { plugins: {} };
  }
}
export function pretty(value: unknown) {
  return JSON.stringify(value, null, 2);
}
export function displayJson(value: unknown) {
  return pretty(value).replaceAll(
    "__INFRAPILOT_REDACTED__",
    "••••••（保留原值）",
  );
}
export function gatewayPage(kind: string) {
  return ["routes", "services", "upstreams", "consumers", "ssls"].includes(kind)
    ? `#/gateway-${kind}`
    : `#/gateway-advanced?resource=${encodeURIComponent(kind)}`;
}

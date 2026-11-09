export const upsPages = [
  ["overview", "电源概览", "plug"],
  ["battery", "电池", "battery-three-quarters"],
  ["power", "功率与负载", "bolt"],
  ["voltage", "电压", "wave-square"],
  ["energy", "能耗与费用", "chart-area"],
  ["history", "历史与设备信息", "history"],
  ["events", "事件", "list-alt"],
  ["devices", "设备管理", "server"],
  ["commands", "设备控制", "play-circle"],
  ["variables", "可写变量", "sliders-h"],
  ["reports", "报告导出", "file-download"],
  ["settings", "采样与计算", "calculator"],
  ["scripts", "自动化脚本", "terminal"],
  ["nut", "NUT 接入与配置", "network-wired"],
  ["users", "用户与单点登录", "user-shield"],
  ["system", "系统维护", "tools"],
];
export function pageFor(op) {
  const p = op.path,
    g = op.group;
  if (["battery", "power", "voltage", "energy"].includes(g)) return g;
  if (g === "auth") return "users";
  if (["advanced", "nut", "nut_config"].includes(g)) return "nut";
  if (g === "upscmd") return "commands";
  if (g === "upsrw") return "variables";
  if (
    ["events", "upsmon", "nut_history"].includes(g) ||
    p.includes("/table/events")
  )
    return "events";
  if (g === "report") return "reports";
  if (p.includes("script-actions")) return "scripts";
  if (
    p.includes("renamer") ||
    g === "options" ||
    (g === "settings" && !p.endsWith("/log"))
  )
    return "settings";
  if (g === "multi-nut") return "devices";
  if (["data", "table", "ups"].includes(g)) return "history";
  return "system";
}
export const labels = {
  battery_charge: "电池电量（%）",
  battery_runtime: "预计续航（秒）",
  battery_voltage: "电池电压（V）",
  battery_voltage_nominal: "标称电压（V）",
  battery_temperature: "电池温度（°C）",
  battery_charge_low: "低电量阈值（%）",
  battery_runtime_low: "低续航阈值（秒）",
  battery_type: "电池类型",
  battery_mfr_date: "电池制造日期",
  battery_date: "电池日期",
  battery_charge_warning: "电量警戒值",
  ups_status: "供电状态",
  ups_load: "负载（%）",
  ups_realpower: "有功功率（W）",
  ups_power: "视在功率（VA）",
  ups_realpower_nominal: "额定有功功率（W）",
  ups_power_nominal: "额定视在功率（VA）",
  ups_test_result: "最近自检",
  input_voltage: "输入电压（V）",
  output_voltage: "输出电压（V）",
  input_voltage_nominal: "标称输入电压（V）",
  output_voltage_nominal: "标称输出电压（V）",
  input_transfer_low: "低电压切换点（V）",
  input_transfer_high: "高电压切换点（V）",
  input_frequency: "输入频率（Hz）",
  output_frequency: "输出频率（Hz）",
  device_mfr: "制造商",
  device_model: "型号",
  device_serial: "序列号",
  timestamp_utc: "时间",
  timestamp: "时间",
  created_at: "创建时间",
  updated_at: "更新时间",
  name: "名称",
  description: "说明",
  value: "值",
  type: "类型",
  event_type: "事件类型",
  event_message: "事件",
  message: "信息",
  acknowledged: "已确认",
  host: "服务器",
  port: "端口",
  enabled: "启用",
  is_primary: "主设备",
  ups_name: "UPS 标识",
  nut_mode: "模式",
  polling_interval: "采样间隔（秒）",
  retention_days: "保留天数",
  db_strategy: "存储策略",
  username: "用户名",
  role: "角色",
  is_admin: "管理员",
  last_login: "最近登录",
  source_ip: "来源地址",
  target_id: "设备 ID",
  success: "成功",
  status: "状态",
  command: "命令",
  totalEnergy: "能耗（kWh）",
  totalCost: "费用",
  total_energy: "能耗（kWh）",
  total_cost: "费用",
  avgLoad: "平均负载（%）",
  avg_load: "平均负载（%）",
  co2: "碳排放（kg）",
  currency: "币种",
  current: "当前",
  min: "最低",
  max: "最高",
  avg: "平均",
  available: "已上报",
  id: "ID",
  canonical_key: "标准变量",
  source_key: "原始变量",
  mapped_key: "映射变量",
  source_value: "当前读数",
  script_body: "脚本内容",
  trigger_event: "触发事件",
  battery_threshold: "电量阈值（%）",
  cooldown_seconds: "冷却时间（秒）",
  filename: "文件名",
  size: "大小",
  modified: "修改时间",
  version: "版本",
  platform: "平台",
  timezone: "时区",
  currency_symbol: "货币符号",
  database_version: "数据库版本",
  python_version: "Python 版本",
  last_update: "更新时间",
};
export const title = (key) => labels[key] || key;
export function numeric(v) {
  return v === null || v === undefined || v === "" || typeof v === "boolean"
    ? null
    : Number.isFinite(Number(v))
      ? Number(v)
      : null;
}
export function batteryCondition(data) {
  const status = String(data.ups_status || ""),
    test = String(data.ups_test_result || "");
  if (/\bRB\b/.test(status) || /replace|fail/i.test(test))
    return "需要检查或更换";
  if (/\bLB\b/.test(status)) return "低电量";
  if (/\bCAL\b/.test(status) || /in progress/i.test(test)) return "正在自检";
  if (/pass/i.test(test)) return "最近自检通过";
  return "未提供健康结论";
}
export function powerReading(data) {
  const actual = numeric(data.ups_realpower),
    load = numeric(data.ups_load),
    nominal = numeric(data.ups_realpower_nominal);
  return actual !== null
    ? {
        value: actual,
        explanation:
          "Nutify 有功功率读数；设备未上报实测值时，由已配置公式计算。",
      }
    : load !== null && nominal !== null
      ? {
          value: (load * nominal) / 100,
          explanation: "估算：负载百分比 × 额定有功功率。",
        }
      : { value: null, explanation: "未上报" };
}
export function unwrap(v) {
  return v?.data && !Array.isArray(v.data) ? v.data : v;
}
export function rowsOf(v) {
  if (Array.isArray(v)) return v;
  for (const k of [
    "rows",
    "targets",
    "users",
    "commands",
    "variables",
    "files",
    "actions",
    "logs",
    "events",
    "history",
    "data",
  ])
    if (Array.isArray(v?.[k])) return v[k];
  if (v?.data && typeof v.data === "object") return rowsOf(v.data);
  return [];
}
export function flatten(value, prefix = "", out = []) {
  if (value && typeof value === "object" && !Array.isArray(value))
    for (const [key, v] of Object.entries(value)) {
      if (
        ["html", "raw_json", "data_json", "script_body", "content"].includes(
          key,
        )
      )
        continue;
      flatten(v, prefix ? prefix + " / " + title(key) : title(key), out);
    }
  else if (!Array.isArray(value))
    out.push({
      name: prefix,
      value:
        value === null || value === undefined
          ? "未上报"
          : typeof value === "boolean"
            ? value
              ? "是"
              : "否"
            : String(value),
    });
  return out;
}
export function chartRows(rows, keys) {
  return rows
    .map((r) => ({
      time: timestampMillis(
        r.timestamp_utc ?? r.timestamp ?? r.created_at,
        r.timestamp_utc !== undefined,
      ),
      ...Object.fromEntries(keys.map((k) => [k, numeric(r[k])])),
    }))
    .filter((r) => Number.isFinite(r.time))
    .sort((a, b) => a.time - b.time);
}
export function schemaDefaults(schema) {
  return Object.fromEntries(
    Object.entries(schema.properties || {})
      .filter(
        ([, s]) =>
          s.default !== undefined &&
          (s.type !== "integer" || Number.isFinite(Number(s.default))),
      )
      .map(([k, s]) => [
        k,
        s.type === "integer" ? Number(s.default) : s.default,
      ]),
  );
}

export function timestampMillis(value, utc = false) {
  if (typeof value === "number") return value;
  if (typeof value !== "string" || !value) return NaN;
  const normalized = value.replace(" ", "T");
  return Date.parse(
    utc && !/(Z|[+-]\d{2}:?\d{2})$/i.test(normalized)
      ? normalized + "Z"
      : normalized,
  );
}
export function reading(value) {
  const n = numeric(value);
  return n === null
    ? "—"
    : new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(n);
}

// Nutify 0.3.0 table serializers attach CACHE_TIMEZONE to UTC wall-clock
// values without converting them (format_datetime_tz). The history endpoints'
// epoch timestamps confirm the database values are UTC. Scope this repair to
// these two table endpoints; normal offset-bearing timestamps stay untouched.
export function normalizeTableResponse(path, value) {
  if (!["/api/table/dynamic", "/api/table/static"].includes(path)) return value;
  const visit = (item) => {
    if (Array.isArray(item)) return item.map(visit);
    if (!item || typeof item !== "object") return item;
    return Object.fromEntries(
      Object.entries(item).map(([key, v]) => [
        key,
        key === "timestamp_utc" && typeof v === "string"
          ? v.replace(/(Z|[+-]\d{2}:?\d{2})$/i, "") + "Z"
          : visit(v),
      ]),
    );
  };
  return visit(value);
}

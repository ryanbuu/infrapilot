import resourceSchemas from "./gateway-schemas.json";
import { encode } from "./api";
import { mobile, toolbar, showError } from "./ui";
import {
  MASK,
  clone,
  typeOf,
  hasMask,
  safeKey,
  variants,
  schemaType,
  resolveSchema,
  emptyValue,
  validateScalar,
  validateMasks,
  resourceDefaults,
} from "./schema-model";

const labels = {
  name: "名称",
  desc: "描述",
  labels: "标签",
  type: "类型",
  scheme: "协议",
  nodes: "上游节点",
  host: "主机",
  port: "端口",
  weight: "权重",
  priority: "优先级",
  metadata: "节点元数据",
  timeout: "超时设置",
  connect: "连接超时（秒）",
  send: "发送超时（秒）",
  read: "读取超时（秒）",
  retries: "重试次数",
  retry_timeout: "重试总时限（秒）",
  keepalive_pool: "连接池",
  idle_timeout: "空闲超时（秒）",
  requests: "每条连接的请求上限",
  size: "连接池容量",
  pass_host: "Host 传递方式",
  upstream_host: "上游 Host",
  hash_on: "哈希来源",
  key: "键值",
  service_name: "服务发现名称",
  discovery_type: "服务发现类型",
  discovery_args: "服务发现参数",
  namespace_id: "命名空间",
  group_name: "服务组",
  checks: "健康检查",
  active: "主动检查",
  passive: "被动检查",
  healthy: "健康判定",
  unhealthy: "不健康判定",
  interval: "检查间隔（秒）",
  successes: "成功次数",
  http_failures: "HTTP 失败次数",
  tcp_failures: "TCP 失败次数",
  timeouts: "超时次数",
  http_statuses: "HTTP 状态码",
  http_path: "检查路径",
  https_verify_certificate: "验证 HTTPS 证书",
  concurrency: "并发数",
  req_headers: "请求头",
  tls: "上游 TLS",
  client_cert: "客户端证书（PEM）",
  client_key: "客户端私钥（PEM）",
  client_cert_id: "客户端证书 ID",
  verify: "验证证书",
  uri: "匹配路径",
  uris: "匹配路径列表",
  hosts: "域名列表",
  methods: "HTTP 方法",
  status: "启用状态",
  remote_addr: "客户端地址",
  remote_addrs: "客户端地址列表",
  vars: "匹配表达式",
  enable_websocket: "WebSocket",
  plugins: "插件",
  upstream: "内联上游",
  upstream_id: "上游 ID",
  service_id: "服务 ID",
  plugin_config_id: "插件配置 ID",
  script: "脚本",
  script_id: "脚本 ID",
  filter_func: "过滤函数",
  group_id: "消费者组 ID",
  snis: "SNI 域名列表",
  sni: "SNI 域名",
  cert: "证书（PEM）",
  cert_key: "私钥（PEM）",
  certs: "额外证书",
  keys: "额外私钥",
  ssl_protocols: "TLS 协议",
  client: "客户端认证",
  ca: "CA 证书（PEM）",
  depth: "证书链深度",
  skip_mtls_uri_regex: "跳过认证的 URI 正则",
  server_addr: "监听地址",
  server_port: "监听端口",
  protocol: "协议配置",
  conf: "协议参数",
  superior_id: "父级 ID",
  logger: "日志器",
  filter: "过滤条件",
  content: "Protobuf 定义",
  token: "访问令牌",
  prefix: "路径前缀",
  access_key: "访问密钥 ID",
  secret_key: "访问密钥",
  region: "区域",
  endpoint: "端点",
  headers: "请求头改写",
  set: "设置",
  add: "追加",
  remove: "移除",
  regex_uri: "URI 正则替换",
  method: "请求方法",
  header: "认证请求头",
  query: "查询参数",
  hide_credentials: "隐藏凭据",
  username: "用户名",
  password: "密码",
  count: "请求上限",
  time_window: "时间窗口（秒）",
  policy: "存储策略",
  allow_degradation: "允许降级",
  show_limit_quota_header: "返回配额响应头",
  rejected_code: "拒绝状态码",
  rejected_msg: "拒绝消息",
  allow_origins: "允许来源",
  allow_methods: "允许方法",
  allow_headers: "允许请求头",
  expose_headers: "暴露响应头",
  max_age: "预检缓存（秒）",
  allow_credential: "允许携带凭据",
  _meta: "插件执行选项",
  disable: "禁用插件",
  error_response: "错误响应",
  log_format: "日志格式",
  ssl_verify: "验证 TLS",
  auth_header: "认证请求头",
  batch_max_size: "批次上限",
  buffer_duration: "缓冲时间（秒）",
  inactive_timeout: "空闲提交（秒）",
  retry_delay: "重试间隔（秒）",
  max_retry_count: "最大重试次数",
  include_req_body: "记录请求体",
  include_resp_body: "记录响应体",
  body: "响应内容",
  body_base64: "Base64 内容",
  redirect_uri: "重定向地址",
  http_code: "HTTP 状态码",
  append_query_string: "保留查询参数",
  http_to_https: "跳转 HTTPS",
  use_real_request_uri_unsafe: "使用原始请求 URI",
  anonymous_consumer: "匿名消费者",
  key_type: "键值类型",
  group: "共享计数组",
  service: "服务",
  path: "路径",
  value: "值",
};
const title = (key) => (/^[-]?\d+$/.test(key) ? "条目值" : labels[key] || key);
const secretKey = (key) =>
  /password|passwd|secret|token|private.?key|client_key|access_key|auth_header|^key$|^keys$/i.test(
    key,
  );
const multiline = (key) =>
  /cert|^ca$|^content$|^script$|^filter_func$|client_key/.test(key);
const optionTypes = [
  ["string", "文本"],
  ["number", "数字"],
  ["boolean", "布尔值"],
  ["object", "字段组"],
  ["array", "列表"],
  ["null", "空值"],
];
const hiddenKeys = new Set(["id", "username", "create_time", "update_time"]);
const labelFor = (key, path) =>
  key === "type" && path.includes("upstream") ? "负载均衡算法" : title(key);
const createField = (config) =>
  Ext.create({
    xtype: "textfield",
    labelAlign: "top",
    cls: "native-field",
    ...config,
  });
const group = (heading, items) =>
  Ext.create({
    xtype: "fieldset",
    title: encode(heading),
    layout: "vbox",
    items,
  });
const wrap = (components) => {
  const rows = [];
  for (let i = 0; i < components.length; i++) {
    const n = components[i];
    if (
      !mobile() &&
      !n.complex &&
      components[i + 1] &&
      !components[i + 1].complex
    ) {
      n.component.setFlex(1);
      components[i + 1].component.setFlex(1);
      components[i + 1].component.setMargin("0 0 0 16");
      rows.push({
        xtype: "container",
        layout: "hbox",
        items: [n.component, components[++i].component],
      });
    } else rows.push(n.component);
  }
  return rows;
};
function scalar(schema, original, path, required) {
  const key = path.at(-1),
    label =
      schema.description?.includes("load balancing") && key === "type"
        ? "负载均衡算法"
        : labelFor(key, path),
    type = schemaType(schema, original);
  let choices = schema.enum;
  if (
    key === "type" &&
    !choices &&
    (schema.description?.includes("load balancing") ||
      path.at(-2) === "upstream")
  )
    choices = ["roundrobin", "chash", "least_conn", "ewma"];
  if (key === "status") choices = [1, 0];
  const config = {
    name: path.join("."),
    label: encode(label),
    required,
    tooltip: encode([key, schema.description].filter(Boolean).join(" · ")),
  };
  let convert = (v) => v;
  if (choices || type === "boolean") {
    choices = choices || [true, false];
    if (original !== undefined && !choices.some((v) => Object.is(v, original)))
      choices = [...choices, original];
    config.xtype = "selectfield";
    const display = (v) =>
      key === "status"
        ? v === 1
          ? "启用"
          : "停用"
        : typeof v === "boolean"
          ? v
            ? "是"
            : "否"
          : String(v);
    config.options = [
      { text: required ? "请选择" : "默认 / 未设置", value: "" },
      ...choices.map((v, i) => ({ text: display(v), value: String(i) })),
    ];
    config.value =
      original === undefined
        ? ""
        : String(choices.findIndex((v) => Object.is(v, original)));
    convert = (v) =>
      v === "" || v == null ? undefined : clone(choices[Number(v)]);
  } else if (type === "number" || type === "integer") {
    config.xtype = "numberfield";
    config.value = original ?? null;
    config.minValue = schema.minimum;
    config.maxValue = schema.maximum;
    config.step = type === "integer" ? 1 : 0.1;
    convert = (v) => (v === "" || v == null ? undefined : Number(v));
  } else if (type === "null") {
    config.xtype = "displayfield";
    config.value = "null";
    convert = () => null;
  } else {
    config.value = original ?? "";
    config.maxLength = schema.maxLength;
    if (multiline(key)) {
      config.xtype = "textareafield";
      config.height = 170;
    } else if (secretKey(key)) config.xtype = "passwordfield";
    convert = (v) => (v === "" ? undefined : String(v));
  }
  if (schema.default !== undefined && config.xtype !== "selectfield")
    config.placeholder = "默认：" + String(schema.default);
  const component = createField(config),
    initial = component.getValue();
  return {
    component,
    complex: config.xtype === "textareafield",
    read() {
      const v = component.getValue();
      let result = Object.is(v, initial) ? clone(original) : convert(v);
      if (required && (result === undefined || result === ""))
        throw Error("请填写" + label);
      return validateScalar(result, schema, label);
    },
  };
}

function makeNode(
  schema = {},
  original,
  path,
  env,
  required = false,
  { root = false, noOptional = false, noVariant = false } = {},
) {
  const key = path.at(-1) || "配置",
    label = labelFor(key, path);
  if (original === MASK) {
    const mode = createField({
      xtype: "selectfield",
      label: encode(label),
      name: path.join(".") + ".mode",
      value: "keep",
      options: [
        { text: "保留现有密钥", value: "keep" },
        { text: "重新填写", value: "replace" },
        ...(!required ? [{ text: "移除该项", value: "remove" }] : []),
      ],
    });
    const body = Ext.create("Ext.Container", { layout: "vbox", hidden: true });
    let replacement;
    mode.on("change", (_, v) => {
      if (v === "replace" && !replacement) {
        replacement = makeNode(schema, undefined, path, env, required, {
          noOptional: true,
        });
        body.add(replacement.component);
      }
      body.setHidden(v !== "replace");
    });
    return {
      component: group(label, [mode, body]),
      complex: true,
      read: () =>
        mode.getValue() === "keep"
          ? MASK
          : mode.getValue() === "remove"
            ? undefined
            : replacement.read(),
    };
  }
  const choices = variants(schema),
    types = [...new Set(choices.map((s) => schemaType(s)))];
  const openType =
    !schema.type &&
    !schema.properties &&
    !schema.items &&
    !schema.enum &&
    !choices.length;
  if (!noVariant && (types.length > 1 || openType)) {
    const allowed = types.length > 1 ? types : optionTypes.map(([v]) => v);
    const current =
      original === undefined
        ? allowed.includes("string")
          ? "string"
          : allowed[0]
        : typeOf(original);
    const normalized =
      current === "number" && allowed.includes("integer") ? "integer" : current;
    const choice = createField({
      xtype: "selectfield",
      label: encode(label + " · 值类型"),
      value: normalized,
      options: [...new Set([...allowed, normalized])].map((v) => ({
        value: v,
        text: optionTypes.find(([k]) => k === v)?.[1] || v,
      })),
    });
    const body = Ext.create("Ext.Container", { layout: "vbox" });
    let node;
    const rebuild = (type, value) => {
      body.removeAll(true);
      const selected = choices.find((s) => schemaType(s) === type) || { type };
      node = makeNode(
        { ...schema, ...selected, type },
        value,
        path,
        env,
        required,
        { root, noOptional, noVariant: true },
      );
      body.add(node.component);
    };
    rebuild(normalized, original);
    choice.on("change", (_, type) =>
      rebuild(
        type,
        emptyValue(choices.find((s) => schemaType(s) === type) || { type }),
      ),
    );
    return {
      component: Ext.create("Ext.Container", {
        layout: "vbox",
        items: [choice, body],
      }),
      complex: true,
      read: () => node.read(),
    };
  }
  schema = resolveSchema(schema, original);
  const type = schemaType(schema, original);
  if (
    !root &&
    !noOptional &&
    !required &&
    (type === "object" || type === "array")
  ) {
    const active = createField({
      xtype: "checkboxfield",
      label: encode("配置" + label),
      labelAlign: "left",
      labelWidth: "75%",
      name: path.join(".") + ".enabled",
      checked: original !== undefined,
    });
    const body = Ext.create("Ext.Container", {
      layout: "vbox",
      hidden: original === undefined,
    });
    let node;
    const enable = () => {
      if (!node) {
        node = makeNode(
          schema,
          original ?? emptyValue(schema),
          path,
          env,
          required,
          { noOptional: true },
        );
        body.add(node.component);
      }
    };
    if (original !== undefined) enable();
    active.on("change", () => {
      const enabled = active.getChecked();
      if (enabled) enable();
      body.setHidden(!enabled);
    });
    return {
      component: Ext.create("Ext.Container", {
        layout: "vbox",
        items: [active, body],
      }),
      complex: true,
      read: () => (active.getChecked() ? node.read() : undefined),
    };
  }
  if (type === "object" && key === "plugins")
    return pluginsNode(original || {}, path, env);
  if (type === "object")
    return objectNode(schema, original || {}, path, env, { root });
  if (type === "array") return arrayNode(schema, original || [], path, env);
  return scalar(schema, original, path, required);
}
function objectNode(schema, original, path, env, { root = false } = {}) {
  const properties = schema.properties || {},
    fields = [];
  const known = new Map();
  for (const [key, s] of Object.entries(properties)) {
    if (root && hiddenKeys.has(key)) continue;
    const node = makeNode(
      s,
      original[key],
      [...path, key],
      env,
      (schema.required || []).includes(key),
    );
    known.set(key, node);
    fields.push(node);
  }
  const extras = new Map();
  const body = Ext.create("Ext.Container", {
    layout: "vbox",
    items: wrap(fields),
  });
  function addExtra(key, value, s) {
    const name = createField({
      label: path.at(-1) === "nodes" ? "节点地址（主机:端口）" : "字段名称",
      value: key,
      required: true,
    });
    const node = makeNode(s || {}, value, [...path, key || "value"], env, true);
    let row;
    const remove = {
      text: "移除字段",
      handler: () => {
        extras.delete(row);
        body.remove(row, true);
      },
    };
    row = group(key || "新字段", [name, node.component, toolbar([remove])]);
    extras.set(row, { name, node, oldKey: key });
    body.add(row);
  }
  const extraSchema = (key) =>
    Object.entries(schema.patternProperties || {}).find(([pattern]) => {
      try {
        return new RegExp(pattern).test(key);
      } catch {
        return false;
      }
    })?.[1] ||
    (typeof schema.additionalProperties === "object"
      ? schema.additionalProperties
      : {});
  for (const [key, value] of Object.entries(original))
    if (!(key in properties) && !(root && hiddenKeys.has(key)))
      addExtra(key, value, extraSchema(key));
  if (schema.additionalProperties !== false || schema.patternProperties) {
    body.add(
      toolbar([
        {
          text: path.at(-1) === "nodes" ? "添加节点" : "添加字段",
          iconCls: "x-fa fa-plus",
          handler: () => addExtra("", undefined, extraSchema("")),
        },
      ]),
    );
  }
  return {
    component:
      root || /^\d+$/.test(path.at(-1))
        ? body
        : group(title(path.at(-1)), [body]),
    complex: true,
    read() {
      const result = clone(original);
      for (const [key, node] of known) {
        const v = node.read();
        if (v === undefined) delete result[key];
        else result[key] = v;
      }
      for (const key of Object.keys(original))
        if (!(key in properties) && !(root && hiddenKeys.has(key)))
          delete result[key];
      const used = new Set(Object.keys(properties));
      for (const { name, node, oldKey } of extras.values()) {
        const key = String(name.getValue() || "").trim();
        if (!safeKey(key)) throw Error("请填写有效的字段名称");
        if (used.has(key)) throw Error("字段重复：" + key);
        used.add(key);
        result[key] = node.read();
      }
      if (
        schema.minProperties &&
        Object.keys(result).length < schema.minProperties
      )
        throw Error(
          title(path.at(-1)) + "至少需要 " + schema.minProperties + " 个字段",
        );
      return result;
    },
  };
}
function arrayNode(schema, original, path, env) {
  const rows = [];
  const body = Ext.create("Ext.Container", { layout: "vbox" });
  function add(value, index) {
    const itemSchema = Array.isArray(schema.items)
      ? schema.items[rows.length] || {}
      : schema.items || {};
    const node = makeNode(
      itemSchema,
      value,
      [...path, String(rows.length)],
      env,
      true,
      { noOptional: true },
    );
    let row;
    const entry = { node, index, removed: false };
    rows.push(entry);
    row = group(
      path.at(-1) === "nodes" ? "节点 " + rows.length : "条目 " + rows.length,
      [
        node.component,
        toolbar([
          {
            text: "移除",
            iconCls: "x-fa fa-trash",
            handler: () => {
              entry.removed = true;
              body.remove(row, true);
            },
          },
        ]),
      ],
    );
    body.add(row);
  }
  original.forEach((value, index) => add(value, index));
  const component = group(title(path.at(-1)), [
    body,
    toolbar([
      {
        text: path.at(-1) === "nodes" ? "添加节点" : "添加条目",
        iconCls: "x-fa fa-plus",
        handler: () =>
          add(
            emptyValue(
              Array.isArray(schema.items)
                ? schema.items[rows.length]
                : schema.items,
            ),
            -1,
          ),
      },
    ]),
  ]);
  return {
    component,
    complex: true,
    read() {
      const result = rows
        .filter((r) => !r.removed)
        .map((r, i) => {
          const v = r.node.read();
          if (hasMask(v) && r.index !== i)
            throw Error("列表位置已改变，请重新填写受影响条目的隐藏密钥");
          return v;
        });
      if (schema.minItems && result.length < schema.minItems)
        throw Error(title(path.at(-1)) + "至少需要 " + schema.minItems + " 条");
      if (schema.maxItems && result.length > schema.maxItems)
        throw Error(title(path.at(-1)) + "最多允许 " + schema.maxItems + " 条");
      if (
        schema.uniqueItems &&
        new Set(result.map((v) => JSON.stringify(v))).size !== result.length
      )
        throw Error(title(path.at(-1)) + "不能重复");
      return result;
    },
  };
}
function pluginsNode(original, path, env) {
  const body = Ext.create("Ext.Container", { layout: "vbox" }),
    nodes = new Map();
  function add(name, value, schema) {
    const node = makeNode(
      { ...schema, type: "object" },
      value,
      [...path, name],
      env,
      true,
      { noOptional: true },
    );
    const card = group(name, [
      node.component,
      toolbar([
        {
          text: "移除插件",
          iconCls: "x-fa fa-trash",
          handler: () => {
            nodes.delete(name);
            body.remove(card, true);
          },
        },
      ]),
    ]);
    nodes.set(name, node);
    body.add(card);
  }
  for (const [name, value] of Object.entries(original))
    add(name, value, env.pluginSchemas[name] || {});
  const chooser = createField({
    xtype: "selectfield",
    label: "添加插件",
    options: [
      { text: "选择插件", value: "" },
      ...env.pluginNames.map((name) => ({ text: name, value: name })),
    ],
    value: "",
    flex: 1,
  });
  const addButton = Ext.create("Ext.Button", {
    text: "添加插件",
    iconCls: "x-fa fa-plus",
    handler: async () => {
      const name = chooser.getValue();
      if (!name) return;
      if (nodes.has(name)) {
        showError("该插件已添加");
        return;
      }
      addButton.disable();
      try {
        const schema = await env.loadPluginSchema(name);
        if (body.destroyed) return;
        add(name, {}, schema);
        chooser.setValue("");
      } catch (e) {
        showError(e);
      } finally {
        if (!addButton.destroyed) addButton.enable();
      }
    },
  });
  return {
    component: group("插件", [toolbar([chooser, addButton]), body]),
    complex: true,
    read: () =>
      Object.fromEntries([...nodes].map(([name, node]) => [name, node.read()])),
  };
}
const sections = [
  [
    "基本信息",
    ["name", "desc", "status", "labels", "type", "scheme", "group_id"],
  ],
  [
    "匹配条件",
    [
      "uri",
      "uris",
      "host",
      "hosts",
      "methods",
      "remote_addr",
      "remote_addrs",
      "priority",
      "vars",
      "sni",
      "server_addr",
      "server_port",
      "enable_websocket",
    ],
  ],
  [
    "转发目标",
    [
      "service_id",
      "upstream_id",
      "upstream",
      "nodes",
      "discovery_type",
      "service_name",
      "discovery_args",
      "hash_on",
      "key",
      "pass_host",
      "upstream_host",
    ],
  ],
  [
    "连接与健康检查",
    ["timeout", "retries", "retry_timeout", "keepalive_pool", "checks", "tls"],
  ],
  ["证书与认证", ["snis", "cert", "certs", "keys", "ssl_protocols", "client"]],
  ["插件", ["plugin_config_id", "plugins"]],
  ["高级设置", ["script", "script_id", "filter_func", "protocol", "content"]],
];
export function gatewaySchema(kind, live) {
  const fallback = resourceSchemas[kind] || {
    type: "object",
    additionalProperties: true,
  };
  return live?.type === "object"
    ? {
        ...fallback,
        ...live,
        properties: { ...fallback.properties, ...live.properties },
      }
    : fallback;
}
export function createGatewayForm({
  kind,
  item,
  schema,
  pluginNames = [],
  pluginSchemas = {},
  loadPluginSchema,
}) {
  if (kind === "plugin_metadata" && !item) {
    const id = createField({
      label: "插件名称 / ID",
      name: "resourceId",
      required: true,
    });
    let loaded;
    const panel = Ext.create("Ext.Container", { flex: 1, layout: "vbox" });
    const load = Ext.create("Ext.Button", {
      text: "加载插件字段",
      ui: "action",
      handler: async () => {
        const name = String(id.getValue() || "").trim();
        if (!name) {
          showError("请填写插件名称");
          return;
        }
        load.disable();
        try {
          const fields = await loadPluginSchema(name);
          if (panel.destroyed) return;
          loaded = createGatewayForm({
            kind,
            item: { id: name, value: {} },
            schema: fields,
            pluginNames,
            pluginSchemas,
            loadPluginSchema,
          });
          panel.removeAll(true);
          panel.add(loaded.component);
        } catch (e) {
          showError(e);
          if (!load.destroyed) load.enable();
        }
      },
    });
    panel.add({
      xtype: "formpanel",
      padding: 16,
      layout: "vbox",
      items: [id, toolbar([load])],
    });
    return {
      component: panel,
      read() {
        if (!loaded) throw Error("请先加载插件字段");
        return loaded.read();
      },
    };
  }
  const original = clone(item?.value || resourceDefaults(kind));
  const env = { pluginNames, pluginSchemas, loadPluginSchema };
  schema = gatewaySchema(kind, schema);
  const id = createField({
    label:
      kind === "consumers"
        ? "用户名 / ID"
        : kind === "secrets"
          ? "资源 ID（provider/id）"
          : kind === "plugin_metadata"
            ? "插件名称 / ID"
            : "资源 ID",
    name: "resourceId",
    required: true,
    value: item?.id || "",
    readOnly: !!item,
  });
  const bodyProperties = { ...schema.properties };
  delete bodyProperties.id;
  delete bodyProperties.username;
  delete bodyProperties.create_time;
  delete bodyProperties.update_time;
  if (kind === "ssls" && bodyProperties.key) {
    bodyProperties.cert_key = bodyProperties.key;
    delete bodyProperties.key;
    schema = {
      ...schema,
      required: (schema.required || []).map((key) =>
        key === "key" ? "cert_key" : key,
      ),
    };
  }
  const hidden = Object.fromEntries(
    Object.entries(original).filter(([key]) => hiddenKeys.has(key)),
  );
  const rootValue = { ...original };
  for (const key of hiddenKeys) delete rootValue[key];
  if (kind === "ssls" && "key" in rootValue) {
    rootValue.cert_key = rootValue.key;
    delete rootValue.key;
  }
  const claimed = new Set(),
    models = [];
  const tabs = [];
  for (const [section, keys] of sections) {
    const chosen = keys.filter(
      (key) => key in bodyProperties || key in rootValue,
    );
    if (section === "证书与认证" && kind === "ssls") chosen.push("cert_key");
    if (!chosen.length) continue;
    chosen.forEach((key) => claimed.add(key));
    const properties = Object.fromEntries(
      chosen.map((key) => [key, bodyProperties[key] || {}]),
    );
    const values = Object.fromEntries(
      chosen
        .filter((key) => key in rootValue)
        .map((key) => [key, rootValue[key]]),
    );
    const model = makeNode(
      {
        type: "object",
        properties,
        required: (schema.required || []).filter((k) => chosen.includes(k)),
        additionalProperties: false,
      },
      values,
      [],
      env,
      true,
      { root: true },
    );
    models.push(model);
    tabs.push({
      xtype: "formpanel",
      title: section,
      layout: "vbox",
      scrollable: "vertical",
      padding: 16,
      items: [...(tabs.length ? [] : [id]), model.component],
    });
  }
  const properties = Object.fromEntries(
    Object.entries(bodyProperties).filter(([k]) => !claimed.has(k)),
  );
  const values = Object.fromEntries(
    Object.entries(rootValue).filter(([k]) => !claimed.has(k)),
  );
  if (
    Object.keys(properties).length ||
    Object.keys(values).length ||
    schema.additionalProperties !== false
  ) {
    const model = makeNode(
      {
        ...schema,
        properties,
        required: (schema.required || []).filter((k) => !claimed.has(k)),
      },
      values,
      [],
      env,
      true,
      { root: true },
    );
    models.push(model);
    tabs.push({
      xtype: "formpanel",
      title: tabs.length ? "扩展字段" : "基本信息",
      layout: "vbox",
      scrollable: "vertical",
      padding: 16,
      items: [...(tabs.length ? [] : [id]), model.component],
    });
  }
  const component = Ext.create("Ext.TabPanel", {
    flex: 1,
    cls: "gateway-form",
    items: tabs,
  });
  return {
    component,
    read() {
      const resourceId = String(id.getValue() || "").trim();
      if (!resourceId) throw Error("请填写资源 ID");
      const value = {
        ...hidden,
        ...Object.assign({}, ...models.map((m) => m.read())),
      };
      if (kind === "ssls" && "cert_key" in value) {
        value.key = value.cert_key;
        delete value.cert_key;
      }
      validateMasks(value, original);
      return { id: resourceId, value };
    },
  };
}

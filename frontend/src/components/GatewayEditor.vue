<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { App as AntdApp } from "ant-design-vue";
import {
  PlusOutlined,
  DeleteOutlined,
  CodeOutlined,
} from "@ant-design/icons-vue";
import { api } from "../api";
import { gatewayLabel, gatewayTemplate, pretty } from "../gateway";
import type { GatewayItem, GatewayMutation, GatewayValue } from "../gateway";
import HelpHint from "./HelpHint.vue";
const props = defineProps<{
  open: boolean;
  kind: string;
  item?: GatewayItem;
  parent?: string;
  copy?: boolean;
  checking?: boolean;
}>();
const emit = defineEmits<{ close: []; preview: [value: GatewayMutation] }>();
const { message } = AntdApp.useApp();
const id = ref(""),
  raw = ref("{}"),
  tab = ref("form"),
  error = ref(""),
  schema = ref<GatewayValue>(),
  plugins = ref<string[]>([]);
const services = ref<GatewayItem[]>([]),
  upstreams = ref<GatewayItem[]>([]),
  pluginToAdd = ref<string>();
const loading = ref(false);
const editing = computed(() => !!props.item && !props.copy);
const draft = computed<GatewayValue>(() => {
  try {
    const v = JSON.parse(raw.value);
    return v && !Array.isArray(v) && typeof v === "object" ? v : {};
  } catch {
    return {};
  }
});
const upstream = computed<GatewayValue>(() =>
  props.kind === "upstreams" ? draft.value : draft.value.upstream || {},
);
const destination = computed(() =>
  draft.value.service_id
    ? "service"
    : draft.value.upstream_id
      ? "upstream"
      : draft.value.upstream
        ? "inline"
        : "plugins",
);
const nodes = computed<GatewayValue[]>(() => {
  const value = upstream.value.nodes;
  if (Array.isArray(value)) return value;
  return Object.entries(value || {}).map(([address, weight]) => {
    const match = address.match(/^(.*):(\d+)$/);
    return {
      host: (match ? match[1] : address)?.replace(/^\[|\]$/g, ""),
      port: match ? Number(match[2]) : 80,
      weight,
    };
  });
});
const named = computed(() =>
  [
    "routes",
    "services",
    "upstreams",
    "plugin_configs",
    "protos",
    "credentials",
  ].includes(props.kind),
);
const described = computed(() =>
  [
    "routes",
    "services",
    "upstreams",
    "consumers",
    "consumer_groups",
    "credentials",
    "plugin_configs",
    "stream_routes",
    "protos",
  ].includes(props.kind),
);
function parse() {
  const value = JSON.parse(raw.value);
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("配置必须为 JSON 对象");
  return value;
}
function patch(values: GatewayValue, remove: string[] = []) {
  try {
    const next = { ...parse(), ...values };
    remove.forEach((k) => delete next[k]);
    raw.value = pretty(next);
    error.value = "";
  } catch {
    error.value = "JSON 格式无效，请先在 JSON 配置中修正";
  }
}
function patchUpstream(values: GatewayValue) {
  if (props.kind === "upstreams") patch(values);
  else patch({ upstream: { ...upstream.value, ...values } });
}
function changeNode(index: number, values: GatewayValue) {
  patchUpstream({
    nodes: nodes.value.map((n, i) => (i === index ? { ...n, ...values } : n)),
  });
}
function changeDestination(value: string) {
  const values =
    value === "service"
      ? { service_id: "" }
      : value === "upstream"
        ? { upstream_id: "" }
        : value === "inline"
          ? { upstream: { type: "roundrobin", scheme: "http", nodes: [] } }
          : {};
  patch(
    values,
    ["service_id", "upstream_id", "upstream"].filter((k) => !(k in values)),
  );
}
// Keep the selected destination visible while an empty reference is being entered.
const destinationChoice = ref("inline");
watch(tab, (value) => {
  if (value === "form") destinationChoice.value = destination.value;
});
async function addPlugin(name: string) {
  try {
    const schema = await api<GatewayValue>(
      `/gateway/schema/routes?plugin=${encodeURIComponent(name)}`,
    );
    const initial: GatewayValue = {};
    Object.entries(schema.properties || {}).forEach(([key, value]) => {
      const v = value as GatewayValue;
      if (v.default !== undefined) initial[key] = v.default;
    });
    patch({ plugins: { ...draft.value.plugins, [name]: initial } });
    tab.value = "json";
  } catch (e) {
    message.error((e as Error).message);
  }
  pluginToAdd.value = undefined;
}
function preview() {
  try {
    const value = parse();
    if (!id.value.trim()) throw new Error("请填写资源 ID");
    emit("preview", {
      action: editing.value ? "UPDATE" : "CREATE",
      id: id.value.trim(),
      parent: props.parent,
      revision: editing.value ? props.item?.revision : "absent",
      value,
    });
    error.value = "";
  } catch (e) {
    error.value = (e as Error).message;
  }
}
watch(
  () => props.open,
  async (open) => {
    if (!open) {
      raw.value = "{}";
      schema.value = undefined;
      return;
    }
    id.value = props.copy ? "" : props.item?.id || "";
    const value = props.item
      ? JSON.parse(JSON.stringify(props.item.value))
      : gatewayTemplate(props.kind);
    if (props.copy) {
      delete value.id;
      delete value.username;
      delete value.create_time;
      delete value.update_time;
    }
    raw.value = pretty(value);
    destinationChoice.value = destination.value;
    tab.value = "form";
    error.value = "";
    loading.value = true;
    try {
      const result = await Promise.all([
        api<GatewayValue>(`/gateway/schema/${props.kind}`),
        api<string[]>("/gateway/plugins"),
        api<{ items: GatewayItem[] }>("/gateway/resources/services"),
        api<{ items: GatewayItem[] }>("/gateway/resources/upstreams"),
      ]);
      schema.value = result[0];
      plugins.value = result[1];
      services.value = result[2].items;
      upstreams.value = result[3].items;
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      loading.value = false;
    }
  },
);
</script>
<template>
  <a-drawer
    :open="open"
    :title="`${editing ? '编辑' : copy ? '复制' : '新增'}${gatewayLabel(kind)}`"
    width="min(880px, 100vw)"
    :destroy-on-close="true"
    root-class-name="gateway-editor"
    @close="emit('close')"
  >
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <a-form layout="vertical" class="gateway-form" @finish="preview">
      <div class="gateway-form-grid">
        <a-form-item
          :label="
            kind === 'consumers'
              ? '用户名 / ID'
              : kind === 'secrets'
                ? '资源 ID（provider/id）'
                : '资源 ID'
          "
          required
        >
          <a-input
            v-model:value="id"
            :disabled="editing"
            aria-label="资源 ID"
            :placeholder="
              kind === 'plugin_metadata'
                ? '插件名称'
                : kind === 'secrets'
                  ? 'vault/internal'
                  : '唯一标识'
            "
            autocomplete="off"
          />
        </a-form-item>
        <a-form-item v-if="named" label="名称"
          ><a-input
            :value="draft.name"
            aria-label="资源名称"
            @update:value="patch({ name: $event })"
        /></a-form-item>
      </div>
      <a-tabs v-model:active-key="tab" :animated="false">
        <a-tab-pane key="form" tab="基础配置">
          <a-form-item v-if="described" label="描述"
            ><a-input
              :value="draft.desc"
              aria-label="资源描述"
              @update:value="patch({ desc: $event })"
          /></a-form-item>
          <template v-if="kind === 'routes'">
            <a-form-item label="域名"
              ><a-select
                mode="tags"
                :value="draft.hosts || (draft.host ? [draft.host] : [])"
                :token-separators="[',', ' ']"
                placeholder="app.example.com"
                aria-label="路由域名"
                @update:value="patch({ hosts: $event }, ['host'])"
            /></a-form-item>
            <a-form-item label="URI"
              ><a-select
                mode="tags"
                :value="draft.uris || (draft.uri ? [draft.uri] : [])"
                placeholder="/*"
                aria-label="路由 URI"
                @update:value="patch({ uris: $event }, ['uri'])"
            /></a-form-item>
            <div class="gateway-form-grid">
              <a-form-item label="请求方法"
                ><a-select
                  mode="multiple"
                  :value="draft.methods || []"
                  :options="
                    [
                      'GET',
                      'POST',
                      'PUT',
                      'DELETE',
                      'PATCH',
                      'HEAD',
                      'OPTIONS',
                      'CONNECT',
                      'TRACE',
                    ].map((value) => ({ value }))
                  "
                  placeholder="全部方法"
                  aria-label="请求方法"
                  @update:value="
                    $event.length
                      ? patch({ methods: $event })
                      : patch({}, ['methods'])
                  "
              /></a-form-item>
              <a-form-item label="优先级"
                ><a-input-number
                  :value="draft.priority || 0"
                  aria-label="路由优先级"
                  @update:value="patch({ priority: $event })"
              /></a-form-item>
            </div>
            <a-form-item label="启用路由"
              ><a-switch
                :checked="draft.status !== 0"
                aria-label="启用路由"
                @update:checked="patch({ status: $event ? 1 : 0 })"
            /></a-form-item>
          </template>
          <template
            v-if="['routes', 'services', 'stream_routes'].includes(kind)"
          >
            <a-form-item label="转发目标"
              ><a-radio-group
                v-model:value="destinationChoice"
                button-style="solid"
                class="gateway-destination"
                @change="changeDestination($event.target.value)"
              >
                <a-radio-button v-if="kind === 'routes'" value="service"
                  >服务</a-radio-button
                ><a-radio-button value="upstream">上游引用</a-radio-button
                ><a-radio-button value="inline">内嵌上游</a-radio-button
                ><a-radio-button v-if="kind !== 'stream_routes'" value="plugins"
                  >插件处理</a-radio-button
                >
              </a-radio-group></a-form-item
            >
            <a-form-item v-if="destinationChoice === 'service'" label="服务"
              ><a-select
                :value="draft.service_id"
                :options="
                  services.map((i) => ({
                    value: i.id,
                    label: `${i.value.name || i.id} · ${i.id}`,
                  }))
                "
                show-search
                option-filter-prop="label"
                aria-label="关联服务"
                @update:value="patch({ service_id: $event })"
            /></a-form-item>
            <a-form-item v-if="destinationChoice === 'upstream'" label="上游"
              ><a-select
                :value="draft.upstream_id"
                :options="
                  upstreams.map((i) => ({
                    value: i.id,
                    label: `${i.value.name || i.id} · ${i.id}`,
                  }))
                "
                show-search
                option-filter-prop="label"
                aria-label="关联上游"
                @update:value="patch({ upstream_id: $event })"
            /></a-form-item>
          </template>
          <template
            v-if="
              kind === 'upstreams' ||
              (['routes', 'services', 'stream_routes'].includes(kind) &&
                destinationChoice === 'inline')
            "
          >
            <div class="gateway-form-grid">
              <a-form-item label="负载均衡"
                ><a-select
                  :value="upstream.type || 'roundrobin'"
                  :options="
                    ['roundrobin', 'chash', 'ewma', 'least_conn'].map(
                      (value) => ({ value }),
                    )
                  "
                  aria-label="负载均衡"
                  @update:value="patchUpstream({ type: $event })"
              /></a-form-item>
              <a-form-item label="协议"
                ><a-select
                  :value="
                    upstream.scheme ||
                    (kind === 'stream_routes' ? 'tcp' : 'http')
                  "
                  :options="
                    ['http', 'https', 'grpc', 'grpcs', 'tcp', 'udp', 'tls'].map(
                      (value) => ({ value }),
                    )
                  "
                  aria-label="上游协议"
                  @update:value="patchUpstream({ scheme: $event })"
              /></a-form-item>
            </div>
            <div class="gateway-field-heading">
              <span class="section-label"
                >后端节点<HelpHint
                  label="后端节点说明"
                  text="支持权重、健康检查、超时、TLS、服务发现等完整字段；更多设置可在 JSON 配置中维护。" /></span
              ><a-button
                size="small"
                @click="
                  patchUpstream({
                    nodes: [...nodes, { host: '', port: 80, weight: 1 }],
                  })
                "
                ><PlusOutlined aria-hidden="true" />添加节点</a-button
              >
            </div>
            <div
              v-for="(node, index) in nodes"
              :key="index"
              class="gateway-node-row"
            >
              <a-input
                :value="node.host"
                :aria-label="`节点 ${index + 1} 地址`"
                placeholder="IP 或域名"
                @update:value="changeNode(index, { host: $event })"
              /><a-input-number
                :value="node.port"
                :min="1"
                :max="65535"
                :aria-label="`节点 ${index + 1} 端口`"
                @update:value="changeNode(index, { port: $event })"
              /><a-input-number
                :value="node.weight"
                :min="0"
                :aria-label="`节点 ${index + 1} 权重`"
                @update:value="changeNode(index, { weight: $event })"
              /><a-button
                type="text"
                danger
                :aria-label="`删除节点 ${index + 1}`"
                @click="
                  patchUpstream({ nodes: nodes.filter((_, i) => i !== index) })
                "
                ><DeleteOutlined aria-hidden="true"
              /></a-button>
            </div>
          </template>
          <template v-if="kind === 'ssls'">
            <a-form-item label="SNI 域名"
              ><a-select
                mode="tags"
                :value="draft.snis || []"
                aria-label="证书域名"
                @update:value="patch({ snis: $event })"
            /></a-form-item>
            <a-form-item label="证书 PEM"
              ><a-textarea
                :value="draft.cert"
                :rows="7"
                class="mono"
                aria-label="证书 PEM"
                spellcheck="false"
                @update:value="patch({ cert: $event })"
            /></a-form-item>
            <a-form-item
              ><template #label
                ><span class="section-label"
                  >私钥 PEM<HelpHint
                    label="敏感字段说明"
                    text="已保存的密钥以保留标记显示，保存时沿用原值；替换时粘贴新私钥。私钥不会写入审计日志。" /></span></template
              ><a-textarea
                :value="draft.key"
                :rows="4"
                class="mono"
                aria-label="私钥 PEM"
                autocomplete="off"
                spellcheck="false"
                @update:value="patch({ key: $event })"
            /></a-form-item>
          </template>
          <a-form-item v-if="kind === 'protos'" label="Protobuf 定义"
            ><a-textarea
              :value="draft.content"
              :rows="18"
              class="mono"
              aria-label="Protobuf 定义"
              spellcheck="false"
              @update:value="patch({ content: $event })"
          /></a-form-item>
          <template
            v-if="
              ![
                'upstreams',
                'ssls',
                'protos',
                'plugin_metadata',
                'secrets',
              ].includes(kind)
            "
          >
            <div class="gateway-field-heading">
              <span class="section-label"
                >插件<HelpHint
                  label="插件配置说明"
                  text="添加插件后，在 JSON 配置中填写完整参数。插件字段以当前实例的 Schema 为准。"
              /></span>
            </div>
            <div class="gateway-plugin-tags">
              <a-tag
                v-for="name in Object.keys(draft.plugins || {})"
                :key="name"
                closable
                @close.prevent="
                  patch({
                    plugins: Object.fromEntries(
                      Object.entries(draft.plugins).filter(([k]) => k !== name),
                    ),
                  })
                "
                >{{ name }}</a-tag
              >
            </div>
            <a-select
              v-model:value="pluginToAdd"
              placeholder="添加插件"
              :options="
                plugins
                  .filter((n) => !(n in (draft.plugins || {})))
                  .map((value) => ({ value }))
              "
              show-search
              aria-label="添加插件"
              @select="addPlugin"
            />
          </template>
          <div class="gateway-json-link">
            <a-button type="link" @click="tab = 'json'"
              ><CodeOutlined aria-hidden="true" />编辑完整 JSON</a-button
            ><HelpHint
              label="完整配置说明"
              text="JSON 编辑会保留表单之外的所有字段，可维护匹配变量、标签、认证、健康检查、重试、限流、超时和插件参数。"
            />
          </div>
        </a-tab-pane>
        <a-tab-pane key="json" tab="JSON 配置"
          ><div class="gateway-field-heading">
            <span class="section-label"
              >完整配置<HelpHint
                label="JSON 编辑说明"
                text="保留标记代表服务端原有敏感值；新增或复制资源时需要填写新值。资源 ID 在此面板顶部维护。" /></span
            ><a-button
              size="small"
              @click="
                () => {
                  try {
                    raw = pretty(parse());
                    error = '';
                  } catch {
                    error = 'JSON 格式无效';
                  }
                }
              "
              >格式化</a-button
            >
          </div>
          <a-textarea
            v-model:value="raw"
            class="gateway-json-editor mono"
            :rows="24"
            aria-label="完整 JSON 配置"
            spellcheck="false"
            autocomplete="off"
        /></a-tab-pane>
        <a-tab-pane key="schema" tab="字段 Schema"
          ><a-spin :spinning="loading">
            <pre class="gateway-code">{{
              schema?.available === false
                ? "当前版本未提供此资源的 Schema；保存时由网关进行校验。"
                : pretty(schema)
            }}</pre>
          </a-spin></a-tab-pane
        >
      </a-tabs>
    </a-form>
    <template #footer
      ><div class="gateway-editor-footer">
        <a-button @click="emit('close')">取消</a-button
        ><a-button type="primary" :loading="checking" @click="preview"
          >校验并预览</a-button
        >
      </div></template
    >
  </a-drawer>
</template>

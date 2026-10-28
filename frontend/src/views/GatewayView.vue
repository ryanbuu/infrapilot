<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { App as AntdApp } from "ant-design-vue";
import {
  ReloadOutlined,
  PlusOutlined,
  SearchOutlined,
  DeleteOutlined,
  CopyOutlined,
  DownloadOutlined,
  RollbackOutlined,
  ApiOutlined,
  ArrowRightOutlined,
  CodeOutlined,
} from "@ant-design/icons-vue";
import { api } from "../api";
import GatewayEditor from "../components/GatewayEditor.vue";
import HelpHint from "../components/HelpHint.vue";
import DataValues from "../components/DataValues.vue";
import {
  displayJson,
  gatewayKinds,
  gatewayLabel,
  gatewayPage,
  pretty,
} from "../gateway";
import type {
  GatewaySummary,
  GatewayListing,
  GatewayItem,
  GatewayMutation,
  GatewayPreview,
  GatewayBackup,
  GatewayValue,
} from "../gateway";
const props = defineProps<{ section: string }>();
const { message } = AntdApp.useApp();
const summary = ref<GatewaySummary>(),
  listing = ref<GatewayListing>(),
  runtime = ref<GatewayValue>(),
  backups = ref<GatewayBackup[]>([]),
  plugins = ref<string[]>([]),
  consumers = ref<GatewayItem[]>([]);
const loading = ref(false),
  checking = ref(false),
  saving = ref(false),
  error = ref(""),
  search = ref("");
const advanced = props.section === "advanced";
const requested = new URLSearchParams(location.hash.split("?")[1]).get(
  "resource",
);
const kind = ref(
  advanced
    ? gatewayKinds.some((k) => k.id === requested)
      ? requested!
      : "global_rules"
    : props.section,
);
const parent = ref<string>();
const editor = ref(false),
  selected = ref<GatewayItem>(),
  copying = ref(false),
  pending = ref<GatewayMutation>(),
  preview = ref<GatewayPreview>(),
  confirmation = ref(false),
  restoring = ref("");
const pluginName = ref(""),
  pluginSchema = ref<GatewayValue>(),
  pluginDrawer = ref(false),
  pluginLoading = ref(false);
const exportDialog = ref(false),
  includeSecrets = ref(false),
  exporting = ref(false);
const overview = props.section === "overview",
  pluginPage = props.section === "plugins",
  backupPage = props.section === "backups";
const title = computed(() =>
  overview
    ? "Application Gateway"
    : pluginPage
      ? "插件"
      : backupPage
        ? "变更备份"
        : advanced
          ? "高级配置"
          : gatewayLabel(kind.value),
);
const filtered = computed(() =>
  (listing.value?.items || []).filter((item) =>
    `${item.id} ${JSON.stringify(item.value)}`
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
const filteredPlugins = computed(() =>
  plugins.value.filter((n) =>
    n.toLowerCase().includes(search.value.toLowerCase()),
  ),
);
const filteredBackups = computed(() =>
  backups.value.filter((b) =>
    `${b.kind} ${b.resourceId} ${b.actor}`
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
const columns = [
  { title: "名称 / ID", key: "name", width: 240 },
  { title: "配置", key: "configuration" },
  { title: "插件", key: "plugins", width: 140 },
  { title: "状态", key: "status", width: 100 },
  { title: "操作", key: "actions", width: 160 },
];
const backupColumns = [
  { title: "时间", key: "time", width: 180 },
  { title: "对象", key: "resource" },
  { title: "操作", dataIndex: "action", width: 100 },
  { title: "操作人", dataIndex: "actor", width: 120 },
  { title: "", key: "restore", width: 110 },
];
const advancedOptions = [
  ...gatewayKinds
    .filter(
      (k) =>
        !["routes", "services", "upstreams", "consumers", "ssls"].includes(
          k.id,
        ),
    )
    .map((k) => ({ value: k.id, label: k.label })),
  { value: "runtime", label: "节点配置" },
];
const canCreate = computed(
  () =>
    !overview &&
    !pluginPage &&
    !backupPage &&
    kind.value !== "runtime" &&
    listing.value?.available &&
    (kind.value !== "credentials" || !!parent.value),
);
function count(kind: string) {
  return summary.value?.resources?.find((r) => r.kind === kind)?.count || 0;
}
function go(kind: string) {
  location.hash = gatewayPage(kind);
}
function displayName(item: GatewayItem) {
  if (kind.value === "ssls") return item.value.snis?.[0] || item.id;
  return item.value.name || item.value.username || item.id;
}
function statusText(item: GatewayItem) {
  return item.value.status === 0
    ? "已停用"
    : ["routes", "ssls", "stream_routes"].includes(kind.value)
      ? "已启用"
      : "已配置";
}
function configuration(item: GatewayItem) {
  const v = item.value;
  if (kind.value === "ssls") return v.snis?.length ? v.snis : ["默认证书"];
  if (kind.value === "consumers")
    return [v.desc || (v.group_id ? `消费者组 ${v.group_id}` : "独立消费者")];
  if (kind.value === "protos")
    return [v.desc || `${(v.content || "").split("\n").length} 行协议定义`];
  if (kind.value === "secrets")
    return [v.uri || v.endpoint || v.prefix || item.id];
  if (v.service_id) return [`服务 · ${v.service_id}`];
  if (v.upstream_id) return [`上游 · ${v.upstream_id}`];
  const u = kind.value === "upstreams" ? v : v.upstream;
  if (u?.nodes)
    return Array.isArray(u.nodes)
      ? u.nodes.map(
          (n: GatewayValue) =>
            `${String(n.host).includes(":") && !String(n.host).startsWith("[") ? `[${n.host}]` : n.host}:${n.port}`,
        )
      : Object.keys(u.nodes);
  return v.desc
    ? [v.desc]
    : Object.keys(v.plugins || {}).length
      ? Object.keys(v.plugins)
      : ["完整 JSON 配置"];
}
function matches(item: GatewayItem) {
  const v = item.value;
  return [
    ...(v.hosts || (v.host ? [v.host] : [])),
    ...(v.uris || (v.uri ? [v.uri] : [])),
  ];
}
function edit(item?: GatewayItem, copy = false) {
  selected.value = item;
  copying.value = copy;
  editor.value = true;
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    if (overview) summary.value = await api("/gateway");
    else if (pluginPage) plugins.value = await api("/gateway/plugins");
    else if (backupPage) backups.value = await api("/gateway/backups");
    else if (kind.value === "runtime")
      runtime.value = await api("/gateway/runtime");
    else if (kind.value !== "credentials" || parent.value)
      listing.value = await api(
        `/gateway/resources/${kind.value}${parent.value ? "?parent=" + encodeURIComponent(parent.value) : ""}`,
      );
    else {
      consumers.value = (
        await api<GatewayListing>("/gateway/resources/consumers")
      ).items;
      listing.value = undefined;
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
async function review(mutation: GatewayMutation) {
  checking.value = true;
  try {
    const result = await api<GatewayPreview>(
      `/gateway/resources/${kind.value}/preview`,
      { method: "POST", body: JSON.stringify(mutation) },
    );
    pending.value = mutation;
    preview.value = result;
    restoring.value = "";
    confirmation.value = true;
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    checking.value = false;
  }
}
async function reviewRestore(backup: GatewayBackup) {
  checking.value = true;
  try {
    preview.value = await api(`/gateway/backups/${backup.id}/preview`, {
      method: "POST",
    });
    restoring.value = backup.id;
    pending.value = undefined;
    confirmation.value = true;
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    checking.value = false;
  }
}
async function save() {
  if (!preview.value) return;
  saving.value = true;
  try {
    if (restoring.value)
      await api(`/gateway/backups/${restoring.value}/restore`, {
        method: "POST",
        body: JSON.stringify({ revision: preview.value.revision }),
      });
    else
      await api(`/gateway/resources/${kind.value}/changes`, {
        method: "POST",
        body: JSON.stringify(pending.value),
      });
    confirmation.value = false;
    editor.value = false;
    pending.value = undefined;
    preview.value = undefined;
    message.success("配置已保存并回读确认");
    await load();
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}
async function showPlugin(name: string) {
  pluginName.value = name;
  pluginDrawer.value = true;
  pluginSchema.value = undefined;
  pluginLoading.value = true;
  try {
    pluginSchema.value = await api(
      `/gateway/schema/routes?plugin=${encodeURIComponent(name)}`,
    );
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    pluginLoading.value = false;
  }
}
async function download() {
  exporting.value = true;
  try {
    const data = await api<GatewayValue>("/gateway/export", {
      method: "POST",
      body: JSON.stringify({ includeSecrets: includeSecrets.value }),
    });
    const url = URL.createObjectURL(
      new Blob([pretty(data)], { type: "application/json" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download = `application-gateway-${new Date().toISOString().replaceAll(":", "-")}.json`;
    a.click();
    URL.revokeObjectURL(url);
    exportDialog.value = false;
    message.success("配置已导出");
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    exporting.value = false;
  }
}
watch([kind, parent], () => {
  search.value = "";
  listing.value = undefined;
  void load();
});
onMounted(load);
</script>
<template>
  <section class="gateway-workspace" :aria-busy="loading || checking">
    <div class="page-heading">
      <div class="heading-with-help">
        <h1>{{ title }}</h1>
        <HelpHint
          label="Application Gateway 说明"
          text="管理应用入口、转发、认证、证书和插件。配置变更先校验和预览，再保存回滚备份与操作审计。密钥等敏感字段默认隐藏。"
        />
      </div>
      <a-button :loading="loading" @click="load"
        ><ReloadOutlined aria-hidden="true" />刷新</a-button
      >
    </div>
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <template v-if="overview">
      <a-empty
        v-if="summary && !summary.enabled"
        description="尚未连接 Application Gateway"
      />
      <template v-else>
        <div class="resource-metrics gateway-metrics">
          <div
            v-for="m in [
              { label: '路由', count: count('routes') },
              { label: '服务', count: count('services') },
              { label: '上游', count: count('upstreams') },
              { label: '已加载插件', count: summary?.plugins || 0 },
            ]"
            :key="m.label"
          >
            <span>{{ m.label }}</span
            ><strong>{{ loading ? "—" : m.count }}</strong>
          </div>
        </div>
        <div class="gateway-instance resource-panel">
          <ApiOutlined aria-hidden="true" />
          <div>
            <strong>{{ summary?.endpoint || "正在连接…" }}</strong
            ><span>APISIX {{ summary?.version || "—" }}</span>
          </div>
          <a-badge
            :status="
              summary?.resources?.find((r) => r.kind === 'routes')?.available
                ? 'success'
                : 'warning'
            "
            :text="
              summary?.resources?.find((r) => r.kind === 'routes')?.available
                ? '已连接'
                : '待检查'
            "
          />
        </div>
        <div class="gateway-section-heading">
          <h2>网关资源</h2>
          <a-button
            @click="
              includeSecrets = false;
              exportDialog = true;
            "
            ><DownloadOutlined aria-hidden="true" />导出配置</a-button
          >
        </div>
        <div class="gateway-resource-grid">
          <button
            v-for="r in summary?.resources || []"
            :key="r.kind"
            class="gateway-resource-card resource-panel"
            @click="go(r.kind)"
          >
            <div>
              <strong>{{ r.label }}</strong
              ><span>{{ r.available ? `${r.count} 个配置` : r.message }}</span>
            </div>
            <a-badge
              :status="r.available ? 'success' : 'default'"
            /><ArrowRightOutlined aria-hidden="true" />
          </button>
        </div>
      </template>
    </template>
    <template v-else>
      <div v-if="advanced" class="records-toolbar gateway-selectors">
        <a-select
          v-model:value="kind"
          :options="advancedOptions"
          aria-label="高级资源类型"
        /><a-select
          v-if="kind === 'credentials'"
          v-model:value="parent"
          :options="consumers.map((i) => ({ value: i.id, label: i.id }))"
          placeholder="选择消费者"
          show-search
          aria-label="所属消费者"
        /><HelpHint
          v-if="kind === 'runtime'"
          label="节点配置说明"
          text="此处只读展示实际挂载的启动配置，密钥已隐藏。监听端口、控制面、插件加载和 etcd 等静态参数由部署文件维护。动态资源在对应资源页面编辑。"
        />
      </div>
      <template v-if="kind === 'runtime'"
        ><a-empty
          v-if="runtime && !runtime.available"
          description="节点配置未挂载"
        />
        <pre v-else class="gateway-code resource-panel">{{
          runtime ? displayJson(runtime.configuration) : "正在读取…"
        }}</pre>
      </template>
      <template v-else>
        <div class="records-toolbar gateway-toolbar">
          <a-input
            v-model:value="search"
            :placeholder="
              pluginPage
                ? '搜索插件'
                : backupPage
                  ? '搜索对象或操作人'
                  : '搜索名称、ID、域名或配置'
            "
            aria-label="搜索网关资源"
            allow-clear
            ><template #prefix
              ><SearchOutlined aria-hidden="true" /></template></a-input
          ><a-button
            v-if="!backupPage"
            @click="
              includeSecrets = false;
              exportDialog = true;
            "
            ><DownloadOutlined aria-hidden="true" />导出配置</a-button
          ><a-button v-if="canCreate" type="primary" @click="edit()"
            ><PlusOutlined aria-hidden="true" />新增{{
              gatewayLabel(kind)
            }}</a-button
          >
        </div>
        <template v-if="pluginPage"
          ><div class="table-caption gateway-plugin-caption">
            <span>{{ filteredPlugins.length }} 个已加载插件</span
            ><HelpHint
              label="插件列表说明"
              text="列表来自当前实例。点击查看实时字段 Schema；在路由、服务、消费者或全局规则中配置插件参数。"
            />
          </div>
          <div class="gateway-plugin-grid">
            <button
              v-for="name in filteredPlugins"
              :key="name"
              class="gateway-plugin-card resource-panel"
              @click="showPlugin(name)"
            >
              <CodeOutlined aria-hidden="true" /><strong>{{ name }}</strong
              ><ArrowRightOutlined aria-hidden="true" />
            </button></div
        ></template>
        <template v-else-if="backupPage"
          ><div class="records-table-panel">
            <div class="table-caption">
              <span>{{ filteredBackups.length }} 份变更备份</span
              ><HelpHint
                label="回滚备份说明"
                text="每次变更前保存完整原值。恢复会重新校验、预览并备份当前状态。这里只显示最近 200 份备份，审计日志保留所有操作。"
              />
            </div>
            <a-table
              table-layout="fixed"
              :columns="backupColumns"
              :data-source="filteredBackups"
              row-key="id"
              :loading="loading"
              :pagination="{ pageSize: 12, showSizeChanger: true }"
              :scroll="{ x: 720 }"
              ><template #bodyCell="{ column, record }"
                ><template v-if="column.key === 'time'">{{
                  new Date(record.createdAt).toLocaleString("zh-CN", {
                    hour12: false,
                  })
                }}</template
                ><template v-else-if="column.key === 'resource'"
                  ><strong>{{ record.resourceId }}</strong>
                  <div class="record-fqdn">
                    {{ gatewayLabel(record.kind)
                    }}<template v-if="record.parent">
                      · {{ record.parent }}</template
                    >
                  </div></template
                ><template v-else-if="column.key === 'restore'"
                  ><a-button
                    type="link"
                    :loading="checking"
                    @click="reviewRestore(record)"
                    ><RollbackOutlined aria-hidden="true" />恢复前值</a-button
                  ></template
                ></template
              ></a-table
            >
          </div></template
        >
        <template v-else>
          <a-alert
            v-if="listing && !listing.available"
            :message="listing.message"
            type="info"
            show-icon
          />
          <a-empty
            v-else-if="kind === 'credentials' && !parent"
            description="请选择消费者以管理凭据"
          />
          <div v-else class="records-table-panel">
            <div class="table-caption">
              <span>{{ filtered.length }} 个{{ gatewayLabel(kind) }}</span
              ><HelpHint
                label="网关配置维护说明"
                text="基础表单和完整 JSON 编辑共用同一份配置，保留未知字段。保存时检查版本变化、写入私密备份并回读确认。"
              />
            </div>
            <a-table
              table-layout="fixed"
              class="gateway-desktop-table"
              :columns="columns"
              :data-source="filtered"
              row-key="id"
              :loading="loading"
              :pagination="{ pageSize: 12, showSizeChanger: true }"
              :scroll="{ x: 860 }"
              ><template #bodyCell="{ column, record }"
                ><template v-if="column.key === 'name'"
                  ><a-button
                    type="link"
                    class="gateway-name-button"
                    @click="edit(record)"
                    >{{ displayName(record) }}</a-button
                  >
                  <div class="record-fqdn mono">{{ record.id }}</div></template
                ><template v-else-if="column.key === 'configuration'"
                  ><DataValues :values="configuration(record)" />
                  <div
                    v-if="matches(record).length"
                    class="record-fqdn gateway-matches"
                  >
                    <DataValues :values="matches(record)" /></div></template
                ><template v-else-if="column.key === 'plugins'"
                  ><div class="gateway-plugin-values">
                    <a-tag
                      v-for="name in Object.keys(
                        record.value.plugins || {},
                      ).slice(0, 2)"
                      :key="name"
                      >{{ name }}</a-tag
                    ><span
                      v-if="Object.keys(record.value.plugins || {}).length > 2"
                      >+{{ Object.keys(record.value.plugins).length - 2 }}</span
                    ><span
                      v-if="!Object.keys(record.value.plugins || {}).length"
                      class="data-empty"
                      >—</span
                    >
                  </div></template
                ><template v-else-if="column.key === 'status'"
                  ><a-badge
                    :status="record.value.status === 0 ? 'default' : 'success'"
                    :text="statusText(record)" /></template
                ><template v-else-if="column.key === 'actions'"
                  ><div class="row-actions">
                    <a-button type="link" size="small" @click="edit(record)"
                      >编辑</a-button
                    ><a-button
                      type="text"
                      size="small"
                      aria-label="复制网关资源"
                      @click="edit(record, true)"
                      ><CopyOutlined aria-hidden="true" /></a-button
                    ><a-button
                      type="text"
                      size="small"
                      danger
                      aria-label="删除网关资源"
                      :loading="checking"
                      @click="
                        review({
                          action: 'DELETE',
                          id: record.id,
                          parent,
                          revision: record.revision,
                        })
                      "
                      ><DeleteOutlined aria-hidden="true"
                    /></a-button></div></template></template
            ></a-table>
            <div class="gateway-mobile-list">
              <a-spin v-if="loading" />
              <article
                v-for="record in filtered"
                :key="record.id"
                class="gateway-mobile-card"
              >
                <div class="gateway-mobile-heading">
                  <strong>{{ displayName(record) }}</strong
                  ><a-badge
                    :status="record.value.status === 0 ? 'default' : 'success'"
                    :text="statusText(record)"
                  />
                </div>
                <div class="record-fqdn mono">{{ record.id }}</div>
                <DataValues :values="configuration(record)" />
                <div
                  v-if="matches(record).length"
                  class="record-fqdn gateway-matches"
                >
                  <DataValues :values="matches(record)" />
                </div>
                <div class="gateway-plugin-tags">
                  <a-tag
                    v-for="name in Object.keys(record.value.plugins || {})"
                    :key="name"
                    >{{ name }}</a-tag
                  >
                </div>
                <div class="row-actions">
                  <a-button type="link" @click="edit(record)">编辑</a-button
                  ><a-button
                    type="text"
                    aria-label="复制网关资源"
                    @click="edit(record, true)"
                    ><CopyOutlined aria-hidden="true" /></a-button
                  ><a-button
                    type="text"
                    danger
                    aria-label="删除网关资源"
                    :loading="checking"
                    @click="
                      review({
                        action: 'DELETE',
                        id: record.id,
                        parent,
                        revision: record.revision,
                      })
                    "
                    ><DeleteOutlined aria-hidden="true"
                  /></a-button>
                </div>
              </article>
              <a-empty
                v-if="!loading && !filtered.length"
                description="暂无配置"
              />
            </div>
          </div>
        </template>
      </template>
    </template>
    <GatewayEditor
      :open="editor"
      :kind="kind"
      :item="selected"
      :parent="parent"
      :copy="copying"
      :checking="checking"
      @close="editor = false"
      @preview="review"
    />
    <a-modal
      :open="confirmation"
      :title="
        restoring
          ? '确认恢复配置'
          : preview?.action === 'DELETE'
            ? '确认删除配置'
            : '确认配置变更'
      "
      width="min(1040px, calc(100vw - 24px))"
      :confirm-loading="saving"
      :ok-button-props="{ danger: preview?.action === 'DELETE' }"
      :ok-text="
        restoring
          ? '确认恢复'
          : preview?.action === 'DELETE'
            ? '确认删除'
            : '确认保存'
      "
      cancel-text="返回"
      @ok="save"
      @cancel="confirmation = false"
    >
      <template v-if="preview"
        ><div class="gateway-preview-heading">
          <strong>{{ gatewayLabel(preview.kind) }} · {{ preview.id }}</strong
          ><a-tag color="blue">{{ preview.validation }}</a-tag>
        </div>
        <div class="change-preview gateway-diff">
          <div class="diff-before">
            <span>当前配置</span>
            <pre>{{ displayJson(preview.before) }}</pre>
          </div>
          <div class="diff-after">
            <span>目标配置</span>
            <pre>{{ displayJson(preview.after) }}</pre>
          </div>
        </div>
        <div class="gateway-preview-note section-label">
          保存前将生成回滚备份<HelpHint
            label="变更保护说明"
            text="仅更新此对象；关联引用会由网关检查。若其他管理端已修改配置，需要刷新后重新预览。管理接口不提供原子条件写，生产变更时请避免多个管理端同时修改同一对象。"
          /></div
      ></template>
    </a-modal>
    <a-drawer
      :open="pluginDrawer"
      :title="pluginName"
      width="min(760px, 100vw)"
      @close="pluginDrawer = false"
      ><a-spin :spinning="pluginLoading">
        <pre class="gateway-code">{{
          pluginSchema ? pretty(pluginSchema) : "正在读取 Schema…"
        }}</pre>
      </a-spin></a-drawer
    >
    <a-modal
      v-model:open="exportDialog"
      title="导出网关配置"
      ok-text="导出"
      cancel-text="取消"
      :confirm-loading="exporting"
      @ok="download"
      ><a-checkbox v-model:checked="includeSecrets"
        >包含证书私钥及认证密钥</a-checkbox
      ><HelpHint
        label="导出内容说明"
        text="默认隐藏敏感字段。勾选后文件包含完整动态配置，请妥善保管；节点配置中的管理凭据始终隐藏。导出操作会记录审计。" /><a-alert
        v-if="includeSecrets"
        message="导出文件将包含敏感凭据，请存放于私密位置。"
        type="warning"
        show-icon
        class="gateway-export-warning"
    /></a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { App as AntdApp } from "ant-design-vue";
import {
  ReloadOutlined,
  PlusOutlined,
  HistoryOutlined,
  DownloadOutlined,
  SearchOutlined,
} from "@ant-design/icons-vue";
import { api } from "../api";
import HelpHint from "../components/HelpHint.vue";
import ConfigSummary from "../components/ConfigSummary.vue";
import { sectionTitles, sectionKinds, sectionHelp, templates } from "../bind";
import type {
  BindState,
  BindObject,
  BindNode,
  BindFile,
  BindChange,
  BindPreview,
  BindBackup,
} from "../bind";
const props = defineProps<{ section: string }>();
const emit = defineEmits<{ changed: [] }>();
const { message } = AntdApp.useApp();
const data = ref<BindState>(),
  loading = ref(false),
  error = ref(""),
  search = ref(""),
  nodeId = ref("bind1"),
  kindFilter = ref("all");
const selected = computed(() =>
  data.value?.nodes.find((n) => n.id === nodeId.value),
);
const editable = computed(() => !!data.value?.writable && !loading.value);
const title = computed(() => sectionTitles[props.section] || "BIND");
const kinds = computed(() => sectionKinds[props.section] || []);
const rows = computed(() =>
  (selected.value?.objects || []).filter(
    (o) =>
      kinds.value.includes(o.kind) &&
      !(
        props.section === "zones" &&
        o.kind === "zone" &&
        o.fields.type === "forward"
      ) &&
      (kindFilter.value === "all" || o.kind === kindFilter.value) &&
      `${o.name} ${o.kind} ${o.view} ${Object.values(o.fields).join(" ")}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
);
const files = computed(() => selected.value?.files || []);
const telemetry = ref<{
    status: string;
    logs: string;
    statistics?: unknown;
    statisticsError?: string;
  }>(),
  telemetryLoading = ref(false);
const editor = ref(false),
  busy = ref(false),
  editError = ref(""),
  mode = ref("object"),
  editTitle = ref(""),
  source = ref(""),
  targetView = ref("_default"),
  both = ref(false),
  object = ref<BindObject>(),
  rawFile = ref<BindFile>(),
  templateKind = ref("acl");
const plan = ref<BindChange>(),
  preview = ref<BindPreview>(),
  reviewed = ref(false);
const zone = reactive({ name: "", ns1: "", ns2: "", ttl: 300, zoneFile: "" });
const newKey = reactive({ name: "", algorithm: "hmac-sha256" });
const backupsOpen = ref(false),
  backups = ref<BindBackup[]>([]),
  backupLoading = ref(false);
const commandId = ref("status"),
  commandNodes = ref<string[]>(["bind1"]),
  argumentsValue = reactive<Record<string, string | number>>({}),
  commandBusy = ref(false),
  commandError = ref(""),
  commandResult = ref<{
    nodes: { id: string; output?: string; error?: string; success: boolean }[];
  }>(),
  confirmCommand = ref(false);
const command = computed(() =>
  selected.value?.commands.find((c) => c.id === commandId.value),
);
const columns = [
  { title: "名称", key: "name", width: "27%" },
  { title: "配置类型", dataIndex: "kind", width: 110 },
  { title: "View", dataIndex: "view", width: 110 },
  { title: "配置摘要", key: "summary" },
  { title: "操作", key: "actions", width: 180 },
];
async function load() {
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/bind");
    if (!data.value!.nodes?.some((n) => n.id === nodeId.value))
      nodeId.value = data.value!.nodes?.[0]?.id || "";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(async () => {
  await load();
  if (props.section === "logging") await loadTelemetry();
});
async function changeNode(value: string) {
  nodeId.value = value;
  telemetry.value = undefined;
  commandNodes.value = [value];
  commandResult.value = undefined;
  if (props.section === "logging") await loadTelemetry();
}
async function loadTelemetry() {
  if (!selected.value?.online) return;
  telemetryLoading.value = true;
  try {
    telemetry.value = await api(`/bind/nodes/${nodeId.value}/telemetry`);
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    telemetryLoading.value = false;
  }
}
function resetEditor() {
  editor.value = true;
  editError.value = "";
  reviewed.value = false;
  preview.value = undefined;
  plan.value = undefined;
  object.value = undefined;
  rawFile.value = undefined;
  both.value = false;
  targetView.value = "_default";
  source.value = "";
}
function optionValue(name: string) {
  return (
    source.value
      .match(new RegExp("\\b" + name + "\\s+([^;{}]+);"))?.[1]
      ?.trim() || ""
  );
}
function setOption(name: string, value: string | number | undefined) {
  if (value === undefined || value === "") return;
  const directive = `${name} ${value};`;
  const pattern = new RegExp("\\b" + name + "\\s+[^;{}]+;");
  if (pattern.test(source.value))
    source.value = source.value.replace(pattern, directive);
  else {
    const close = source.value.lastIndexOf("}");
    source.value =
      source.value.slice(0, close) +
      "    " +
      directive +
      "\n" +
      source.value.slice(close);
  }
}
function createObject() {
  resetEditor();
  mode.value = "object";
  editTitle.value = "新增配置";
  templateKind.value = kinds.value[0] || "acl";
  source.value = templates[templateKind.value] || "";
}
function editObject(o: BindObject) {
  resetEditor();
  mode.value = "object";
  editTitle.value = "编辑配置";
  object.value = o;
  source.value = o.source;
  targetView.value = o.view;
  templateKind.value = o.kind;
}
function removeObject(o: BindObject) {
  resetEditor();
  mode.value = "delete";
  object.value = o;
  editTitle.value = "删除配置";
  source.value = o.source;
  targetView.value = o.view;
}
function editFile(f: BindFile) {
  resetEditor();
  mode.value = "file";
  editTitle.value = f.editable ? "编辑原生配置" : "查看配置";
  rawFile.value = f;
  source.value = f.content;
}
function createZone() {
  resetEditor();
  mode.value = "zone";
  editTitle.value = "新增主从区域";
  const main =
    selected.value?.protectedZones.find(
      (z) => !z.endsWith("arpa") && !z.startsWith("dhcp."),
    ) || "example.net";
  Object.assign(zone, {
    name: "",
    ns1: `bind1.${main}`,
    ns2: `bind2.${main}`,
    ttl: 300,
    zoneFile: "",
  });
}
function removeZone(o: BindObject) {
  resetEditor();
  mode.value = "zone-delete";
  editTitle.value = "删除主从区域";
  object.value = o;
  source.value = o.source;
}
function generateKey() {
  resetEditor();
  mode.value = "key";
  editTitle.value = "生成 TSIG 密钥";
  newKey.name = "";
  newKey.algorithm = "hmac-sha256";
  both.value = true;
}
async function makeKey() {
  busy.value = true;
  editError.value = "";
  try {
    const r = await api<{ source: string }>("/bind/keys/generate", {
      method: "POST",
      body: JSON.stringify(newKey),
    });
    source.value = r.source;
    mode.value = "object";
    templateKind.value = "key";
    editTitle.value = "新增 TSIG 密钥";
  } catch (e) {
    editError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
const newFileName = ref("custom.conf");
function createFile() {
  resetEditor();
  mode.value = "file-new";
  editTitle.value = "新增配置文件";
  newFileName.value = "custom.conf";
  source.value = "// BIND configuration\n";
}
function payload(): BindChange {
  const revision = data.value!.revision;
  if (mode.value === "file-new") {
    if (!/^[a-zA-Z0-9_-]{1,64}\.conf$/.test(newFileName.value))
      throw new Error("文件名须为字母、数字、横线或下划线，并以 .conf 结尾");
    const target = `${selected.value!.managedDirectory}/${newFileName.value}`;
    if (files.value.some((f) => f.path === target))
      throw new Error("配置文件已存在");
    const main = files.value.find((f) => f.path === "/etc/bind/named.conf");
    if (!main) throw new Error("未找到主配置");
    return {
      revision,
      nodes: [
        {
          id: nodeId.value,
          files: [
            {
              path: main.path,
              content: main.content + `\ninclude "${target}";\n`,
            },
            { path: target, content: source.value },
          ],
          assets: [],
        },
      ],
    };
  }

  if (mode.value === "restore") return plan.value!;
  if (mode.value === "file")
    return {
      revision,
      nodes: [
        {
          id: nodeId.value,
          files: [{ path: rawFile.value!.path, content: source.value }],
          assets: [],
        },
      ],
    };
  if (mode.value === "zone")
    return { revision, action: { operation: "zone-create", ...zone } };
  if (mode.value === "zone-delete")
    return {
      revision,
      action: { operation: "zone-delete", name: object.value!.name },
    };
  return {
    revision,
    action: {
      operation:
        mode.value === "delete" ? "delete" : object.value ? "update" : "create",
      node: nodeId.value,
      both: both.value,
      id: object.value?.id,
      kind: templateKind.value,
      name: object.value?.name,
      view: targetView.value,
      source: source.value,
    },
  };
}
async function review() {
  busy.value = true;
  editError.value = "";
  try {
    plan.value = payload();
    preview.value = await api("/bind/preview", {
      method: "POST",
      body: JSON.stringify(plan.value),
    });
    reviewed.value = true;
  } catch (e) {
    editError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function save() {
  busy.value = true;
  editError.value = "";
  try {
    await api("/bind/changes", {
      method: "POST",
      body: JSON.stringify(plan.value),
    });
    message.success("配置已校验并重载，回滚备份已保存");
    editor.value = false;
    backupsOpen.value = false;
    source.value = "";
    plan.value = undefined;
    await load();
    emit("changed");
  } catch (e) {
    editError.value = (e as Error).message;
    reviewed.value = false;
    await load();
  } finally {
    busy.value = false;
  }
}
async function showBackups() {
  backupsOpen.value = true;
  backupLoading.value = true;
  try {
    backups.value = await api("/bind/backups");
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    backupLoading.value = false;
  }
}
function restore(b: BindBackup) {
  resetEditor();
  mode.value = "restore";
  editTitle.value = "恢复配置";
  backupsOpen.value = false;
  plan.value = {
    revision: data.value!.revision,
    action: { operation: "restore", backupId: b.id },
  };
}
async function recover() {
  loading.value = true;
  try {
    data.value = await api("/bind/recover", { method: "POST" });
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function download(name: string, text: string) {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(
    new Blob([text], { type: "text/plain;charset=utf-8" }),
  );
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 1000);
}
function exportConfig() {
  download(`${nodeId.value}-named.conf`, selected.value?.effective || "");
}
function chooseCommand(value: string) {
  commandId.value = value;
  commandResult.value = undefined;
  commandError.value = "";
  for (const k of Object.keys(argumentsValue)) delete argumentsValue[k];
  const c = selected.value?.commands.find((x) => x.id === value);
  for (const f of c?.fields || [])
    if (!f.optional)
      argumentsValue[f.name] =
        f.type === "integer" ? f.min || 0 : f.choices?.[0] || "";
  if (c?.zone) {
    argumentsValue.zone =
      selected.value?.objects.find(
        (o) => o.kind === "zone" && o.view === selected.value?.primaryView,
      )?.name || "";
    argumentsValue.view = selected.value?.primaryView || "_default";
  }
}
async function runCommand() {
  if (command.value?.mutating && !confirmCommand.value) {
    confirmCommand.value = true;
    return;
  }
  confirmCommand.value = false;
  commandBusy.value = true;
  commandError.value = "";
  try {
    commandResult.value = await api("/bind/commands", {
      method: "POST",
      body: JSON.stringify({
        revision: data.value!.revision,
        nodes: commandNodes.value,
        command: commandId.value,
        arguments: argumentsValue,
        confirmed: !!command.value?.mutating,
      }),
    });
  } catch (e) {
    commandError.value = (e as Error).message;
  } finally {
    commandBusy.value = false;
  }
}
const fieldLabels: Record<string, string> = {
  name: "域名",
  view: "View",
  state: "状态",
  keyId: "密钥编号",
  serial: "序列号",
  level: "调试级别",
  lifetime: "有效期（秒）",
  initial: "连接超时",
  idle: "空闲超时",
  keepalive: "保持连接超时",
  advertised: "通告超时",
  count: "保留数量",
  kind: "数据类型",
};
function summaryFields(o: BindObject) {
  return Object.entries(o.fields)
    .filter(([key]) => !["secret", "database"].includes(key))
    .slice(0, 4)
    .map(([key, value]) => ({
      label: fieldLabels[key] || key,
      values: Array.isArray(value) ? value.map(String) : [String(value ?? "—")],
    }));
}
const statRows = computed(() => {
  const result: { key: string; value: string | number }[] = [];
  function walk(value: unknown, path: string) {
    if (value && typeof value === "object")
      for (const [k, v] of Object.entries(value))
        walk(v, path ? `${path}.${k}` : k);
    else if (typeof value === "number" || typeof value === "string")
      result.push({ key: path, value });
  }
  walk(telemetry.value?.statistics, "");
  return result;
});
</script>

<template>
  <section class="bind-page">
    <div class="page-heading">
      <div class="bind-title">
        <h1>{{ title }}</h1>
        <HelpHint label="功能说明" :text="sectionHelp[section] || ''" />
      </div>
      <a-space wrap
        ><a-button @click="showBackups"
          ><HistoryOutlined aria-hidden="true" /> 变更备份</a-button
        ><a-button :loading="loading" @click="load"
          ><ReloadOutlined aria-hidden="true" /> 刷新</a-button
        ></a-space
      >
    </div>
    <a-alert
      v-if="error || data?.error"
      type="error"
      show-icon
      :message="error || data?.error"
      ><template #action
        ><a-button size="small" @click="recover">重试恢复</a-button></template
      ></a-alert
    >
    <a-result
      v-if="data && !data.enabled"
      status="info"
      title="BIND 管理尚未接入"
    />
    <a-spin :spinning="loading">
      <template v-if="data?.enabled">
        <div class="bind-nodes">
          <button
            v-for="n in data.nodes"
            :key="n.id"
            type="button"
            :class="['bind-node', { 'is-selected': n.id === nodeId }]"
            @click="changeNode(n.id)"
          >
            <span
              ><a-badge :status="n.online ? 'success' : 'error'" />
              <strong>{{ n.label }}</strong></span
            ><span>{{ n.address }}</span
            ><span class="secondary">{{
              n.online
                ? n.version?.[0]?.split(" ").slice(0, 3).join(" ")
                : n.error
            }}</span>
          </button>
        </div>
        <template v-if="selected?.online">
          <div v-if="section === 'operations'" class="panel bind-command-panel">
            <a-form layout="vertical" @finish="runCommand">
              <a-form-item label="运行命令" name="command"
                ><a-select
                  :value="commandId"
                  show-search
                  option-filter-prop="label"
                  :options="
                    selected.commands.map((c) => ({
                      value: c.id,
                      label: c.label,
                    }))
                  "
                  @change="chooseCommand"
              /></a-form-item>
              <a-form-item label="目标节点" name="nodes"
                ><a-checkbox-group
                  v-model:value="commandNodes"
                  :options="
                    data.nodes.map((n) => ({
                      label: n.label,
                      value: n.id,
                      disabled: !n.online,
                    }))
                  "
              /></a-form-item>
              <div class="bind-form-grid">
                <template v-for="f in command?.fields" :key="f.name"
                  ><a-form-item
                    :label="fieldLabels[f.name] || f.name"
                    :name="f.name"
                    ><a-select
                      v-if="f.type === 'choice'"
                      v-model:value="argumentsValue[f.name]"
                      :options="f.choices?.map((x) => ({ value: x, label: x }))"
                      :allow-clear="f.optional" /><a-select
                      v-else-if="f.type === 'view'"
                      v-model:value="argumentsValue[f.name]"
                      :options="
                        selected.views.map((v) => ({ value: v, label: v }))
                      "
                      allow-clear /><a-input-number
                      v-else-if="f.type === 'integer'"
                      v-model:value="argumentsValue[f.name]"
                      :min="f.min"
                      :max="f.max"
                      style="width: 100%" /><a-input
                      v-else
                      v-model:value="argumentsValue[f.name]"
                      :placeholder="f.optional ? '可选' : ''" /></a-form-item
                ></template>
              </div>
              <div v-if="command?.zone" class="bind-form-grid">
                <a-form-item label="区域" name="zone"
                  ><a-select
                    v-model:value="argumentsValue.zone"
                    show-search
                    :options="
                      [
                        ...new Set(
                          selected.objects
                            .filter((o) => o.kind === 'zone')
                            .map((o) => o.name),
                        ),
                      ].map((x) => ({ label: x, value: x }))
                    " /></a-form-item
                ><a-form-item label="View" name="zoneView"
                  ><a-select
                    v-model:value="argumentsValue.view"
                    :options="
                      selected.views.map((x) => ({ value: x, label: x }))
                    "
                /></a-form-item>
              </div>
              <a-alert
                v-if="commandError"
                type="error"
                :message="commandError"
                show-icon
              />
              <a-button
                type="primary"
                html-type="submit"
                :disabled="!editable || !commandNodes.length"
                :loading="commandBusy"
                >执行</a-button
              >
            </a-form>
            <div
              v-for="r in commandResult?.nodes"
              :key="r.id"
              class="bind-command-result"
            >
              <strong>{{ r.id }}</strong
              ><a-tag :color="r.success ? 'success' : 'error'">{{
                r.success ? "已完成" : "失败"
              }}</a-tag>
              <pre>{{ r.output || r.error }}</pre>
            </div>
          </div>
          <template v-else-if="section === 'advanced'">
            <div class="record-tools">
              <a-input
                v-model:value="search"
                placeholder="搜索配置文件"
                allow-clear
                ><template #prefix
                  ><SearchOutlined aria-hidden="true" /></template></a-input
              ><a-button @click="exportConfig"
                ><DownloadOutlined aria-hidden="true" /> 导出展开配置</a-button
              ><a-button
                type="primary"
                :disabled="!editable"
                @click="createObject"
                ><PlusOutlined aria-hidden="true" /> 新增配置块</a-button
              >
            </div>
            <a-button :disabled="!editable" @click="createFile"
              >新增配置文件</a-button
            >
            <div class="panel bind-file-list">
              <div
                v-for="f in files.filter((f) => f.path.includes(search))"
                :key="f.path"
                class="bind-file-row"
              >
                <span class="monospace">{{ f.path }}</span
                ><a-tag v-if="!f.editable">条件转发维护</a-tag
                ><a-button type="link" @click="editFile(f)">{{
                  f.editable ? "编辑" : "查看"
                }}</a-button>
              </div>
            </div>
          </template>
          <template v-else>
            <div v-if="section === 'logging'" class="bind-telemetry">
              <div class="panel">
                <div class="bind-panel-heading">
                  <h3>运行状态</h3>
                  <a-button
                    size="small"
                    :loading="telemetryLoading"
                    @click="loadTelemetry"
                    >刷新统计</a-button
                  >
                </div>
                <pre>{{ telemetry?.status || selected.status }}</pre>
              </div>
              <div class="panel">
                <h3>服务日志</h3>
                <pre class="bind-logs">{{ telemetry?.logs || "—" }}</pre>
              </div>
              <div v-if="telemetry?.statistics" class="panel">
                <h3>
                  统计计数器
                  <HelpHint
                    label="功能说明"
                    text="计数器来自当前节点的 BIND statistics-channels。"
                  />
                </h3>
                <a-table
                  table-layout="fixed"
                  size="small"
                  :columns="[
                    { title: '计数器', dataIndex: 'key' },
                    { title: '值', dataIndex: 'value', width: 180 },
                  ]"
                  :data-source="statRows"
                  row-key="key"
                  :scroll="{ x: 600 }"
                  :pagination="{ pageSize: 12, showSizeChanger: true }"
                />
              </div>
              <a-alert
                v-else-if="telemetry?.statisticsError"
                type="warning"
                :message="telemetry.statisticsError"
                show-icon
              />
            </div>
            <div class="record-tools">
              <a-input v-model:value="search" placeholder="搜索配置" allow-clear
                ><template #prefix
                  ><SearchOutlined aria-hidden="true" /></template></a-input
              ><a-select
                v-model:value="kindFilter"
                aria-label="配置类型筛选"
                :options="[
                  { value: 'all', label: '全部类型' },
                  ...kinds.map((k) => ({ value: k, label: k })),
                ]"
              /><a-button
                v-if="section === 'zones'"
                type="primary"
                :disabled="!editable"
                @click="createZone"
                ><PlusOutlined aria-hidden="true" /> 新增主从区域</a-button
              ><a-button
                v-else-if="section === 'dnssec'"
                :disabled="!editable"
                @click="generateKey"
                >生成 TSIG 密钥</a-button
              ><a-button
                :type="section === 'zones' ? 'default' : 'primary'"
                :disabled="!editable"
                @click="createObject"
                ><PlusOutlined aria-hidden="true" /> 新增配置</a-button
              >
            </div>
            <div class="panel bind-table">
              <a-table
                table-layout="fixed"
                :columns="columns"
                :data-source="rows"
                row-key="id"
                :scroll="{ x: 850 }"
                :pagination="{ pageSize: 12, showSizeChanger: true }"
                ><template #bodyCell="{ column, record }"
                  ><template v-if="column.key === 'name'"
                    ><strong>{{ record.name }}</strong>
                    <div class="secondary">
                      {{
                        record.kind === "zone"
                          ? record.fields.type
                          : record.file
                      }}
                    </div></template
                  ><template v-else-if="column.key === 'summary'"
                    ><ConfigSummary :fields="summaryFields(record)" /></template
                  ><template v-else-if="column.key === 'actions'"
                    ><a-button
                      type="link"
                      size="small"
                      :disabled="!editable || !record.editable"
                      @click="editObject(record)"
                      >编辑</a-button
                    ><a-button
                      v-if="section === 'zones' && record.kind === 'zone'"
                      type="link"
                      danger
                      size="small"
                      :disabled="
                        !editable ||
                        !record.editable ||
                        selected.protectedZones.includes(record.name)
                      "
                      @click="removeZone(record)"
                      >删除区域</a-button
                    ><a-button
                      v-else
                      type="link"
                      danger
                      size="small"
                      :disabled="!editable || !record.editable"
                      @click="removeObject(record)"
                      >删除</a-button
                    ></template
                  ></template
                ></a-table
              >
            </div>
            <div class="bind-mobile-list">
              <article v-for="o in rows" :key="o.id" class="panel">
                <div class="bind-panel-heading">
                  <strong>{{ o.name }}</strong
                  ><a-tag>{{ o.kind }}</a-tag>
                </div>
                <div class="secondary">{{ o.view }}</div>
                <ConfigSummary :fields="summaryFields(o)" />
                <a-button
                  type="link"
                  :disabled="!editable || !o.editable"
                  @click="editObject(o)"
                  >编辑</a-button
                ><a-button
                  type="link"
                  danger
                  :disabled="
                    !editable ||
                    !o.editable ||
                    (o.kind === 'zone' &&
                      selected.protectedZones.includes(o.name))
                  "
                  @click="o.kind === 'zone' ? removeZone(o) : removeObject(o)"
                  >删除</a-button
                >
              </article>
              <a-empty v-if="!rows.length" />
            </div>
          </template>
        </template>
      </template>
    </a-spin>
    <a-drawer
      v-model:open="editor"
      :title="editTitle"
      width="min(960px, 100vw)"
      :mask-closable="!busy"
      :closable="!busy"
      :keyboard="!busy"
      @after-open-change="
        (open: boolean) => {
          if (!open) {
            source = '';
            plan = undefined;
          }
        }
      "
    >
      <a-alert v-if="editError" type="error" show-icon :message="editError" />
      <template v-if="reviewed && preview"
        ><a-alert
          type="success"
          show-icon
          :message="`${preview.nodesValidated} 个节点已通过原生配置校验`"
        />
        <div v-for="n in preview.after" :key="n.id" class="bind-review">
          <h3>{{ n.id }}</h3>
          <div v-for="f in n.files" :key="f.path">
            <h4 class="monospace">{{ f.path }}</h4>
            <div class="bind-diff">
              <div>
                <h4>变更前</h4>
                <pre>{{
                  preview.before
                    .find((b) => b.id === n.id)
                    ?.files.find((b) => b.path === f.path)?.content ||
                  "（新文件）"
                }}</pre>
              </div>
              <div>
                <h4>变更后</h4>
                <pre>{{ f.content }}</pre>
              </div>
            </div>
          </div>
        </div></template
      >
      <template v-else-if="mode === 'zone'"
        ><a-form layout="vertical"
          ><a-form-item label="区域名称" name="name" required
            ><a-input
              v-model:value="zone.name"
              aria-label="区域名称"
              placeholder="example.net"
          /></a-form-item>
          <div class="bind-form-grid">
            <a-form-item label="主名称服务器" name="ns1" required
              ><a-input
                v-model:value="zone.ns1"
                aria-label="主名称服务器" /></a-form-item
            ><a-form-item label="从名称服务器" name="ns2" required
              ><a-input v-model:value="zone.ns2" aria-label="从名称服务器"
            /></a-form-item>
          </div>
          <a-form-item label="默认 TTL（秒）" name="ttl"
            ><a-input-number
              v-model:value="zone.ttl"
              :min="30"
              :max="2147483647" /></a-form-item
          ><a-form-item name="zoneFile"
            ><template #label
              >初始区域数据
              <HelpHint
                label="功能说明"
                text="可选，使用标准区域文件语法；不填写时生成 SOA 和 NS。数据只在主节点初始化，从节点通过传送同步。不允许 $INCLUDE 或 $GENERATE。" /></template
            ><a-textarea
              v-model:value="zone.zoneFile"
              :rows="10"
              class="bind-code"
              placeholder="可选：导入标准区域文件" /></a-form-item></a-form
      ></template>
      <template v-else-if="mode === 'key'"
        ><a-form layout="vertical"
          ><a-form-item label="密钥名称" name="keyName" required
            ><a-input
              v-model:value="newKey.name"
              placeholder="application.example.net" /></a-form-item
          ><a-form-item label="算法" name="algorithm"
            ><a-select
              v-model:value="newKey.algorithm"
              :options="
                ['hmac-sha256', 'hmac-sha384', 'hmac-sha512'].map((v) => ({
                  value: v,
                  label: v,
                }))
              " /></a-form-item></a-form
      ></template>
      <template v-else-if="mode === 'restore'"
        ><a-alert type="info" show-icon message="恢复该变更之前的配置" />
        <p class="secondary">{{ plan?.action?.backupId }}</p></template
      >
      <template v-else
        ><a-form layout="vertical"
          ><template v-if="mode === 'object'"
            ><div class="bind-form-grid">
              <a-form-item label="配置类型" name="kind"
                ><a-select
                  v-model:value="templateKind"
                  :disabled="!!object"
                  :options="
                    Object.keys(templates).map((k) => ({ value: k, label: k }))
                  "
                  @change="
                    (v: string) => {
                      if (!object) source = templates[v] || '';
                    }
                  " /></a-form-item
              ><a-form-item label="作用域" name="scope"
                ><a-select
                  v-model:value="targetView"
                  :disabled="!!object"
                  :options="[
                    { value: '_default', label: '全局' },
                    ...(selected?.views || [])
                      .filter((v) => v !== '_default')
                      .map((v) => ({ value: v, label: v })),
                  ]"
              /></a-form-item>
            </div>
            <a-form-item
              ><a-checkbox v-model:checked="both">同时应用到两个节点</a-checkbox
              ><HelpHint
                label="功能说明"
                text="适合共享 ACL、密钥、签名策略等。节点地址、角色和 View 不同的配置请分别编辑，并在预览中核对。" /></a-form-item></template
          ><a-form-item
            v-if="mode === 'file-new'"
            label="文件名称"
            name="fileName"
            ><a-input v-model:value="newFileName" placeholder="custom.conf"
          /></a-form-item>
          <div
            v-if="mode === 'object' && templateKind === 'options'"
            class="bind-form-grid"
          >
            <a-form-item label="递归解析"
              ><a-select
                :value="optionValue('recursion') || undefined"
                :options="
                  ['yes', 'no'].map((x) => ({
                    value: x,
                    label: x === 'yes' ? '启用' : '关闭',
                  }))
                "
                @change="(v: string) => setOption('recursion', v)"
            /></a-form-item>
            <a-form-item label="DNSSEC 验证"
              ><a-select
                :value="optionValue('dnssec-validation') || undefined"
                :options="
                  ['auto', 'yes', 'no'].map((x) => ({ value: x, label: x }))
                "
                @change="(v: string) => setOption('dnssec-validation', v)"
            /></a-form-item>
            <a-form-item label="最大缓存容量"
              ><a-input
                :value="optionValue('max-cache-size')"
                placeholder="例如 256M 或 50%"
                @change="
                  (e: Event) =>
                    setOption(
                      'max-cache-size',
                      (e.target as HTMLInputElement).value,
                    )
                "
            /></a-form-item>
            <a-form-item label="最长缓存 TTL（秒）"
              ><a-input-number
                :value="Number(optionValue('max-cache-ttl')) || undefined"
                :min="0"
                :max="2147483647"
                style="width: 100%"
                @change="(v: number) => setOption('max-cache-ttl', v)"
            /></a-form-item>
          </div>
          <a-form-item name="source"
            ><template #label
              >{{ rawFile?.path || "原生配置" }}
              <HelpHint
                label="功能说明"
                text="支持当前 BIND 版本的完整配置语法。已有密钥使用占位符保留，不能手动改写占位符；发布前会校验并展示两侧差异。" /></template
            ><a-textarea
              v-model:value="source"
              :rows="22"
              class="bind-code"
              :readonly="
                mode === 'delete' ||
                mode === 'zone-delete' ||
                (mode === 'file' && !rawFile?.editable)
              "
              spellcheck="false" /></a-form-item></a-form
      ></template>
      <template #footer
        ><div class="bind-editor-footer">
          <a-button :disabled="busy" @click="editor = false">取消</a-button
          ><a-button v-if="reviewed" :disabled="busy" @click="reviewed = false"
            >返回编辑</a-button
          ><a-button
            v-if="mode === 'key'"
            type="primary"
            :loading="busy"
            :disabled="!newKey.name"
            @click="makeKey"
            >生成</a-button
          ><a-button
            v-else-if="!(mode === 'file' && !rawFile?.editable)"
            type="primary"
            :danger="mode === 'delete' || mode === 'zone-delete'"
            :loading="busy"
            :disabled="!editable"
            @click="reviewed ? save() : review()"
            >{{ reviewed ? "确认发布" : "校验并预览" }}</a-button
          >
        </div></template
      >
    </a-drawer>
    <a-drawer
      v-model:open="backupsOpen"
      title="BIND 变更备份"
      width="min(840px, 100vw)"
      ><a-spin :spinning="backupLoading"
        ><a-empty v-if="!backups.length" />
        <article v-for="b in backups" :key="b.id" class="panel bind-backup">
          <div class="bind-panel-heading">
            <strong>{{ b.name }}</strong
            ><a-tag
              :color="
                b.phase === 'complete'
                  ? 'success'
                  : b.phase === 'rolled-back'
                    ? 'default'
                    : 'warning'
              "
              >{{
                b.phase === "complete"
                  ? "已完成"
                  : b.phase === "rolled-back"
                    ? "已回滚"
                    : "待恢复"
              }}</a-tag
            >
          </div>
          <p>
            {{ new Date(b.createdAt).toLocaleString() }} · {{ b.actor }} ·
            {{ b.operation }}
          </p>
          <div class="secondary monospace">{{ b.id }}</div>
          <a-space wrap
            ><a-button
              size="small"
              @click="download(`bind-${b.id}.json`, JSON.stringify(b, null, 2))"
              >导出</a-button
            ><a-button
              size="small"
              :disabled="!editable || b.phase !== 'complete'"
              @click="restore(b)"
              >恢复此变更前配置</a-button
            ></a-space
          >
        </article></a-spin
      ></a-drawer
    >
    <a-modal
      v-model:open="confirmCommand"
      title="确认运行操作"
      ok-text="执行"
      :confirm-loading="commandBusy"
      @ok="runCommand"
      ><p>{{ command?.label }}</p>
      <p>{{ commandNodes.join("、") }}</p>
      <HelpHint
        label="功能说明"
        text="操作将立即执行。涉及签名、缓存、验证或动态更新状态的变更，请核对目标区域与节点。"
    /></a-modal>
  </section>
</template>

<style scoped>
.panel {
  padding: 20px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: var(--surface);
  min-width: 0;
}
.secondary {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.bind-mobile-list :deep(.config-summary) {
  margin: 16px 0;
}
.bind-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bind-page {
  display: grid;
  gap: 24px;
  min-width: 0;
}
.bind-page :deep(.ant-spin-container) {
  display: grid;
  gap: 24px;
  min-width: 0;
}
.bind-page .page-heading h1 {
  margin: 0;
}
.bind-nodes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.bind-node {
  border: 1px solid var(--border);
  background: var(--surface);
  color: inherit;
  text-align: left;
  display: grid;
  gap: 8px;
  padding: 20px;
  cursor: pointer;
  border-radius: 6px;
  min-width: 0;
}
.bind-node.is-selected {
  border-color: #1677ff;
  box-shadow: 0 0 0 1px #1677ff;
}
.bind-node span {
  overflow-wrap: anywhere;
}
.bind-node:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: 3px;
}
.bind-summary {
  overflow-wrap: anywhere;
  white-space: normal;
}
.bind-table {
  padding: 0;
  overflow: hidden;
  min-width: 0;
}
.bind-mobile-list {
  display: none;
}
.bind-file-list {
  padding: 0;
}
.bind-file-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border-bottom: 1px solid var(--border);
  min-width: 0;
}
.bind-file-row:last-child {
  border-bottom: 0;
}
.bind-file-row > span:first-child {
  flex: 1;
  overflow-wrap: anywhere;
  min-width: 0;
}
.bind-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.bind-code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  tab-size: 4;
}
.bind-page pre,
.bind-review pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  max-height: 520px;
  overflow: auto;
  margin: 12px 0 0;
}
.bind-panel-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.bind-panel-heading h3 {
  margin: 0;
}
.bind-telemetry {
  display: grid;
  gap: 20px;
  min-width: 0;
}
.bind-telemetry > .panel {
  min-width: 0;
  overflow: hidden;
}
.bind-logs {
  max-height: 360px !important;
}
.bind-editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.bind-diff {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.bind-diff > div {
  min-width: 0;
  border: 1px solid var(--border);
  padding: 12px;
  border-radius: 6px;
}
.bind-review {
  margin: 24px 0;
}
.bind-review h4 {
  overflow-wrap: anywhere;
}
.bind-backup {
  margin-bottom: 16px;
}
.bind-backup .ant-space {
  margin-top: 16px;
}
.bind-command-panel {
  max-width: 960px;
}
.bind-command-panel .ant-form > .ant-btn {
  margin-top: 8px;
}
.bind-command-result {
  margin-top: 24px;
}
.bind-page .record-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.bind-page .record-tools > .ant-input-affix-wrapper {
  flex: 1;
  min-width: 180px;
}
.bind-page .record-tools > .ant-select {
  min-width: 160px;
}
@media (max-width: 760px) {
  .bind-page {
    gap: 20px;
  }
  .bind-nodes {
    gap: 12px;
  }
  .bind-node {
    padding: 14px;
    font-size: 12px;
  }
  .bind-form-grid,
  .bind-diff {
    grid-template-columns: 1fr;
  }
  .bind-form-grid {
    gap: 0;
  }
  .bind-table {
    display: none;
  }
  .bind-mobile-list {
    display: grid;
    gap: 12px;
  }
  .bind-mobile-list strong {
    overflow-wrap: anywhere;
    min-width: 0;
  }
  .bind-file-row {
    flex-wrap: wrap;
    gap: 8px;
  }
  .bind-file-row > span:first-child {
    flex-basis: 100%;
  }
  .bind-page .page-heading {
    align-items: flex-start;
    gap: 12px;
  }
  .bind-page .record-tools > .ant-input-affix-wrapper {
    flex-basis: 100%;
  }
  .bind-page .record-tools > .ant-select {
    flex: 1;
    min-width: 130px;
  }
}
@media (hover: none) and (pointer: coarse) {
  .bind-code {
    font-size: 16px;
  }
}
</style>

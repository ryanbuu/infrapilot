<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { App as AntdApp } from "ant-design-vue";
import {
  ReloadOutlined,
  PlusOutlined,
  SearchOutlined,
  HistoryOutlined,
  DeleteOutlined,
} from "@ant-design/icons-vue";
import { api } from "../api";
import type {
  ForwardingRule,
  ForwardingState,
  ForwardingChange,
  ForwardingBackup,
} from "../forwarding";
import HelpHint from "../components/HelpHint.vue";
import DataValues from "../components/DataValues.vue";

const props = defineProps<{ admin: boolean }>();
const { message } = AntdApp.useApp();
const data = ref<ForwardingState>(),
  loading = ref(false),
  error = ref(""),
  search = ref("");
const editor = ref(false),
  busy = ref(false),
  editError = ref(""),
  preview = ref(false);
const previewValue = ref<{
  before: ForwardingRule[];
  after: ForwardingRule[];
}>();
const change = ref<ForwardingChange>();
const form = reactive({
  domain: "",
  transport: "DOT" as "DOT" | "DNS",
  forward: "only" as "only" | "first",
  port: 853,
  addresses: "",
  tlsServerName: "",
  description: "",
});
const backupsOpen = ref(false),
  backups = ref<ForwardingBackup[]>([]),
  backupsLoading = ref(false);
const canEdit = computed(
  () => props.admin && !!data.value?.writable && !loading.value,
);
const rows = computed(
  () =>
    data.value?.rules.filter((r) =>
      `${r.domain} ${r.addresses.join(" ")} ${r.tlsServerName} ${r.description}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
    ) || [],
);
const columns = [
  { title: "域名", key: "domain", width: "25%" },
  { title: "协议", key: "transport", width: 100 },
  { title: "上游", key: "upstream", width: "24%" },
  { title: "TLS 校验域名", dataIndex: "tlsServerName" },
  { title: "转发模式", key: "mode", width: 120 },
  { title: "操作", key: "actions", width: 150 },
];
const phaseLabels: Record<string, string> = {
  complete: "已完成",
  "rolled-back": "已回滚",
  committed: "待收尾",
  preparing: "待恢复",
  applying: "待恢复",
  "rollback-needed": "待恢复",
};
const operationLabels: Record<string, string> = {
  create: "新增",
  update: "修改",
  delete: "删除",
  restore: "恢复",
};

async function load() {
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/forwarding");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function edit(rule?: ForwardingRule) {
  Object.assign(
    form,
    rule
      ? { ...rule, addresses: rule.addresses.join("\n") }
      : {
          domain: "",
          transport: "DOT",
          forward: "only",
          port: 853,
          addresses: "",
          tlsServerName: "",
          description: "",
        },
  );
  change.value = {
    revision: data.value!.revision,
    operation: rule ? "update" : "create",
    originalDomain: rule?.domain,
  };
  preview.value = false;
  editError.value = "";
  editor.value = true;
}
function remove(rule: ForwardingRule) {
  change.value = {
    revision: data.value!.revision,
    operation: "delete",
    originalDomain: rule.domain,
  };
  Object.assign(form, { ...rule, addresses: rule.addresses.join("\n") });
  preview.value = false;
  editError.value = "";
  editor.value = true;
}
function changeProtocol(value: "DOT" | "DNS") {
  if (form.port === (form.transport === "DOT" ? 853 : 53))
    form.port = value === "DOT" ? 853 : 53;
  form.transport = value;
}
const isRestore = computed(() => change.value?.operation === "restore");
const isDelete = computed(() => change.value?.operation === "delete");
const editorTitle = computed(() =>
  isRestore.value
    ? "恢复条件转发"
    : isDelete.value
      ? "删除条件转发"
      : change.value?.operation === "update"
        ? "编辑条件转发"
        : "新增条件转发",
);
function payload(): ForwardingChange {
  return {
    ...change.value!,
    ...(!isRestore.value && !isDelete.value
      ? {
          rule: {
            ...form,
            addresses: form.addresses.split(/[\s,]+/).filter(Boolean),
            tlsServerName:
              form.transport === "DOT" ? form.tlsServerName.trim() : "",
          },
        }
      : {}),
  };
}
async function review() {
  busy.value = true;
  editError.value = "";
  try {
    previewValue.value = await api("/forwarding/preview", {
      method: "POST",
      body: JSON.stringify(payload()),
    });
    preview.value = true;
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
    await api("/forwarding/changes", {
      method: "POST",
      body: JSON.stringify(payload()),
    });
    message.success("两台节点已保存并重载");
    editor.value = false;
    backupsOpen.value = false;
    await load();
  } catch (e) {
    editError.value = (e as Error).message;
    preview.value = false;
    await load();
  } finally {
    busy.value = false;
  }
}
async function showBackups() {
  backupsOpen.value = true;
  backupsLoading.value = true;
  try {
    backups.value = await api("/forwarding/backups");
  } catch (e) {
    message.error((e as Error).message);
  } finally {
    backupsLoading.value = false;
  }
}
function restore(backup: ForwardingBackup) {
  change.value = {
    revision: data.value!.revision,
    operation: "restore",
    backupId: backup.id,
  };
  preview.value = false;
  editError.value = "";
  backupsOpen.value = false;
  editor.value = true;
}
async function recover() {
  loading.value = true;
  try {
    data.value = await api("/forwarding/recover", { method: "POST" });
    error.value = "";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function mode(rule: ForwardingRule) {
  return rule.forward === "only" ? "仅转发" : "优先转发";
}
</script>

<template>
  <div class="page-heading">
    <div class="heading-with-help">
      <h1>条件转发</h1>
      <HelpHint
        label="条件转发说明"
        text="按域名及其子域选择上游解析器。保存前校验两个节点，重载后回读对比；失败时尝试恢复，未确认恢复前暂停新变更。"
      />
    </div>
    <a-space
      ><a-button v-if="admin" @click="showBackups"
        ><HistoryOutlined aria-hidden="true" />变更备份</a-button
      ><a-button :loading="loading" @click="load"
        ><ReloadOutlined aria-hidden="true" />刷新状态</a-button
      ></a-space
    >
  </div>
  <a-alert
    v-if="error"
    :message="error"
    type="error"
    show-icon
    class="forwarding-alert"
  />
  <a-alert
    v-if="data && !data.enabled"
    message="条件转发尚未接入"
    type="info"
    show-icon
  />
  <template v-else-if="data">
    <a-alert
      v-if="data.error"
      :message="data.error"
      type="warning"
      show-icon
      class="forwarding-alert"
      ><template v-if="admin" #action
        ><a-button size="small" :loading="loading" @click="recover"
          >重试恢复</a-button
        ></template
      ></a-alert
    >
    <div class="forwarding-nodes">
      <div
        v-for="node in data.nodes"
        :key="node.id"
        class="forwarding-node surface"
      >
        <a-badge
          :status="
            node.online && !node.drift && !node.pending ? 'success' : 'error'
          "
          :text="node.label"
        />
        <a-tag
          :color="node.online && !node.drift && !node.pending ? 'green' : 'red'"
          >{{
            !node.online
              ? "无法连接"
              : node.drift
                ? "配置差异"
                : node.pending
                  ? "待恢复"
                  : "在线"
          }}</a-tag
        >
        <HelpHint
          v-if="node.error"
          :label="`${node.label}错误详情`"
          :text="node.error"
        />
      </div>
      <div class="forwarding-node surface">
        <span>配置一致性</span
        ><a-tag :color="data.consistent ? 'green' : 'orange'">{{
          data.consistent ? "一致" : "待核实"
        }}</a-tag>
      </div>
    </div>
    <div class="resource-toolbar forwarding-toolbar">
      <a-input
        v-model:value="search"
        placeholder="搜索域名、上游或备注"
        aria-label="搜索条件转发"
        allow-clear
        ><template #prefix><SearchOutlined aria-hidden="true" /></template
      ></a-input>
      <a-button v-if="admin" type="primary" :disabled="!canEdit" @click="edit()"
        ><PlusOutlined aria-hidden="true" />新增条件转发</a-button
      >
    </div>
    <section class="surface forwarding-list">
      <div class="section-heading">
        <span>{{ rows.length }} 条转发规则</span
        ><a-tag v-if="data.consistent" color="green">双节点一致</a-tag>
      </div>
      <a-table
        table-layout="fixed"
        class="forwarding-desktop"
        :data-source="rows"
        :columns="columns"
        :scroll="{ x: 1000 }"
        row-key="domain"
        :loading="loading"
        :pagination="{ pageSize: 12, showSizeChanger: false }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'domain'"
            ><strong>{{ record.domain }}</strong>
            <div v-if="record.description" class="record-fqdn">
              {{ record.description }}
            </div></template
          >
          <a-tag
            v-else-if="column.key === 'transport'"
            :color="record.transport === 'DOT' ? 'green' : undefined"
            >{{ record.transport === "DOT" ? "DoT" : "DNS" }}</a-tag
          >
          <template v-else-if="column.key === 'upstream'"
            ><DataValues
              :values="
                record.addresses.map(
                  (ip: string) =>
                    `${ip.includes(':') ? `[${ip}]` : ip}:${record.port}`,
                )
              "
          /></template>
          <template v-else-if="column.key === 'mode'">{{
            mode(record)
          }}</template>
          <template v-else-if="column.key === 'actions'"
            ><a-space
              ><a-button
                type="link"
                size="small"
                :disabled="!canEdit"
                @click="edit(record)"
                >编辑</a-button
              ><a-button
                type="text"
                danger
                size="small"
                aria-label="删除条件转发"
                :disabled="!canEdit"
                @click="remove(record)"
                ><DeleteOutlined aria-hidden="true" /></a-button></a-space
          ></template>
        </template>
      </a-table>
      <div class="forwarding-mobile">
        <a-empty v-if="!rows.length" />
        <article
          v-for="rule in rows"
          :key="rule.domain"
          class="forwarding-mobile-card"
        >
          <div class="forwarding-mobile-title">
            <strong>{{ rule.domain }}</strong
            ><a-tag :color="rule.transport === 'DOT' ? 'green' : undefined">{{
              rule.transport === "DOT" ? "DoT" : "DNS"
            }}</a-tag>
          </div>
          <div v-if="rule.description" class="muted">
            {{ rule.description }}
          </div>
          <dl>
            <dt>上游</dt>
            <dd>
              <DataValues
                :values="
                  rule.addresses.map(
                    (ip: string) =>
                      `${ip.includes(':') ? `[${ip}]` : ip}:${rule.port}`,
                  )
                "
              />
            </dd>
            <template v-if="rule.transport === 'DOT'"
              ><dt>TLS 校验域名</dt>
              <dd>{{ rule.tlsServerName }}</dd></template
            >
            <dt>转发模式</dt>
            <dd>{{ mode(rule) }}</dd>
          </dl>
          <a-space v-if="admin"
            ><a-button type="link" :disabled="!canEdit" @click="edit(rule)"
              >编辑</a-button
            ><a-button
              type="text"
              danger
              aria-label="删除条件转发"
              :disabled="!canEdit"
              @click="remove(rule)"
              ><DeleteOutlined aria-hidden="true" /></a-button
          ></a-space>
        </article>
      </div>
    </section>
  </template>
  <a-spin v-else-if="loading" />

  <a-drawer
    v-model:open="editor"
    :title="editorTitle"
    width="min(640px, 100vw)"
    :mask-closable="!busy"
    :closable="!busy"
    :keyboard="!busy"
    class="forwarding-editor"
    destroy-on-close
  >
    <a-alert
      v-if="editError"
      :message="editError"
      type="error"
      show-icon
      class="forwarding-alert"
    />
    <template v-if="preview && previewValue">
      <a-alert message="两台节点配置校验通过" type="success" show-icon />
      <div class="forwarding-preview">
        <h3>变更前</h3>
        <pre>{{ JSON.stringify(previewValue.before, null, 2) }}</pre>
        <h3>变更后</h3>
        <pre>{{ JSON.stringify(previewValue.after, null, 2) }}</pre>
      </div>
    </template>
    <a-form
      v-else-if="!isDelete && !isRestore"
      layout="vertical"
      :model="form"
      @finish="review"
    >
      <a-form-item
        label="域名"
        name="domain"
        :rules="[{ required: true, message: '请输入域名' }]"
        ><a-input
          v-model:value="form.domain"
          aria-label="转发域名"
          placeholder="example.com"
          autocapitalize="none"
          autocorrect="off"
      /></a-form-item>
      <div class="forwarding-form-grid">
        <a-form-item label="转发协议" name="transport"
          ><a-select
            :value="form.transport"
            @change="changeProtocol"
            aria-label="转发协议"
            :options="[
              { value: 'DOT', label: 'DNS over TLS (DoT)' },
              { value: 'DNS', label: 'DNS (UDP / TCP)' },
            ]" /></a-form-item
        ><a-form-item label="端口" name="port"
          ><a-input-number
            v-model:value="form.port"
            aria-label="上游端口"
            :min="1"
            :max="65535"
            :precision="0"
        /></a-form-item>
      </div>
      <a-form-item
        name="addresses"
        :rules="[{ required: true, message: '请输入上游 IP' }]"
        ><template #label
          >上游 IP
          <HelpHint
            label="上游地址说明"
            text="每行一个 IPv4 或 IPv6 地址，最多 8 个。上游地址使用相同的端口和 TLS 校验域名。" /></template
        ><a-textarea
          v-model:value="form.addresses"
          aria-label="上游 IP"
          :rows="3"
          placeholder="192.0.2.53"
          autocapitalize="none"
          autocorrect="off"
      /></a-form-item>
      <a-form-item
        v-if="form.transport === 'DOT'"
        label="TLS 校验域名"
        name="tlsServerName"
        :rules="[{ required: true, message: '请输入 TLS 校验域名' }]"
        ><a-input
          v-model:value="form.tlsServerName"
          aria-label="TLS 校验域名"
          placeholder="resolver.example.com"
          autocapitalize="none"
          autocorrect="off"
      /></a-form-item>
      <a-form-item name="forward"
        ><template #label
          >转发模式
          <HelpHint
            label="转发模式说明"
            text="仅转发：上游失败时返回失败；优先转发：上游失败后尝试自行递归解析，可能绕过上游过滤策略。" /></template
        ><a-select
          v-model:value="form.forward"
          aria-label="转发模式"
          :options="[
            { value: 'only', label: '仅转发' },
            { value: 'first', label: '优先转发' },
          ]"
      /></a-form-item>
      <a-form-item label="备注"
        ><a-input
          v-model:value="form.description"
          aria-label="转发备注"
          :maxlength="200"
      /></a-form-item>
    </a-form>
    <a-alert
      v-else-if="isDelete"
      :message="`删除 ${form.domain} 的转发规则？`"
      description="此域名将恢复使用节点的默认解析策略。"
      type="warning"
      show-icon
    />
    <a-alert
      v-else
      message="将两个节点恢复到此备份的变更前配置"
      type="warning"
      show-icon
    />
    <template #footer
      ><div class="forwarding-editor-footer">
        <a-button
          :disabled="busy"
          @click="preview ? (preview = false) : (editor = false)"
          >{{ preview ? "返回编辑" : "取消" }}</a-button
        ><a-button
          v-if="!preview"
          type="primary"
          :loading="busy"
          @click="review"
          >校验并预览</a-button
        ><a-button
          v-else
          type="primary"
          :danger="isDelete"
          :loading="busy"
          @click="save"
          >{{
            isDelete ? "确认删除" : isRestore ? "确认恢复" : "确认保存"
          }}</a-button
        >
      </div></template
    >
  </a-drawer>
  <a-drawer
    v-model:open="backupsOpen"
    title="条件转发变更备份"
    width="min(760px, 100vw)"
  >
    <a-spin :spinning="backupsLoading"
      ><a-empty v-if="!backups.length" />
      <article v-for="b in backups" :key="b.id" class="forwarding-backup">
        <div>
          <strong
            >{{ operationLabels[b.operation] || b.operation }} ·
            {{ b.domain }}</strong
          ><a-tag>{{ phaseLabels[b.phase] || b.phase }}</a-tag>
        </div>
        <p class="muted">
          {{ new Date(b.createdAt).toLocaleString() }} · {{ b.actor }}
        </p>
        <a-button
          :disabled="!canEdit || !['complete', 'rolled-back'].includes(b.phase)"
          @click="restore(b)"
          >恢复变更前配置</a-button
        >
      </article></a-spin
    >
  </a-drawer>
</template>

<style scoped>
.forwarding-alert {
  margin-bottom: 20px;
}
.forwarding-nodes {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
.forwarding-node {
  padding: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.forwarding-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.forwarding-toolbar > .ant-input-affix-wrapper {
  flex: 1;
  min-width: 0;
}
.forwarding-code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}
.forwarding-list {
  overflow: hidden;
}
.forwarding-desktop :deep(td) {
  overflow-wrap: anywhere;
}
.forwarding-list .section-heading {
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  border-bottom: 1px solid var(--border);
}
.forwarding-mobile {
  display: none;
}
.forwarding-form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 16px;
}
.forwarding-form-grid .ant-input-number {
  width: 100%;
}
.forwarding-editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.forwarding-preview h3 {
  margin-top: 24px;
}
.forwarding-preview pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--surface-inset);
  border-radius: 6px;
  padding: 16px;
  font-size: 12px;
}
.forwarding-backup {
  padding: 20px 0;
  border-bottom: 1px solid var(--border);
}
.forwarding-backup > div {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: space-between;
}
@media (max-width: 1100px) {
  .forwarding-nodes {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .forwarding-node {
    padding: 12px 16px;
  }
}
@media (max-width: 760px) {
  .forwarding-desktop {
    display: none;
  }
  .forwarding-mobile {
    display: block;
  }
  .forwarding-mobile-card {
    padding: 20px;
    border-bottom: 1px solid var(--border);
    overflow-wrap: anywhere;
  }
  .forwarding-mobile-card:last-child {
    border-bottom: 0;
  }
  .forwarding-mobile-title {
    display: flex;
    justify-content: space-between;
    gap: 12px;
  }
  .forwarding-mobile-title strong {
    min-width: 0;
  }
  .forwarding-mobile-card dl {
    margin: 16px 0 8px;
  }
  .forwarding-mobile-card dt {
    color: var(--text-secondary);
    margin-top: 12px;
  }
  .forwarding-mobile-card dd {
    margin: 4px 0 0;
  }
  .forwarding-toolbar {
    flex-wrap: wrap;
  }
  .forwarding-toolbar > .ant-input-affix-wrapper {
    flex-basis: 100%;
  }
  .forwarding-toolbar > .ant-btn {
    margin-left: auto;
  }
  .page-heading {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 16px;
  }
}
</style>

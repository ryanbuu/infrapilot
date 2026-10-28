<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ReloadOutlined, ClockCircleOutlined } from "@ant-design/icons-vue";
import { api } from "../api";
import type { AuditEvent } from "../types";
import HelpHint from "../components/HelpHint.vue";
import DataValues from "../components/DataValues.vue";
const rows = ref<AuditEvent[]>([]),
  loading = ref(false),
  error = ref(""),
  offset = ref(0),
  selected = ref<AuditEvent>(),
  drawer = ref(false);
const search = ref(""),
  resource = ref("ALL"),
  status = ref("ALL");
const filtered = computed(() =>
  rows.value.filter(
    (r) =>
      (resource.value === "ALL" ||
        (resource.value === "DHCP"
          ? r.recordType === "DHCP4"
          : resource.value === "GATEWAY"
            ? r.recordType === "GATEWAY"
            : resource.value === "FORWARDING"
              ? r.recordType === "FORWARDING"
              : !["DHCP4", "GATEWAY", "FORWARDING"].includes(r.recordType))) &&
      (status.value === "ALL" || status.value === r.status) &&
      `${r.recordName} ${r.actor} ${r.detail}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
);
const columns = [
  { title: "操作时间", key: "time", width: 180 },
  { title: "操作", key: "action", width: 120 },
  { title: "记录", key: "record" },
  { title: "操作人", dataIndex: "actor", width: 120 },
  { title: "状态", key: "status", width: 100 },
  { title: "", key: "details", width: 80 },
];
const actions: Record<string, string> = {
    CREATE: "新增",
    UPDATE: "修改",
    DELETE: "删除",
    CONFIG: "DHCP 配置",
    RESERVATION: "保留地址",
    RELEASE: "释放租约",
    DDNS: "重发 DNS",
    EXPORT: "导出配置",
    RESTORE: "恢复配置",
  },
  statuses: Record<string, string> = {
    SUCCESS: "已完成",
    FAILED: "失败",
    PENDING: "待核实",
  };
async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await api(`/audit?offset=${offset.value}&limit=50`);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function json(v: string) {
  try {
    const value = JSON.parse(v);
    return value && !Array.isArray(value) && Array.isArray(value.values)
      ? `${value.name} · ${value.type} · TTL ${value.ttl}s\n${value.values.join("\n")}`
      : value
        ? JSON.stringify(value, null, 2)
        : "无";
  } catch {
    return v;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="heading-with-help">
        <h1>操作审计</h1>
        <HelpHint
          label="审计说明"
          text="查看 DNS、DHCP、Application Gateway 的配置变更、同步与回读结果。"
        />
      </div>
    </div>
    <a-button :loading="loading" @click="load"
      ><ReloadOutlined />刷新日志</a-button
    >
  </div>
  <a-alert v-if="error" :message="error" type="error" show-icon />
  <div class="records-toolbar audit-toolbar">
    <a-input
      v-model:value="search"
      placeholder="搜索对象、操作人或结果"
      aria-label="搜索审计"
      allow-clear
    /><a-select
      v-model:value="resource"
      :options="[
        { value: 'ALL', label: '全部资源' },
        { value: 'DNS', label: 'DNS' },
        { value: 'DHCP', label: 'DHCP' },
        { value: 'GATEWAY', label: 'Application Gateway' },
        { value: 'FORWARDING', label: '条件转发' },
      ]"
      style="width: 140px"
      aria-label="审计资源"
    /><a-select
      v-model:value="status"
      :options="[
        { value: 'ALL', label: '全部结果' },
        { value: 'SUCCESS', label: '已完成' },
        { value: 'FAILED', label: '失败' },
        { value: 'PENDING', label: '待核实' },
      ]"
      style="width: 140px"
      aria-label="审计结果"
    />
  </div>
  <div class="records-table-panel">
    <div class="table-caption">
      <span class="section-label"
        >操作事件<HelpHint
          label="审计筛选说明"
          text="筛选仅作用于当前页，每页最多 50 条记录。"
      /></span>
    </div>
    <a-table
      table-layout="fixed"
      class="audit-desktop-table"
      :columns="columns"
      :data-source="filtered"
      row-key="id"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 760 }"
      ><template #bodyCell="{ column, record }"
        ><template v-if="column.key === 'time'"
          ><time class="audit-time" :datetime="record.occurredAt"
            ><span>{{
              new Date(record.occurredAt).toLocaleDateString("zh-CN")
            }}</span
            ><span class="record-fqdn">{{
              new Date(record.occurredAt).toLocaleTimeString("zh-CN", {
                hour12: false,
              })
            }}</span></time
          ></template
        ><template v-else-if="column.key === 'action'"
          ><a-tag
            :color="
              record.action === 'DELETE'
                ? 'red'
                : record.action === 'CREATE'
                  ? 'blue'
                  : 'orange'
            "
            >{{ actions[record.action] || record.action }}</a-tag
          ></template
        ><template v-else-if="column.key === 'record'"
          ><DataValues :values="record.recordName" />
          <div class="record-fqdn">
            {{
              record.recordType === "DHCP4"
                ? "DHCP"
                : record.recordType === "GATEWAY"
                  ? "Application Gateway"
                  : record.recordType === "FORWARDING"
                    ? "DNS · 条件转发"
                    : "DNS · " + record.recordType
            }}
            · {{ record.zoneId }}
          </div></template
        ><template v-else-if="column.key === 'status'"
          ><a-badge
            :status="
              record.status === 'SUCCESS'
                ? 'success'
                : record.status === 'FAILED'
                  ? 'error'
                  : 'warning'
            "
            :text="statuses[record.status]" /></template
        ><template v-else-if="column.key === 'details'"
          ><a-button
            type="link"
            size="small"
            @click="
              selected = record;
              drawer = true;
            "
            >详情</a-button
          ></template
        ></template
      ><template #emptyText
        ><div class="audit-empty">
          <ClockCircleOutlined />
          <h3>暂无审计记录</h3>
        </div></template
      ></a-table
    >
    <section class="audit-mobile-list" aria-label="审计记录">
      <a-empty v-if="!filtered.length && !loading" description="暂无审计记录" />
      <article
        v-for="record in filtered"
        :key="record.id"
        class="mobile-record"
      >
        <div class="mobile-record-heading">
          <a-tag>{{ actions[record.action] || record.action }}</a-tag>
          <a-badge
            :status="
              record.status === 'SUCCESS'
                ? 'success'
                : record.status === 'FAILED'
                  ? 'error'
                  : 'warning'
            "
            :text="statuses[record.status]"
          />
        </div>
        <div class="mobile-record-content">
          <DataValues :values="record.recordName" />
        </div>
        <div class="record-fqdn">{{ record.zoneId }} · {{ record.actor }}</div>
        <div class="audit-mobile-meta">
          <time class="audit-time" :datetime="record.occurredAt">{{
            new Date(record.occurredAt).toLocaleString("zh-CN", {
              hour12: false,
            })
          }}</time>
          <a-button
            type="link"
            size="small"
            @click="
              selected = record;
              drawer = true;
            "
            >详情</a-button
          >
        </div>
      </article>
    </section>
    <div class="audit-pagination">
      <a-button
        :disabled="offset === 0 || loading"
        @click="
          offset = Math.max(0, offset - 50);
          load();
        "
        >上一页</a-button
      ><span>第 {{ offset / 50 + 1 }} 页</span
      ><a-button
        :disabled="rows.length < 50 || loading"
        @click="
          offset += 50;
          load();
        "
        >下一页</a-button
      >
    </div>
  </div>
  <a-drawer :open="drawer" title="变更详情" :width="600" @close="drawer = false"
    ><template v-if="selected"
      ><a-descriptions :column="1" bordered size="small"
        ><a-descriptions-item label="操作编号"
          >#{{ selected.id }}</a-descriptions-item
        ><a-descriptions-item label="操作人">{{
          selected.actor
        }}</a-descriptions-item
        ><a-descriptions-item label="结果"
          >{{ statuses[selected.status] }} ·
          {{ selected.detail }}</a-descriptions-item
        ></a-descriptions
      >
      <div class="change-preview">
        <div class="diff-before">
          <span>变更前</span>
          <pre>{{ json(selected.beforeJson) }}</pre>
        </div>
        <div class="diff-after">
          <span>变更后</span>
          <pre>{{ json(selected.afterJson) }}</pre>
        </div>
      </div></template
    ></a-drawer
  >
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  ReloadOutlined,
  GlobalOutlined,
  ClusterOutlined,
  ArrowRightOutlined,
} from "@ant-design/icons-vue";
import type { Zone, DhcpState } from "../types";
import { api } from "../api";
import HelpHint from "../components/HelpHint.vue";
import DataValues from "../components/DataValues.vue";
const props = defineProps<{ zones: Zone[]; loading: boolean; demo: boolean }>();
const emit = defineEmits<{ select: [id: string] }>();
const dhcp = ref<DhcpState>(),
  error = ref(""),
  loading = ref(false),
  search = ref(""),
  tab = ref("dns");
const records = computed(() =>
  props.zones.reduce((n, z) => n + (z.recordCount || 0), 0),
);
const filtered = computed(() =>
  props.zones.filter((z) =>
    `${z.name} ${z.description}`
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
async function refresh() {
  loading.value = true;
  try {
    dhcp.value = await api("/dhcp");
    error.value = "";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
function go(page: string) {
  location.hash = "#/" + page;
}
onMounted(refresh);
</script>
<template>
  <div class="account-heading">
    <div class="resource-eyebrow">我的工作空间</div>
    <div class="page-heading">
      <div>
        <div class="heading-with-help">
          <h1>网络概览</h1>
          <HelpHint
            label="网络概览说明"
            text="查看 DNS 区域、DHCP 地址分配与高可用服务状态。"
          />
        </div>
      </div>
      <a-button :loading="loading" @click="refresh"
        ><ReloadOutlined />刷新状态</a-button
      >
    </div>
  </div>
  <div class="resource-metrics overview-metrics">
    <div>
      <span>DNS 区域</span><strong>{{ zones.length }}</strong
      ><small>{{ records }} 组解析记录</small>
    </div>
    <div>
      <span
        >DHCP 子网<HelpHint
          label="子网统计说明"
          text="已接入的 IPv4 地址分配网络。" /></span
      ><strong>{{ dhcp?.enabled ? dhcp.subnets.length : "—" }}</strong>
    </div>
    <div>
      <span
        >有效租约<HelpHint
          label="租约统计说明"
          text="统计已合并主备副本的有效租约，不重复计数。" /></span
      ><strong>{{
        dhcp?.enabled ? dhcp.leases.filter((l) => l.active).length : "—"
      }}</strong>
    </div>
    <div>
      <span>HA 在线节点</span
      ><strong
        >{{ dhcp?.enabled ? dhcp.nodes.filter((n) => n.online).length : "—" }}
        <small v-if="dhcp?.enabled">/ {{ dhcp.nodes.length }}</small></strong
      ><small>{{
        dhcp?.writable
          ? "HA 正常 · 配置一致"
          : dhcp?.enabled
            ? "请检查节点状态"
            : "未连接 DHCP 服务"
      }}</small>
    </div>
  </div>
  <a-alert v-if="error" :message="error" type="warning" show-icon />
  <div class="resource-tabs" role="tablist" aria-label="网络资源">
    <button
      role="tab"
      :aria-selected="tab === 'dns'"
      :class="{ active: tab === 'dns' }"
      @click="tab = 'dns'"
    >
      <GlobalOutlined />DNS 区域 <span>{{ zones.length }}</span></button
    ><button
      role="tab"
      :aria-selected="tab === 'dhcp'"
      :class="{ active: tab === 'dhcp' }"
      @click="tab = 'dhcp'"
    >
      <ClusterOutlined />DHCP 子网 <span>{{ dhcp?.subnets.length || 0 }}</span>
    </button>
  </div>
  <template v-if="tab === 'dns'"
    ><div class="records-toolbar resource-toolbar">
      <a-input
        v-model:value="search"
        placeholder="搜索 DNS 区域"
        aria-label="搜索 DNS 区域"
        allow-clear
      /><span class="muted">{{ filtered.length }} 个区域</span>
    </div>
    <section class="resource-panel">
      <div class="resource-list-header">
        <span>名称</span><span>管理方式 / 状态</span>
      </div>
      <a-skeleton v-if="props.loading" active /><button
        v-for="zone in filtered"
        :key="zone.id"
        class="resource-list-row"
        @click="emit('select', zone.id)"
      >
        <div class="resource-list-title">
          <GlobalOutlined />
          <div>
            <strong>{{ zone.name.replace(/\.$/, "") }}</strong>
            <p>{{ zone.description }}</p>
          </div>
        </div>
        <div class="resource-list-status">
          <a-tag>{{ zone.readOnly ? "动态注册" : "静态配置" }}</a-tag
          ><a-badge
            :status="zone.error ? 'error' : 'success'"
            :text="zone.error ? '读取失败' : '正常'"
          /><span>{{ zone.recordCount ?? "—" }} 组记录</span
          ><ArrowRightOutlined />
        </div></button
      ><a-empty
        v-if="!loading && !filtered.length"
        description="没有匹配的区域"
      /></section
  ></template>
  <template v-else
    ><section class="resource-panel">
      <div class="resource-list-header">
        <span>网络</span><span>地址池 / 保留地址</span>
      </div>
      <button
        v-for="s in dhcp?.subnets || []"
        :key="s.id"
        class="resource-list-row"
        @click="go('dhcp-subnets')"
      >
        <div class="resource-list-title">
          <ClusterOutlined />
          <div>
            <strong>{{ s.prefix }}</strong>
            <p>{{ s.domain || "子网 " + s.id }}</p>
          </div>
        </div>
        <div class="resource-list-status">
          <span>{{ s.pools.length }} 个地址池</span
          ><span>{{ s.reservations.length }} 个保留地址</span
          ><ArrowRightOutlined />
        </div></button
      ><a-empty
        v-if="!dhcp?.subnets.length"
        description="尚未连接 DHCP 子网"
      /></section
  ></template>
  <section class="workspace-services">
    <h2>服务与连接</h2>
    <div class="service-grid">
      <div class="service-summary">
        <GlobalOutlined />
        <div>
          <strong>BIND DNS</strong>
          <dl class="service-endpoints">
            <div>
              <dt>主节点</dt>
              <dd><DataValues :values="zones[0]?.primary" /></dd>
            </div>
            <div>
              <dt>从节点</dt>
              <dd><DataValues :values="zones[0]?.secondaries" /></dd>
            </div>
          </dl>
        </div>
        <a-badge
          :status="
            zones.length && zones.every((z) => !z.error) ? 'success' : 'warning'
          "
          :text="
            zones.length && zones.every((z) => !z.error) ? '正常' : '待检查'
          "
        />
      </div>
      <div class="service-summary">
        <ClusterOutlined />
        <div>
          <strong class="section-label"
            >DHCP 地址分配<HelpHint
              label="配置同步说明"
              text="配置变更同步到两台节点并持久化。"
          /></strong>
          <p>
            {{
              dhcp?.enabled ? "主备管理与租约查询已连接" : "尚未连接管理接口"
            }}
          </p>
        </div>
        <a-button v-if="dhcp?.enabled" type="link" @click="go('dhcp-ha')"
          >查看 HA</a-button
        >
      </div>
    </div>
  </section>
</template>

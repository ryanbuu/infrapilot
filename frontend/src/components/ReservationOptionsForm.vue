<script setup lang="ts">
import { computed } from "vue";
import { PlusOutlined, DeleteOutlined } from "@ant-design/icons-vue";
import type { ReservationSettings, DhcpOption } from "../types";
import HelpHint from "./HelpHint.vue";

const props = defineProps<{
  settings: ReservationSettings;
  defaults: Record<string, string>;
  clientClasses: string[];
  serverDomain: string;
  staticDnsAvailable: boolean;
}>();
const networkFields = [
  {
    key: "router",
    label: "网关",
    hint: "多个网关用逗号分隔，按优先顺序下发；地址须位于所属子网。",
  },
  {
    key: "dnsServers",
    label: "DNS 服务器",
    hint: "多个 IPv4 地址用逗号分隔，按填写顺序下发。",
  },
  {
    key: "domain",
    label: "域名",
    hint: "向客户端下发的域名后缀；动态注册仍遵循现有 DNS 更新策略。",
  },
  { key: "searchDomains", label: "搜索域", hint: "多个搜索域用逗号分隔。" },
  {
    key: "ntpServers",
    label: "NTP 服务器",
    hint: "时间服务器 IPv4 地址，多个地址用逗号分隔。",
  },
] as const;
const advanced = computed(() =>
  props.settings.nextServer ||
  props.settings.serverHostname ||
  props.settings.bootFileName ||
  props.settings.clientClasses.length ||
  props.settings.extraOptions.length
    ? ["advanced"]
    : [],
);
function inherited(key: string) {
  return props.defaults[key] ? `继承：${props.defaults[key]}` : "继承网络设置";
}
function addOption() {
  props.settings.extraOptions.push({
    space: "dhcp4",
    data: "",
    "csv-format": true,
  });
}
function sendMode(option: DhcpOption) {
  return option["never-send"]
    ? "never"
    : option["always-send"]
      ? "always"
      : "requested";
}
function setSendMode(option: DhcpOption, mode: string) {
  delete option["always-send"];
  delete option["never-send"];
  if (mode === "always") option["always-send"] = true;
  if (mode === "never") option["never-send"] = true;
}
function optionCode(option: DhcpOption, value: number | null) {
  if (value == null) delete option.code;
  else option.code = value;
}
</script>

<template>
  <a-form-item>
    <template #label
      ><span class="section-label"
        >DNS 维护方式<HelpHint
          label="DNS 维护方式说明"
          :text="`静态维护用于服务器名称，使用 ${serverDomain || '主区域'}，解析记录通过 DNS 页面维护；动态注册沿用所属网络的自动注册策略。保留地址用于固定分配或避免冲突，不代表客户端当前一定使用 DHCP。`" /></span
    ></template>
    <a-radio-group
      v-model:value="settings.dnsMode"
      aria-label="DNS 维护方式"
      :options="[
        { value: 'static', label: '静态维护', disabled: !staticDnsAvailable },
        { value: 'dynamic', label: '动态注册' },
      ]"
    />
  </a-form-item>
  <div class="reservation-section-heading">
    <h3>网络设置</h3>
    <HelpHint
      label="网络设置继承说明"
      text="留空使用所属网络的设置；填写后仅覆盖此设备。清空并保存可恢复继承，在客户端下次获取或续租时生效。"
    />
  </div>
  <div class="dhcp-options-grid reservation-network-grid">
    <a-form-item v-for="field in networkFields" :key="field.key">
      <template #label
        ><span class="section-label"
          >{{ field.label
          }}<HelpHint
            :label="field.label + '设置说明'"
            :text="field.hint" /></span
      ></template>
      <a-input
        v-model:value="settings[field.key]"
        :aria-label="'保留地址' + field.label"
        :placeholder="inherited(field.key)"
        allow-clear
        autocapitalize="none"
        autocorrect="off"
      />
    </a-form-item>
    <a-form-item>
      <template #label
        ><span class="section-label"
          >MTU<HelpHint
            label="MTU 设置说明"
            text="客户端接口的最大传输单元，单位字节。留空继承网络设置。" /></span
      ></template>
      <a-input-number
        v-model:value="settings.mtu"
        :min="68"
        :max="65535"
        :precision="0"
        :placeholder="inherited('mtu')"
        aria-label="保留地址 MTU"
      />
    </a-form-item>
  </div>
  <a-form-item label="备注">
    <a-input
      v-model:value="settings.comment"
      :maxlength="2048"
      aria-label="保留地址备注"
      allow-clear
    />
  </a-form-item>
  <!-- Long responsive forms must reflow without a temporary fixed animation height. -->
  <a-collapse
    :default-active-key="advanced"
    :open-animation="{ css: false }"
    class="reservation-advanced"
  >
    <a-collapse-panel key="advanced" header="高级设置">
      <div class="reservation-section-heading">
        <h3>网络启动</h3>
        <HelpHint
          label="网络启动说明"
          text="用于 PXE / BOOTP 启动。下列字段分别设置启动服务器地址、服务器名称与启动文件；DHCP 66 / 67 选项可在附加选项中单独配置。留空继承网络设置。"
        />
      </div>
      <div class="dhcp-options-grid">
        <a-form-item label="启动服务器 IP"
          ><a-input
            v-model:value="settings.nextServer"
            :placeholder="inherited('nextServer')"
            aria-label="启动服务器 IP"
            allow-clear
        /></a-form-item>
        <a-form-item label="启动服务器名称"
          ><a-input
            v-model:value="settings.serverHostname"
            :placeholder="inherited('serverHostname')"
            aria-label="启动服务器名称"
            allow-clear
        /></a-form-item>
        <a-form-item label="启动文件" class="reservation-full-row"
          ><a-input
            v-model:value="settings.bootFileName"
            :placeholder="inherited('bootFileName')"
            aria-label="启动文件"
            allow-clear
        /></a-form-item>
      </div>
      <a-form-item
        ><template #label
          ><span class="section-label"
            >客户端分类<HelpHint
              label="客户端分类说明"
              text="为此设备分配现有分类或标签，用于选项与地址分配策略。分类定义继续保留，不会因编辑此设备而修改。" /></span
        ></template>
        <a-select
          v-model:value="settings.clientClasses"
          mode="tags"
          :options="clientClasses.map((value) => ({ value }))"
          aria-label="保留地址客户端分类"
          placeholder="选择或输入分类"
        />
      </a-form-item>
      <div class="reservation-section-heading reservation-extra-heading">
        <h3>附加 DHCP 选项</h3>
        <HelpHint
          label="附加 DHCP 选项说明"
          text="支持标准、自定义及厂商选项。代码或名称至少填写一项；自定义选项须已有相应定义。CSV 按选项类型填写，十六进制用于原始值。网关、DNS 等常用选项请使用上方字段。"
        />
        <a-button size="small" @click="addOption" aria-label="添加 DHCP 选项"
          ><PlusOutlined />添加选项</a-button
        >
      </div>
      <div
        v-for="(option, index) in settings.extraOptions"
        :key="index"
        class="reservation-option"
        :aria-label="'DHCP 选项 ' + (index + 1)"
      >
        <div class="reservation-option-fields">
          <a-form-item label="代码"
            ><a-input-number
              :value="option.code"
              :min="0"
              :max="65535"
              :precision="0"
              :aria-label="'选项代码 ' + (index + 1)"
              @update:value="
                (value: unknown) => optionCode(option, value as number | null)
              "
          /></a-form-item>
          <a-form-item label="名称"
            ><a-input
              v-model:value="option.name"
              :aria-label="'选项名称 ' + (index + 1)"
              placeholder="例如 tftp-server-name"
          /></a-form-item>
          <a-form-item label="选项空间"
            ><a-input
              v-model:value="option.space"
              :aria-label="'选项空间 ' + (index + 1)"
              placeholder="dhcp4"
          /></a-form-item>
          <a-form-item label="格式"
            ><a-select
              :value="option['csv-format'] === false ? 'hex' : 'csv'"
              :aria-label="'选项格式 ' + (index + 1)"
              :options="[
                { value: 'csv', label: 'CSV' },
                { value: 'hex', label: '十六进制' },
              ]"
              @update:value="
                (value: unknown) => (option['csv-format'] = value === 'csv')
              "
          /></a-form-item>
          <a-form-item label="发送策略"
            ><a-select
              :value="sendMode(option)"
              :aria-label="'选项发送策略 ' + (index + 1)"
              :options="[
                { value: 'requested', label: '按客户端请求' },
                { value: 'always', label: '始终发送' },
                { value: 'never', label: '禁止发送' },
              ]"
              @update:value="
                (value: unknown) => setSendMode(option, value as string)
              "
          /></a-form-item>
          <a-button
            type="text"
            danger
            :aria-label="'删除 DHCP 选项 ' + (index + 1)"
            @click="settings.extraOptions.splice(index, 1)"
            ><DeleteOutlined
          /></a-button>
        </div>
        <a-form-item label="选项值"
          ><a-input
            v-model:value="option.data"
            :maxlength="4096"
            :aria-label="'选项值 ' + (index + 1)"
        /></a-form-item>
      </div>
    </a-collapse-panel>
  </a-collapse>
</template>

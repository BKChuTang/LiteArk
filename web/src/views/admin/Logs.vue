<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">操作日志</h2>
      <span><el-button :icon="Download" @click="exportCsv">导出 CSV</el-button><el-button :icon="Refresh" @click="load">刷新</el-button></span>
    </div>
    <el-card style="margin-bottom:16px">
      <el-form :inline="true" :model="query">
        <el-form-item label="时间"><el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始" end-placeholder="结束" style="width:230px" /></el-form-item>
        <el-form-item label="用户"><el-input v-model="query.username" placeholder="用户名" clearable style="width:130px" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.action" placeholder="全部类型" clearable filterable allow-create
            style="width:150px" @change="search">
            <el-option v-for="a in actionOptions" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-table :data="list" v-loading="loading" border style="width:100%">
      <el-table-column prop="createdAt" label="时间" width="170" header-align="center" />
      <el-table-column label="操作人" width="120" header-align="center">
        <template #default="{ row }">
          <span :class="{ muted: !row.username }">{{ row.username || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" align="center" header-align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="actionTag(row.action)" effect="light">{{ row.action }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="登录端" width="96" align="center" header-align="center">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" type="info">{{ clientText(row.clientType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作对象" min-width="190" header-align="center" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ muted: fmtResource(row) === '—' }">{{ fmtResource(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="330" header-align="center" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ muted: fmtDetail(row) === '—' }">{{ fmtDetail(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="IP" width="130" header-align="center">
        <template #default="{ row }">{{ fmtIp(row.ip) }}</template>
      </el-table-column>
      <el-table-column label="结果" width="86" align="center" header-align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.result === 'success' ? 'success' : 'danger'">{{ row.result === 'success' ? '成功' : '失败' }}</el-tag>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无日志" /></template>
    </el-table>
    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next" :total="total"
        v-model:current-page="query.page" v-model:page-size="query.pageSize" :page-sizes="[20,50,100]"
        @current-change="load" @size-change="load" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Refresh, Download } from '@element-plus/icons-vue'
import { adminApi } from '../../api'

const list = ref([]), total = ref(0), loading = ref(false), range = ref([])
const query = reactive({ page: 1, pageSize: 20, username: '', action: '', start: '', end: '' })

const PERM_CN = { view: '查看', edit: '编辑', download: '下载', upload: '上传', delete: '删除', share: '分享' }

/** 类型筛选的可选值，也允许手动输入旧版本的操作名 */
const actionOptions = [
  '登录', '登录失败', '上传', '下载', '批量下载', '新建文件夹', '重命名', '复制', '剪切',
  '删除到回收站', '还原', '永久删除', '清空回收站', '在线编辑', '创建共享', '修改共享', '撤销共享',
  '授权文件夹', '撤销文件夹授权', '设置文件夹权限', '删除目录权限', '新增用户', '修改用户', '重置密码',
  '删除用户', '新增角色', '删除角色', '修改系统配置', '导出操作日志',
  '发起二次验证绑定', '完成二次验证绑定', '绑定二次验证', '关闭二次验证', '重新生成恢复码',
  '新增信任设备', '删除信任设备', '信任设备过期', '登录二次验证失败',
  '设置二次验证权限', '批量设置二次验证权限', '重置二次验证', '管理员身份核验'
]

/** 端类型：PC / Mobile → 电脑端 / 手机端 */
function clientText(t) {
  if (!t) return '电脑端'
  return String(t).toLowerCase() === 'mobile' ? '手机端' : '电脑端'
}

/** IPv6 回环与映射地址统一成 IPv4，兼容历史数据 */
function fmtIp(ip) {
  const s = (ip == null ? '' : String(ip)).trim()
  if (!s) return '—'
  const lower = s.toLowerCase()
  if (lower === '::1' || lower === '0:0:0:0:0:0:0:1' || lower === '::0.0.0.1') return '127.0.0.1'
  const mapped = lower.match(/^(?:::ffff:|0:0:0:0:0:ffff:)(.+)$/)
  if (mapped && mapped[1].includes('.')) return mapped[1]
  return s
}

/** 操作类型配色，便于扫读 */
function actionTag(action) {
  const a = action || ''
  if (a.includes('失败')) return 'danger'
  if (/登录|注册|密码/.test(a)) return 'info'
  if (/删除|清空|撤销/.test(a)) return 'warning'
  if (/上传|下载|复制|剪切|新建|创建/.test(a)) return 'success'
  if (/权限|授权|角色|配置|二次验证|恢复码|信任设备/.test(a)) return 'primary'
  return ''
}

/** 操作对象：优先用后端写好的业务名称，历史数据做兜底 */
function fmtResource(row) {
  const r = row.resource == null ? '' : String(row.resource).trim()
  if (!r) {
    if (/登录|注册|修改密码/.test(row.action || '')) return '—'
    if ((row.action || '') === '清空回收站') return '回收站'
    return '—'
  }
  if (/^user:/.test(r)) return '用户：' + r.slice(5)
  if (/^role:/.test(r)) return '角色：' + r.slice(5)
  // 早期版本只记了内部编号，名称已不可还原
  if (/^(id|rule):\d+$/.test(r)) return '（早期记录）'
  if (/^\d+\s*项$/.test(r)) return r.replace(/\s*项$/, ' 个项目')
  if (/^\d+\s*个文件$/.test(r)) return r
  return r
}

/** 说明：把历史数据里的 token、权限位英文、内部编号转成人话 */
function fmtDetail(row) {
  let d = row.detail == null ? '' : String(row.detail).trim()
  if (!d) return '—'
  // 只剩内部编号时没有可读信息
  if (/^id:\d+$/.test(d)) return '—'

  // 早期版本把共享 token 写进了详情，对管理员没有意义
  d = d.replace(/token:[0-9a-f]+/gi, '').trim()
  // [view, download] → 查看、下载
  d = d.replace(/\[([a-z_,\s]+)\]/gi, (_, g) => {
    const parts = g.split(',').map(x => PERM_CN[x.trim()] || x.trim()).filter(Boolean)
    return parts.join('、')
  })
  d = d.replace(/权限[:：]\s*(?=[^\s])/g, '权限：')
  // 父目录:0 → 位置：根目录；目录:部门共享资料 → 文件夹「部门共享资料」
  d = d.replace(/父目录[:：]0(?!\d)/g, '位置：根目录')
  d = d.replace(/父目录[:：](\d+)/g, '位置：目录 $1')
  d = d.replace(/目标[:：](\d+)/g, '目标目录 $1')
  d = d.replace(/目录[:：]\s*(\d+)(?!\S)/g, '目录 $1')
  d = d.replace(/目录[:：]\s*([^\s\d][^\s]*)/g, '文件夹「$1」')
  d = d.replace(/按角色[:：]\d+/g, '按角色授权')
  // 版本 -> 5 → 第 5 个版本
  d = d.replace(/版本\s*->\s*(\d+)/g, '第 $1 个版本')
  d = d.replace(/(\d+)\s*字符$/, '共 $1 字符')
  d = d.replace(/\s{2,}/g, ' ').replace(/^[·,，\s]+|[·,，\s]+$/g, '')
  return d || '—'
}

async function load() {
  loading.value = true
  try {
    const p = { ...query }
    if (range.value?.length === 2) { p.start = range.value[0]; p.end = range.value[1] }
    const r = await adminApi.logs(p); list.value = r.data.list; total.value = r.data.total
  } catch (e) {} finally { loading.value = false }
}
function exportCsv() {
  const p = {}
  if (query.username) p.username = query.username
  if (query.action) p.action = query.action
  if (range.value?.length === 2) { p.start = range.value[0]; p.end = range.value[1] }
  window.open(adminApi.logsExportUrl(p), '_blank')
}
function search() { query.page = 1; load() }
function reset() { query.username = ''; query.action = ''; range.value = []; search() }
onMounted(load)
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.muted { color: var(--el-text-color-placeholder); }
</style>

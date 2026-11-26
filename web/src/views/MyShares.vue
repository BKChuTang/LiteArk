<template>
  <div class="page-container">
    <div class="page-header"><h2 class="page-title">我的分享</h2><el-button :icon="Refresh" @click="load">刷新</el-button></div>
    <el-table :data="list" v-loading="loading" border style="width:100%">
      <el-table-column label="文件名" min-width="180">
        <template #default="{ row }">{{ row.fileName || ('文件 #' + row.fileId) }}</template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }"><el-tag size="small" :type="row.shareType === 'internal' ? 'warning' : 'primary'" effect="plain">{{ row.shareType === 'internal' ? '内部分享' : '外部分享' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="分享给" min-width="160">
        <template #default="{ row }">{{ targetNames(row) }}</template>
      </el-table-column>
      <el-table-column label="权限" width="110">
        <template #default="{ row }"><el-tag size="small">{{ perm(row.permission) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="密码" width="90"><template #default="{ row }">{{ row.password ? '有' : '无' }}</template></el-table-column>
      <el-table-column prop="expiresAt" label="过期时间" width="180"><template #default="{ row }">{{ row.expiresAt || '永久' }}</template></el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="copy(row)">复制链接</el-button>
          <el-button link type="primary" size="small" @click="openAccess(row)">访问记录</el-button>
          <el-button link type="danger" size="small" @click="revoke(row)">撤销</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="还没有创建任何分享" /></template>
    </el-table>

    <div class="section-head">
      <h3 class="section-title">文件夹授权</h3>
      <span class="section-tip">授权给同事的文件夹，权限递归作用于该文件夹及全部下级（含后续新增）；撤销后对其不可见。</span>
    </div>
    <el-table :data="grants" v-loading="loading" border style="width:100%">
      <el-table-column prop="folderPath" label="文件夹" min-width="240" show-overflow-tooltip />
      <el-table-column prop="subjectName" label="授权对象" width="150" />
      <el-table-column label="来源" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <el-tag v-if="row.sourceRoleName" size="small" type="warning" effect="plain">角色 · {{ row.sourceRoleName }}</el-tag>
          <span v-else class="muted">直接指定</span>
        </template>
      </el-table-column>
      <el-table-column label="权限" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="p in row.perms" :key="p" size="small" type="success" effect="plain" class="perm-tag">{{ permOf(p) }}</el-tag>
          <span v-if="!row.perms || !row.perms.length" class="muted">不可见</span>
        </template>
      </el-table-column>
      <el-table-column label="继承上级" width="100">
        <template #default="{ row }">{{ row.inherit ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="gotoFolder(row)">打开文件夹</el-button>
          <el-button link type="danger" size="small" @click="revokeGrant(row)">撤销</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="还没有把文件夹授权给同事" /></template>
    </el-table>

    <el-dialog v-model="accessVisible" title="访问记录" width="700px">
      <el-table :data="accessList" size="small" border max-height="460">
        <el-table-column prop="accessedAt" label="时间" width="180" />
        <el-table-column prop="clientType" label="端类型" width="100" />
        <el-table-column prop="ip" label="IP" width="150" />
        <el-table-column prop="ua" label="UA" min-width="240" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { shareApi, grantApi } from '../api'

const router = useRouter()
const list = ref([]), grants = ref([]), users = ref([]), loading = ref(false)
async function load() {
  loading.value = true
  try {
    const [s, g, u] = await Promise.all([shareApi.list(), grantApi.mine(), shareApi.users()])
    list.value = s.data || []
    grants.value = g.data || []
    users.value = u.data || []
  } catch (e) {} finally { loading.value = false }
}
onMounted(load)
function perm(p) { return { view: '仅查看', download: '可下载', edit: '可编辑' }[p] || p }
function permOf(p) { return { view: '查看', edit: '编辑', download: '下载', upload: '上传', share_internal: '内部分享', share_external: '外部分享' }[p] || p }
function targetNames(row) {
  if (row.shareType !== 'internal') return '任何人（凭链接）'
  const ids = (row.targetUserIds || '').split(',').filter(Boolean).map(Number)
  return ids.map((id) => users.value.find((u) => u.id === id)?.name || ('用户 ' + id)).join('、') || '—'
}
function gotoFolder(row) { router.push({ path: '/files', query: { folder: row.folderId } }) }
async function revokeGrant(row) {
  try {
    await ElMessageBox.confirm(`撤销「${row.subjectName}」对「${row.folderPath}」的授权？撤销后该文件夹对其不可见。`, '提示', { type: 'warning' })
    await grantApi.revoke(row.id); ElMessage.success('已撤销'); load()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
function copy(row) { navigator.clipboard?.writeText(location.origin + (import.meta.env.BASE_URL.replace(/\/$/, '')) + '/s/' + row.token).then(() => ElMessage.success('已复制')) }
async function revoke(row) {
  try { await ElMessageBox.confirm('确定撤销该链接？', '提示', { type: 'warning' }); await shareApi.revoke(row.id); ElMessage.success('已撤销'); load() }
  catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
const accessVisible = ref(false), accessList = ref([])
async function openAccess(row) { try { accessList.value = (await shareApi.access(row.id)).data; accessVisible.value = true } catch (e) { ElMessage.error(e.message) } }
</script>

<style scoped>
.section-head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; margin: 26px 0 10px; }
.section-title { margin: 0; font-size: 15px; font-weight: 600; color: #33475b; }
.section-tip { font-size: 12px; color: var(--la-muted); }
.perm-tag { margin-right: 6px; }
.muted { color: var(--la-muted); font-size: 12px; }
</style>

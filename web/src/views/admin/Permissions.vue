<template>
  <div class="page-container">
    <div class="page-header"><h2 class="page-title">文件夹权限</h2></div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px">
      <template #title>文件夹权限<b>默认按角色配置</b>：角色上配好的目录权限，该角色下的用户自动继承（后续新增用户同样生效）。需要单独调整某个用户时，再对用户授权——可直接指定权限，也可“按角色授权”套用角色默认权限。</template>
      <div class="tip-line">规则挂在文件夹上，递归覆盖该文件夹及其<b>全部下级</b>（含后续新增）；下级只能在上级授权范围内收窄，不会放大。资源所有者与超级管理员始终拥有全部权限。</div>
    </el-alert>

    <el-alert v-if="isMobile" type="warning" :closable="false" show-icon title="细粒度授权属于复杂操作，建议在电脑端完成。" style="margin-bottom:16px" />

    <el-card header="新增 / 修改规则">
      <el-form label-position="top" :disabled="isMobile">
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="授权对象类型">
              <el-radio-group v-model="form.subjectType" @change="onSubjectTypeChange">
                <el-radio-button value="role">角色（默认）</el-radio-button>
                <el-radio-button value="user">具体用户</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item :label="form.subjectType === 'user' ? '选择用户' : '选择角色'">
              <el-select v-model="form.subjectId" filterable placeholder="请选择" style="width:100%" @change="loadRules">
                <el-option v-for="s in subjects" :key="s.id" :value="s.id" :label="s.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="文件夹属于">
              <el-select v-model="form.ownerId" filterable placeholder="选择所有者" style="width:100%" @change="loadFormTree">
                <el-option v-for="u in folderOwners" :key="u.id" :value="u.id" :label="u.label" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="选择已有文件夹">
              <el-tree-select v-model="form.folderId" :data="formTree" node-key="id" check-strictly
                :props="{ label: 'label', children: 'children' }" placeholder="请选择文件夹"
                style="width:100%" @change="onFolderChange" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item v-if="form.subjectType === 'user'" label="权限来源">
          <div class="source-picker">
            <el-radio-group v-model="form.mode" @change="onModeChange">
              <el-radio-button value="direct">直接指定</el-radio-button>
              <el-radio-button value="role">按角色授权</el-radio-button>
            </el-radio-group>
            <el-select v-if="form.mode === 'role'" v-model="form.sourceRoleId" filterable placeholder="选择角色模板"
              style="width:220px" @change="loadRoleDefaults">
              <el-option v-for="r in normalRoles" :key="r.id" :value="r.id" :label="r.name" />
            </el-select>
            <span v-if="form.mode === 'role' && roleInfo" class="switch-tip">
              角色默认权限：{{ roleInfo.perms?.length ? roleInfo.perms.map(labelOf).join(' / ') : '该目录未配置' }}
              · 该角色 {{ roleInfo.userCount }} 人
            </span>
          </div>
          <div class="perm-hint">{{ form.mode === 'role'
            ? '实际权限 = 角色在该文件夹的默认权限 ∩ 下方勾选的上限；角色默认配置调整后该用户自动跟随，且不会超过上限。'
            : '直接指定：权限固定为下方勾选值，不随角色变化。' }}</div>
        </el-form-item>

        <el-form-item :label="form.mode === 'role' ? '权限上限（角色默认权限的基础上可收窄）' : '权限（作用范围：所选文件夹及全部下级）'">
          <div class="perm-picker">
            <el-checkbox-group v-model="form.perms">
              <el-checkbox v-for="p in permDefs" :key="p.value" :value="p.value">{{ p.label }}</el-checkbox>
            </el-checkbox-group>
            <el-button-group class="preset">
              <el-button size="small" @click="preset('readonly')">只读（查看+下载）</el-button>
              <el-button size="small" @click="preset('upload')">可上传</el-button>
              <el-button size="small" @click="preset('all')">全部</el-button>
            </el-button-group>
          </div>
          <div class="perm-hint">{{ permHint }}</div>
        </el-form-item>

        <el-form-item label="继承上级权限">
          <el-switch v-model="form.inherit" />
          <span class="switch-tip">开启（默认）时，本级权限受上级规则约束；关闭后该文件夹成为新的权限边界，不再向上继承。</span>
        </el-form-item>

        <el-button type="primary" :disabled="!form.subjectId || !form.folderId" @click="save">保存规则</el-button>
        <span v-if="form.folderPath" class="path-tip">目标：{{ form.folderPath }}</span>
      </el-form>
    </el-card>

    <el-card :header="ruleHeader" style="margin-top:20px">
      <el-table :data="rules" size="small" border>
        <el-table-column prop="folderPath" label="文件夹路径" min-width="240" show-overflow-tooltip />
        <el-table-column prop="ownerName" label="所有者" width="110" />
        <el-table-column label="来源" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.sourceRoleName" size="small" type="warning" effect="plain">角色 · {{ row.sourceRoleName }}</el-tag>
            <span v-else class="muted">{{ row.subjectType === 'role' ? '角色默认权限' : '直接指定' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="权限" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="p in row.perms" :key="p" size="small" type="success" effect="plain" class="perm-tag">{{ labelOf(p) }}</el-tag>
            <span v-if="!row.perms || !row.perms.length" class="muted">无（等同禁止访问）</span>
          </template>
        </el-table-column>
        <el-table-column label="继承上级" width="90">
          <template #default="{ row }">{{ row.inherit ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="isMobile" @click="edit(row)">编辑</el-button>
            <el-button link type="danger" size="small" :disabled="isMobile" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="尚未配置规则：普通用户只能看到自己的文件" /></template>
      </el-table>
      <div class="perm-hint" style="margin-top:10px">给<b>角色</b>配置的规则即该角色的默认文件夹权限，角色下所有用户（含后续新增）自动继承；给<b>用户</b>配置的规则优先级更高，会覆盖其在同级目录的角色默认权限。</div>
    </el-card>

    <el-card header="权限查询：指定用户在某文件夹上的有效权限" style="margin-top:20px">
      <el-form :inline="true">
        <el-form-item label="用户">
          <el-select v-model="query.userId" filterable style="width:160px">
            <el-option v-for="u in folderOwners" :key="u.id" :value="u.id" :label="u.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="文件夹属于">
          <el-select v-model="query.ownerId" filterable style="width:150px" @change="loadQueryTree">
            <el-option v-for="u in folderOwners" :key="u.id" :value="u.id" :label="u.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="文件夹">
          <el-tree-select v-model="query.folderId" :data="queryTree" node-key="id" check-strictly
            :props="{ label: 'label', children: 'children' }" placeholder="请选择" style="width:220px" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="queryEffective">查询</el-button></el-form-item>
      </el-form>
      <div v-if="queried" class="tag-list">
        <template v-if="effective.length">
          <el-tag v-for="p in effective" :key="p" type="success" effect="plain">{{ labelOf(p) }}</el-tag>
        </template>
        <span v-else class="muted">该用户在此文件夹无任何权限（不可见）</span>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'

const isMobile = ref(window.innerWidth < 768)
const permDefs = [
  { value: 'view', label: '文件查看' },
  { value: 'edit', label: '文件编辑' },
  { value: 'download', label: '文件下载' },
  { value: 'upload', label: '文件上传' },
  { value: 'share_internal', label: '内部分享' },
  { value: 'share_external', label: '外部分享' },
]
const PERM_HINTS = {
  view: '可在列表中看到并在线预览（图片、音视频、PDF、Office、文本等）',
  edit: '可在线编辑文本、重命名、删除到回收站、回滚历史版本',
  download: '可下载原文件、批量打包下载',
  upload: '可在该文件夹内上传文件、新建子文件夹、粘贴复制/剪切的内容',
  share_internal: '可把文件/文件夹分享给指定的内部用户（需登录且在名单内）',
  share_external: '可创建外部分享链接（可设密码、有效期、下载次数）',
}
function labelOf(v) { return permDefs.find((p) => p.value === v)?.label || v }

const users = ref([]), roles = ref([])
const folderOwners = computed(() => users.value.map((u) => ({
  id: u.id, label: (u.nickname && u.nickname !== u.username ? u.nickname + '（' + u.username + '）' : u.username) + (u.role_id === 1 ? ' · 管理员' : ''),
})))
const normalUsers = computed(() => users.value.filter((u) => u.role_id !== 1))
const normalRoles = computed(() => roles.value.filter((r) => r.id !== 1))
const subjects = computed(() => form.subjectType === 'user'
  ? normalUsers.value.map((u) => ({ id: u.id, name: u.username }))
  : roles.value.map((r) => ({ id: r.id, name: r.name })))

const form = reactive({
  subjectType: 'role', subjectId: null,
  ownerId: null, folderId: null, folderPath: '',
  perms: ['view', 'download'], inherit: true,
  mode: 'direct', sourceRoleId: null,
})
const roleInfo = ref(null)
const formTree = ref([]), queryTree = ref([])
const rules = ref([])
const query = reactive({ userId: null, ownerId: null, folderId: null })
const effective = ref([]), queried = ref(false)

const ruleHeader = computed(() => {
  if (!form.subjectId) return '已配置规则'
  const name = subjects.value.find((s) => s.id === form.subjectId)?.name || ''
  return `已配置规则 · ${name}`
})
const permHint = computed(() => form.mode === 'role'
  ? '实际生效：' + (effectiveByRole.value.length ? effectiveByRole.value.map(labelOf).join(' / ') : '无（角色在此目录没有默认权限）')
    + '；勾选仅表示上限，角色默认配置调整后该用户自动跟随。'
  : (form.perms.length ? form.perms.map((p) => PERM_HINTS[p]).join('；') : '未勾选任何权限 = 该文件夹对此对象不可见'))
const effectiveByRole = computed(() => (roleInfo.value?.perms || []).filter((p) => form.perms.includes(p)))

onMounted(async () => {
  await loadBase()
})

async function loadBase() {
  try {
    users.value = (await adminApi.users()).data
    roles.value = (await adminApi.roles()).data
    const first = users.value[0]
    if (first) { form.ownerId = first.id; query.ownerId = first.id }
    await Promise.all([loadFormTree(), loadQueryTree()])
  } catch (e) { ElMessage.error(e.message) }
}

async function loadTree(ownerId) {
  if (!ownerId) return []
  const data = (await adminApi.folders(ownerId)).data || []
  // 根节点只作展示，不允许单独授权（权限作用在具体文件夹上）
  const strip = (nodes) => (nodes || []).map((n) => ({ ...n, children: strip(n.children) }))
  const root = data[0]
  return root ? strip(root.children) : []
}
async function loadFormTree() { try { formTree.value = await loadTree(form.ownerId); form.folderId = null; form.folderPath = '' } catch (e) {} }
async function loadQueryTree() { try { queryTree.value = await loadTree(query.ownerId); query.folderId = null } catch (e) {} }

function findNode(nodes, id) {
  for (const n of nodes || []) {
    if (n.id === id) return n
    const hit = findNode(n.children, id)
    if (hit) return hit
  }
  return null
}
function onFolderChange(id) {
  form.folderPath = findNode(formTree.value, id)?.path || ''
  if (form.mode === 'role') loadRoleDefaults()
}

// 角色默认权限：用于“按角色授权”时的回填与提示
async function loadRoleDefaults() {
  roleInfo.value = null
  if (form.subjectType !== 'user' || form.mode !== 'role' || !form.sourceRoleId) return
  try {
    roleInfo.value = (await adminApi.roleEffective(form.sourceRoleId, form.folderId || 0)).data || null
    // 勾选的是上限：默认放开到四权限，实际权限由角色默认值决定
    if (roleInfo.value) form.perms = permDefs.map((p) => p.value)
  } catch (e) { roleInfo.value = null }
}

function onModeChange() {
  if (form.mode === 'direct') {
    form.sourceRoleId = null
    roleInfo.value = null
    return
  }
  if (!form.sourceRoleId) {
    const u = users.value.find((x) => x.id === form.subjectId)
    form.sourceRoleId = u && u.role_id !== 1 ? u.role_id : (normalRoles.value[0]?.id || null)
  }
  loadRoleDefaults()
}

function preset(kind) {
  if (kind === 'readonly') form.perms = ['view', 'download']
  else if (kind === 'upload') form.perms = ['view', 'download', 'upload']
  else form.perms = ['view', 'edit', 'download', 'upload', 'share_internal', 'share_external']
}

function onSubjectTypeChange() {
  form.subjectId = null
  form.mode = 'direct'
  form.sourceRoleId = null
  roleInfo.value = null
  rules.value = []
}

async function loadRules() {
  if (!form.subjectId) { rules.value = []; return }
  try {
    const data = (await adminApi.permissions(form.subjectType, form.subjectId)).data || []
    rules.value = data.map((r) => ({ ...r, perms: r.perms || String(r.permBits || '').split(',').filter(Boolean) }))
  } catch (e) { rules.value = [] }
}

async function save() {
  try {
    const byRole = form.subjectType === 'user' && form.mode === 'role'
    if (byRole && !form.sourceRoleId) { ElMessage.warning('请选择角色模板'); return }
    const r = await adminApi.savePermission({
      subjectType: form.subjectType, subjectId: form.subjectId,
      folderId: form.folderId, perms: form.perms, inherit: form.inherit ? 1 : 0,
      sourceRoleId: byRole ? form.sourceRoleId : null,
    })
    ElMessage.success(r.msg || '规则已保存')
    loadRules()
  } catch (e) { ElMessage.error(e.message) }
}

async function edit(row) {
  form.subjectType = row.subjectType
  form.subjectId = row.subjectId
  form.ownerId = row.ownerId
  form.perms = [...(row.perms || [])]
  form.inherit = !!row.inherit
  form.mode = row.sourceRoleId ? 'role' : 'direct'
  form.sourceRoleId = row.sourceRoleId || null
  await loadFormTree()
  form.folderId = row.folderId
  form.folderPath = row.folderPath || ''
  roleInfo.value = null
  if (form.mode === 'role') loadRoleDefaults()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除「${row.folderPath}」上的规则？删除后该用户将不再拥有此文件夹的任何权限。`, '提示', { type: 'warning' })
    await adminApi.deletePermission(row.id)
    ElMessage.success('规则已删除')
    loadRules()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

async function queryEffective() {
  if (!query.userId || !query.folderId) { ElMessage.warning('请选择用户与文件夹'); return }
  try {
    effective.value = (await adminApi.effectivePermissions(query.userId, query.folderId)).data || []
    queried.value = true
  } catch (e) { ElMessage.error(e.message) }
}
</script>

<style scoped>
.perm-picker { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; width: 100%; }
.source-picker { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; width: 100%; }
.preset { margin-left: auto; }
.perm-hint { color: var(--la-muted); font-size: 12px; margin-top: 6px; line-height: 1.7; width: 100%; }
.switch-tip { color: var(--la-muted); font-size: 12px; margin-left: 10px; }
.path-tip { color: var(--la-muted); font-size: 12px; margin-left: 12px; }
.tip-line { font-size: 12px; margin-top: 6px; opacity: .85; }
.tag-list { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.perm-tag { margin-right: 6px; }
.muted { color: var(--la-muted); font-size: 13px; }
</style>

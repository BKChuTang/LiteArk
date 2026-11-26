<template>
  <div class="page-container">
    <div class="page-header"><h2 class="page-title">角色管理</h2><el-button type="primary" :icon="Plus" @click="openAdd">新增角色</el-button></div>
    <el-alert type="info" :closable="false" title="文件夹权限默认关联角色：在角色的「修改权限」里配置一次，该角色下所有用户（含后续新增）自动继承；个别用户需要额外权限或收窄时，再到「文件夹权限」页对用户单独授权。" style="margin-bottom:16px" />
    <el-table :data="list" v-loading="loading" border style="width:100%">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="角色名称" min-width="150" />
      <el-table-column prop="description" label="描述" min-width="200" />
      <el-table-column label="类型" width="110"><template #default="{ row }"><el-tag size="small" :type="row.is_system ? 'warning' : 'success'">{{ row.is_system ? '内置' : '自定义' }}</el-tag></template></el-table-column>
      <el-table-column prop="user_count" label="用户数" width="100" />
      <el-table-column label="操作" width="220"><template #default="{ row }">
        <el-button link type="primary" size="small" @click="openPerms(row)">修改权限</el-button>
        <el-button link type="primary" size="small" :disabled="row.is_system === 1" @click="openEdit(row)">编辑</el-button>
        <el-button link type="danger" size="small" :disabled="row.is_system === 1" @click="remove(row)">删除</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="editing ? '编辑角色' : '新增角色'" width="440px">
      <el-form label-position="top">
        <el-form-item label="角色名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <!-- 角色权限：新增/修改规则 -->
    <el-drawer v-model="permVisible" :title="'修改权限 · ' + (permRole?.name || '')" size="640px">
      <el-alert v-if="permRole?.id === 1" type="warning" :closable="false" show-icon
        title="超级管理员始终拥有全部权限，无需配置文件夹权限。下方仅展示已有规则。" style="margin-bottom:16px" />
      <el-alert v-else type="info" :closable="false" show-icon style="margin-bottom:16px">
        <template #title>此处配置的权限即该角色的<b>默认文件夹权限</b>，角色下 {{ permRole?.user_count || 0 }} 个用户自动继承，后续新增用户同样生效。规则递归作用于所选文件夹及其全部下级。</template>
      </el-alert>

      <div v-if="permRole?.id !== 1" class="perm-toolbar">
        <span class="perm-list-title">文件夹权限列表</span>
        <el-button size="small" :icon="Plus" @click="addPerm">新增规则</el-button>
      </div>

      <el-table :data="permRules" size="small" border max-height="420">
        <el-table-column prop="folderPath" label="文件夹路径" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ownerName" label="所有者" width="90" />
        <el-table-column label="权限" min-width="150">
          <template #default="{ row }">
            <el-tag v-for="p in row.perms" :key="p" size="small" type="success" effect="plain" class="perm-tag">{{ labelOf(p) }}</el-tag>
            <span v-if="!row.perms?.length" class="muted">不可见</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="permRole?.id === 1" @click="editPerm(row)">编辑</el-button>
            <el-button link type="danger" size="small" :disabled="permRole?.id === 1" @click="delPerm(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="该角色还没有文件夹权限规则" :image-size="60" /></template>
      </el-table>

      <!-- 只有点「新增规则」或列表「编辑」后才展开权限操作区，默认只展示规则列表 -->
      <el-card v-if="permEditing" ref="permEditorRef" class="perm-editor" shadow="never">
        <template #header>
          <div class="perm-editor-head">
            <b>{{ editingRule ? '编辑权限 · ' + editingRule.folderPath : '新增权限规则' }}</b>
            <el-button link size="small" @click="resetPermForm">收起</el-button>
          </div>
        </template>
        <el-form label-position="top">
          <el-row :gutter="12">
            <el-col :span="11">
              <el-form-item label="文件夹属于">
                <el-select v-model="permOwnerId" filterable placeholder="选择所有者" style="width:100%" @change="loadPermTree">
                  <el-option v-for="u in owners" :key="u.id" :value="u.id" :label="u.label" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="13">
              <el-form-item label="选择已有文件夹">
                <el-tree-select v-model="permForm.folderId" :data="permTree" node-key="id" check-strictly
                  :props="{ label: 'label', children: 'children' }" placeholder="请选择文件夹" style="width:100%" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="权限">
            <el-checkbox-group v-model="permForm.perms">
              <el-checkbox v-for="p in PERM_DEFS" :key="p.value" :value="p.value">{{ p.label }}</el-checkbox>
            </el-checkbox-group>
            <div class="perm-hint">{{ permForm.perms.length
              ? permForm.perms.map(labelOf).join(' / ') + '（未勾选 = 该目录对角色不可见）'
              : '未勾选任何权限 = 该目录对此角色的用户不可见' }}</div>
          </el-form-item>
          <el-form-item label="继承上级权限">
            <el-switch v-model="permForm.inherit" />
            <span class="perm-hint" style="margin-left:10px">开启时受上级规则约束；关闭后该文件夹成为新的权限边界</span>
          </el-form-item>
        </el-form>
      </el-card>

      <template #footer>
        <el-button @click="permVisible = false">关闭</el-button>
        <template v-if="permEditing && permRole?.id !== 1">
          <el-button @click="resetPermForm">取消</el-button>
          <el-button type="primary" :disabled="!permForm.folderId" :loading="permSaving" @click="savePerm">
            {{ editingRule ? '保存修改' : '保存权限' }}
          </el-button>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { adminApi } from '../../api'

const PERM_DEFS = [
  { value: 'view', label: '文件查看' },
  { value: 'edit', label: '文件编辑' },
  { value: 'download', label: '文件下载' },
  { value: 'upload', label: '文件上传' },
  { value: 'share_internal', label: '内部分享' },
  { value: 'share_external', label: '外部分享' },
]
function labelOf(v) { return PERM_DEFS.find((p) => p.value === v)?.label || v }

const list = ref([]), loading = ref(false)
async function load() { loading.value = true; try { list.value = (await adminApi.roles()).data } catch (e) {} finally { loading.value = false } }
onMounted(load)
const visible = ref(false), editing = ref(null), form = ref({ name: '', description: '' })
function openAdd() { editing.value = null; form.value = { name:'', description:'' }; visible.value = true }
function openEdit(row) { editing.value = row; form.value = { name:row.name, description:row.description }; visible.value = true }
async function save() {
  if (!form.value.name.trim()) return ElMessage.warning('请输入角色名称')
  try { editing.value ? await adminApi.updateRole(editing.value.id, form.value) : await adminApi.addRole(form.value); ElMessage.success('保存成功'); visible.value = false; load() }
  catch (e) { ElMessage.error(e.message) }
}
async function remove(row) { try { await ElMessageBox.confirm(`删除角色「${row.name}」？`, '提示', { type: 'warning' }); await adminApi.deleteRole(row.id); ElMessage.success('已删除'); load() } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) } }

// ===== 角色默认文件夹权限 =====
const permVisible = ref(false), permRole = ref(null)
const users = ref([]), permTree = ref([]), permRules = ref([])
const permOwnerId = ref(null), editingRule = ref(null), permAdding = ref(false), permSaving = ref(false)
const permEditorRef = ref(null)
const permEditing = computed(() => permAdding.value || !!editingRule.value)
const permForm = reactive({ folderId: null, perms: ['view', 'download'], inherit: true })
const owners = computed(() => users.value.map((u) => ({
  id: u.id,
  label: (u.nickname && u.nickname !== u.username ? u.nickname + '（' + u.username + '）' : u.username)
    + (u.role_id === 1 ? ' · 管理员' : ''),
})))

function resetPermForm() {
  editingRule.value = null
  permAdding.value = false
  permForm.folderId = null
  permForm.perms = ['view', 'download']
  permForm.inherit = true
}
// 展开下方权限操作区后滚到可见位置
async function revealEditor() {
  await nextTick()
  permEditorRef.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

async function openPerms(row) {
  permRole.value = row
  permVisible.value = true
  resetPermForm()
  permRules.value = []
  if (!users.value.length) { try { users.value = (await adminApi.users()).data } catch (e) {} }
  if (!permOwnerId.value && users.value.length) permOwnerId.value = users.value[0].id
  await Promise.all([loadPermTree(), loadPermRules()])
}

function addPerm() {
  resetPermForm()
  permAdding.value = true
  if (!permTree.value.length) loadPermTree()
  revealEditor()
}

// 点规则列表的「编辑」：把已有规则回填到下方权限操作区，保存时走同一个 upsert 接口
async function editPerm(row) {
  permAdding.value = false
  editingRule.value = row
  permForm.perms = [...(row.perms || [])]
  permForm.inherit = !!row.inherit
  if (row.ownerId && row.ownerId !== permOwnerId.value) permOwnerId.value = row.ownerId
  await loadPermTree()
  permForm.folderId = row.folderId
  revealEditor()
}
async function loadPermTree() {
  permForm.folderId = null
  if (!permOwnerId.value) { permTree.value = []; return }
  try {
    const data = (await adminApi.folders(permOwnerId.value)).data || []
    const root = data[0]
    permTree.value = root ? root.children : []
  } catch (e) { permTree.value = [] }
}
async function loadPermRules() {
  if (!permRole.value) { permRules.value = []; return }
  try {
    permRules.value = (await adminApi.permissions('role', permRole.value.id)).data || []
  } catch (e) { permRules.value = [] }
}
async function savePerm() {
  if (!permForm.folderId) return ElMessage.warning('请选择文件夹')
  permSaving.value = true
  try {
    const r = await adminApi.savePermission({
      subjectType: 'role', subjectId: permRole.value.id,
      folderId: permForm.folderId, perms: permForm.perms, inherit: permForm.inherit ? 1 : 0,
    })
    ElMessage.success(r.msg || '已保存')
    resetPermForm()
    await loadPermRules()
  } catch (e) { ElMessage.error(e.message) }
  finally { permSaving.value = false }
}
async function delPerm(row) {
  try {
    await ElMessageBox.confirm(`删除「${row.folderPath}」上的角色权限规则？该角色下的用户将不再因此获得该目录权限。`, '提示', { type: 'warning' })
    await adminApi.deletePermission(row.id)
    ElMessage.success('已删除')
    if (editingRule.value?.id === row.id) resetPermForm()
    loadPermRules()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
</script>

<style scoped>
.perm-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.perm-list-title { font-size: 14px; font-weight: 600; }
.perm-editor { margin-top: 18px; }
.perm-editor-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.perm-hint { color: var(--la-muted); font-size: 12px; margin-top: 6px; line-height: 1.7; width: 100%; }
.perm-tag { margin-right: 6px; }
.muted { color: var(--la-muted); font-size: 13px; }
</style>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">用户管理</h2>
      <span>
        <el-button :disabled="!selected.length" @click="batchAllowed(true)">批量允许二次验证</el-button>
        <el-button :disabled="!selected.length" @click="batchAllowed(false)">批量禁止二次验证</el-button>
        <el-button type="primary" :icon="Plus" @click="openAdd">新增用户</el-button>
      </span>
    </div>
    <el-table :data="list" v-loading="loading" border style="width:100%" @selection-change="(v) => selected = v">
      <el-table-column type="selection" width="46" />
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="账号" width="130" />
      <el-table-column prop="nickname" label="昵称" width="130" />
      <el-table-column label="角色" width="130"><template #default="{ row }"><el-tag size="small" :type="row.role_id === 1 ? 'danger' : 'info'">{{ row.role_name }}</el-tag></template></el-table-column>
      <el-table-column label="配额" width="120"><template #default="{ row }">{{ size(row.quota) }}</template></el-table-column>
      <el-table-column label="已用" width="120"><template #default="{ row }">{{ size(row.used) }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag></template></el-table-column>
      <el-table-column label="二次验证" width="170" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.totp_allowed === 1 ? 'success' : 'info'">{{ row.totp_allowed === 1 ? '允许开通' : '禁止开通' }}</el-tag>
          <el-tag v-if="row.totp_enabled === 1" size="small" type="warning" effect="plain" style="margin-left:6px">已绑定</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="380" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openPwd(row)">重置密码</el-button>
          <el-button link :type="row.totp_allowed === 1 ? 'warning' : 'success'" size="small" @click="toggleTotp(row)">
            {{ row.totp_allowed === 1 ? '禁止二次验证' : '允许二次验证' }}
          </el-button>
          <el-button link type="warning" size="small" @click="resetTotp(row)">重置二次验证</el-button>
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" size="small" @click="toggle(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="editing ? '编辑用户' : '新增用户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="账号" prop="username"><el-input v-model="form.username" :disabled="!!editing" /></el-form-item>
        <el-form-item v-if="!editing" label="密码" prop="password"><el-input v-model="form.password" type="password" show-password /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="form.roleId" style="width:100%"><el-option v-for="r in roles" :key="r.id" :value="r.id" :label="r.name" /></el-select></el-form-item>
        <el-form-item label="配额（GB）"><el-input-number v-model="quotaGB" :min="0.1" :max="10240" style="width:100%" /></el-form-item>
        <el-form-item v-if="editing" label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="pwdVisible" title="重置密码" width="400px">
      <p class="muted">为用户 {{ pwdTarget?.username }} 设置新密码</p>
      <el-input v-model="newPwd" type="password" show-password placeholder="至少 6 位" />
      <template #footer><el-button @click="pwdVisible = false">取消</el-button><el-button type="primary" @click="savePwd">确定</el-button></template>
    </el-dialog>

    <el-dialog v-model="verifyVisible" title="管理员身份核验" width="420px">
      <p class="muted">{{ verifyText }}</p>
      <el-radio-group v-model="verifyType" style="margin-bottom:14px">
        <el-radio-button value="totp">动态验证码</el-radio-button>
        <el-radio-button v-if="verifyAllowRecovery" value="recovery">备用恢复码</el-radio-button>
      </el-radio-group>
      <el-input v-model="verifyCode" :maxlength="verifyType === 'totp' ? 6 : 40" autocomplete="off"
        :placeholder="verifyType === 'totp' ? '6 位动态验证码' : 'XXXX-XXXX-XXXX-XXXX'" />
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="verifyLoading" @click="confirmVerify">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { adminApi, totpApi } from '../../api'

const list = ref([]), roles = ref([]), loading = ref(false), selected = ref([]), adminTotpEnabled = ref(false)
async function load() {
  loading.value = true
  try {
    const [u, r, s] = await Promise.all([adminApi.users(), adminApi.roles(), totpApi.status()])
    list.value = u.data; roles.value = r.data; adminTotpEnabled.value = !!s.data.enabled
  } catch (e) {} finally { loading.value = false }
}
onMounted(load)
function size(n) { if (!n) return '0 B'; const u=['B','KB','MB','GB','TB'], i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),4); return (n/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i] }

const formRef = ref(), visible = ref(false), editing = ref(null)
const form = ref({ username: '', password: '', nickname: '', roleId: 2, status: 1 }), quotaGB = ref(20)
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, min: 6, message: '至少 6 位', trigger: 'blur' }],
}
function openAdd() { editing.value = null; form.value = { username:'', password:'', nickname:'', roleId:2, status:1 }; quotaGB.value = 20; visible.value = true }
function openEdit(row) { editing.value = row; form.value = { username:row.username, nickname:row.nickname, roleId:row.role_id, status:row.status }; quotaGB.value = Number((row.quota/1073741824).toFixed(2)) || 20; visible.value = true }
async function save() {
  await formRef.value.validate()
  const quota = Math.round(quotaGB.value * 1073741824)
  try { editing.value ? await adminApi.updateUser(editing.value.id, { ...form.value, quota }) : await adminApi.addUser({ ...form.value, quota }); ElMessage.success('保存成功'); visible.value = false; load() }
  catch (e) { ElMessage.error(e.message) }
}
async function toggle(row) { try { await adminApi.updateUser(row.id, { status: row.status === 1 ? 0 : 1 }); ElMessage.success('已更新'); load() } catch (e) { ElMessage.error(e.message) } }

const pwdVisible = ref(false), pwdTarget = ref(null), newPwd = ref('')
function openPwd(row) { pwdTarget.value = row; newPwd.value = ''; pwdVisible.value = true }
async function savePwd() { if (newPwd.value.length < 6) return ElMessage.warning('至少 6 位'); try { await adminApi.resetPassword(pwdTarget.value.id, newPwd.value); ElMessage.success('已重置'); pwdVisible.value = false } catch (e) { ElMessage.error(e.message) } }
async function remove(row) {
  try { await ElMessageBox.confirm(`删除用户「${row.username}」及其全部文件？`, '警告', { type: 'error' }); await adminApi.deleteUser(row.id); ElMessage.success('已删除'); load() }
  catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

// ===== 二次验证管理 =====
const verifyVisible = ref(false), verifyText = ref(''), verifyType = ref('totp')
const verifyAllowRecovery = ref(false), verifyCode = ref(''), verifyLoading = ref(false)
let pendingAction = null

async function toggleTotp(row) {
  const allowed = row.totp_allowed !== 1
  try {
    await ElMessageBox.confirm(
      `${allowed ? '允许' : '禁止'}用户「${row.username}」开启二次验证？禁止后按当前策略处理已有绑定。`,
      '二次确认', { type: 'warning' })
  } catch (e) { return }
  const run = async (verification) => {
    await adminApi.setTotpAllowed(row.id, { allowed, verification })
    ElMessage.success('已保存'); load()
  }
  if (adminTotpEnabled.value) askVerify('请输入管理员本人的动态验证码，核验后修改该用户的二次验证权限。', false, run)
  else safeRun(run)
}

async function batchAllowed(allowed) {
  const names = selected.value.map((u) => u.username).join('、')
  try {
    await ElMessageBox.confirm(
      `确认批量${allowed ? '允许' : '禁止'}以下 ${selected.value.length} 名用户开启二次验证？\n${names}`,
      '二次确认', { type: 'warning' })
  } catch (e) { return }
  const ids = selected.value.map((u) => u.id)
  const run = async (verification) => {
    const r = await adminApi.batchTotpAllowed({ ids, allowed, verification })
    ElMessage.success(`已处理 ${r.data.count} 名用户${r.data.unbound ? `，解除 ${r.data.unbound} 个绑定` : ''}`)
    selected.value = []; load()
  }
  if (adminTotpEnabled.value) askVerify('请输入管理员本人的动态验证码，核验后批量修改二次验证权限。', false, run)
  else safeRun(run)
}

async function resetTotp(row) {
  try { await ElMessageBox.confirm(`重置用户「${row.username}」的二次验证？密钥、恢复码与信任设备将全部清除。`, '警告', { type: 'error' }) }
  catch (e) { return }
  const run = async (verification) => {
    await adminApi.resetTotp(row.id, { verification })
    ElMessage.success('已重置二次验证'); load()
  }
  if (adminTotpEnabled.value) askVerify('请输入管理员本人的动态验证码或一条备用恢复码。', true, run)
  else safeRun(run)
}

async function safeRun(run) {
  try { await run({}) } catch (e) { ElMessage.error(e.message) }
}
function askVerify(text, allowRecovery, run) {
  verifyText.value = text
  verifyAllowRecovery.value = allowRecovery
  verifyType.value = 'totp'
  verifyCode.value = ''
  pendingAction = run
  verifyVisible.value = true
}
async function confirmVerify() {
  if (!verifyCode.value.trim()) return ElMessage.warning('请输入验证码')
  verifyLoading.value = true
  try {
    await pendingAction({ type: verifyType.value, code: verifyCode.value.trim() })
    verifyVisible.value = false
    verifyCode.value = ''
  } catch (e) { ElMessage.error(e.message) }
  finally { verifyLoading.value = false }
}
</script>

<style scoped>
.muted { color: var(--la-muted); }
</style>

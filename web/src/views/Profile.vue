<template>
  <div class="page-container">
    <h2 class="page-title" style="margin-bottom:20px">我的</h2>
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card header="基本信息">
          <el-form label-position="top">
            <el-form-item label="账号"><el-input :model-value="store.user?.username" disabled /></el-form-item>
            <el-form-item label="昵称"><el-input v-model="nickname" /></el-form-item>
            <el-form-item label="角色"><el-input :model-value="store.user?.role_name" disabled /></el-form-item>
            <el-button type="primary" @click="saveNick">保存昵称</el-button>
          </el-form>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card header="使用空间">
          <div class="quota-wrap">
            <svg viewBox="0 0 120 120" class="ring">
              <circle cx="60" cy="60" r="50" fill="none" stroke="#e8efed" stroke-width="10" />
              <circle cx="60" cy="60" r="50" fill="none" stroke="#1f6b60" stroke-width="10"
                :stroke-dasharray="circum" :stroke-dashoffset="dashOffset" stroke-linecap="round"
                transform="rotate(-90 60 60)" />
              <text x="60" y="58" text-anchor="middle" class="ring-num">{{ percent }}%</text>
              <text x="60" y="76" text-anchor="middle" class="ring-label">已使用</text>
            </svg>
            <div class="quota-text">
              <div>{{ usedText }} <span>/ {{ quotaText }}</span></div>
              <p>个人存储空间</p>
            </div>
          </div>
        </el-card>
        <el-card header="修改密码" style="margin-top:20px">
          <el-form label-position="top">
            <el-form-item label="原密码"><el-input v-model="pwd.oldPassword" type="password" show-password /></el-form-item>
            <el-form-item label="新密码"><el-input v-model="pwd.newPassword" type="password" show-password /></el-form-item>
            <el-button type="primary" @click="changePwd">修改密码</el-button>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="status.visible" header="账号安全 · 二次验证" style="margin-top:20px">
      <div class="security-head">
        <div>
          <div class="security-title">
            动态验证码二次验证
            <el-tag size="small" :type="status.enabled ? 'success' : 'info'" effect="light">
              {{ status.enabled ? '已开启' : '未开启' }}
            </el-tag>
          </div>
          <p class="muted">
            支持谷歌验证器、微软验证器等 TOTP 应用。开启后，新设备登录需输入 6 位动态验证码。
          </p>
        </div>
        <div class="security-actions">
          <el-button v-if="status.canBind" type="primary" @click="openBind">开启二次验证</el-button>
          <template v-if="status.enabled">
            <el-button @click="openVerify('regenerate')">重新生成恢复码</el-button>
            <el-button @click="loadDevices">查看信任设备</el-button>
            <el-button type="danger" @click="openVerify('unbind')">关闭二次验证</el-button>
          </template>
        </div>
      </div>

      <el-alert v-if="status.enabled" type="info" :closable="false" show-icon class="security-alert"
        :title="`当前有 ${status.deviceCount || 0} 台信任设备，${status.recoveryCount || 0} 个未使用的备用恢复码`" />

      <div v-if="devices.length" class="device-wrap">
        <h4>已信任设备</h4>
        <el-table :data="devices" border size="small">
          <el-table-column prop="deviceName" label="设备" min-width="140" />
          <el-table-column prop="clientType" label="登录端" width="90" />
          <el-table-column prop="ip" label="IP" width="140" />
          <el-table-column prop="expireAt" label="有效期至" width="170" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }"><el-button link type="danger" size="small" @click="removeDevice(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 绑定：二维码本地生成，明文密钥仅内存暂存 -->
    <el-dialog v-model="bindVisible" title="开启二次验证" width="460px" @closed="clearBind">
      <div class="bind-step">1. 用验证器 App 扫描下方二维码，或手动录入密钥</div>
      <div class="qr-wrap"><img v-if="qr" :src="qr" alt="二次验证二维码" /></div>
      <div class="secret-box">
        <span class="secret-label">手动录入密钥</span>
        <code>{{ bindSecret }}</code>
        <el-button link type="primary" @click="copySecret">复制</el-button>
      </div>
      <div class="bind-step">2. 输入验证器生成的 6 位验证码完成绑定</div>
      <el-input v-model="bindCode" maxlength="6" inputmode="numeric" autocomplete="off"
        placeholder="6 位动态验证码" />
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="confirmBind">确认绑定</el-button>
      </template>
    </el-dialog>

    <!-- 关闭 / 重生成恢复码：二选一核验 -->
    <el-dialog v-model="verifyVisible" :title="verifyAction === 'unbind' ? '关闭二次验证' : '重新生成恢复码'" width="420px">
      <p class="muted">请先完成身份核验（动态验证码或一条未使用的备用恢复码）。</p>
      <el-radio-group v-model="verifyType" style="margin-bottom:14px">
        <el-radio-button value="totp">动态验证码</el-radio-button>
        <el-radio-button value="recovery">备用恢复码</el-radio-button>
      </el-radio-group>
      <el-input v-model="verifyCode" :maxlength="verifyType === 'totp' ? 6 : 40" autocomplete="off"
        :placeholder="verifyType === 'totp' ? '6 位动态验证码' : 'XXXX-XXXX-XXXX-XXXX'" />
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submitVerify">确定</el-button>
      </template>
    </el-dialog>

    <!-- 恢复码仅展示一次，带水印防截屏泄露 -->
    <el-dialog v-model="recoveryVisible" title="请保存备用恢复码" width="520px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon
        title="仅展示这一次，关闭后无法找回。请离线保存，每条仅能使用一次。" />
      <div class="code-watermark" :data-wm="wmText">
        <div class="recovery-grid">
          <code v-for="(c, i) in recoveryCodes" :key="i">{{ c }}</code>
        </div>
      </div>
      <template #footer><el-button type="primary" @click="recoveryVisible = false">我已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import QRCode from 'qrcode'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi, totpApi } from '../api'
import { useUserStore } from '../stores/user'

const store = useUserStore()
const nickname = ref(''), pwd = ref({ oldPassword: '', newPassword: '' })
const status = ref({ visible: false, enabled: false, canBind: false, recoveryCount: 0, deviceCount: 0 })
const devices = ref([])
const bindVisible = ref(false), bindSecret = ref(''), bindCode = ref(''), qr = ref('')
const verifyVisible = ref(false), verifyAction = ref(''), verifyType = ref('totp'), verifyCode = ref('')
const recoveryVisible = ref(false), recoveryCodes = ref([]), loading = ref(false)

onMounted(async () => {
  nickname.value = store.user?.nickname || ''
  try { const r = await authApi.me(); store.updateUser(r.data); nickname.value = r.data.nickname } catch (e) {}
  await loadStatus()
})

async function loadStatus() {
  try { status.value = (await totpApi.status()).data } catch (e) { status.value = { visible: false } }
}
async function loadDevices() {
  try { devices.value = (await totpApi.devices()).data } catch (e) { ElMessage.error(e.message) }
}
async function openBind() {
  bindCode.value = ''
  try {
    const r = await totpApi.bind()
    bindSecret.value = r.data.secret
    qr.value = await QRCode.toDataURL(r.data.otpauthUri, {
      margin: 1, width: 220, color: { dark: '#16433f', light: '#ffffff' },
    })
    bindVisible.value = true
  } catch (e) { ElMessage.error(e.message) }
}
function clearBind() {
  bindSecret.value = ''
  bindCode.value = ''
  qr.value = ''
}
async function copySecret() {
  try { await navigator.clipboard.writeText(bindSecret.value); ElMessage.success('密钥已复制') }
  catch (e) { ElMessage.warning('复制失败，请手动录入') }
}
async function confirmBind() {
  if (!/^\d{6}$/.test(bindCode.value)) return ElMessage.warning('请输入 6 位动态验证码')
  loading.value = true
  try {
    const r = await totpApi.verifyBind(bindCode.value)
    recoveryCodes.value = r.data.recoveryCodes || []
    bindVisible.value = false
    recoveryVisible.value = true
    await loadStatus()
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}
function openVerify(action) {
  verifyAction.value = action
  verifyType.value = 'totp'
  verifyCode.value = ''
  verifyVisible.value = true
}
async function submitVerify() {
  if (!verifyCode.value.trim()) return ElMessage.warning('请输入验证码或恢复码')
  loading.value = true
  try {
    const d = verifyType.value === 'totp'
      ? { code: verifyCode.value.trim() }
      : { recoveryCode: verifyCode.value.trim() }
    if (verifyAction.value === 'unbind') {
      await totpApi.unbind(d)
      ElMessage.success('二次验证已关闭')
      devices.value = []
    } else {
      const r = await totpApi.regenerate(d)
      recoveryCodes.value = r.data.recoveryCodes || []
      recoveryVisible.value = true
    }
    verifyVisible.value = false
    verifyCode.value = ''
    await loadStatus()
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}
async function removeDevice(row) {
  try {
    await ElMessageBox.confirm(`删除信任设备「${row.deviceName || '未命名设备'}」？`, '提示', { type: 'warning' })
    await totpApi.deleteDevice(row.id)
    ElMessage.success('已删除')
    await loadDevices(); await loadStatus()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

const wmText = computed(() => `${store.user?.username || 'LiteArk'} · ${new Date().toLocaleDateString()}`)

function size(n) { if (!n) return '0 B'; const u=['B','KB','MB','GB','TB'], i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),4); return (n/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i] }
const usedText = computed(() => size(store.user?.used || 0))
const quotaText = computed(() => size(store.user?.quota || 0))
const percent = computed(() => { const q = store.user?.quota || 0; return q ? Math.min(100, Math.round((store.user?.used || 0) / q * 100)) : 0 })
const circum = 2 * Math.PI * 50
const dashOffset = computed(() => circum * (1 - percent.value / 100))
async function saveNick() {
  try { await authApi.updateProfile({ nickname: nickname.value }); store.updateUser({ nickname: nickname.value }); ElMessage.success('已保存') }
  catch (e) { ElMessage.error(e.message) }
}
async function changePwd() {
  try { await authApi.changePassword(pwd.value); ElMessage.success('密码已修改，请重新登录'); pwd.value = { oldPassword: '', newPassword: '' } }
  catch (e) { ElMessage.error(e.message) }
}
</script>

<style scoped>
.quota-wrap { display: flex; align-items: center; gap: 24px; }
.ring { width: 120px; height: 120px; }
.ring-num { font-size: 20px; font-weight: 600; fill: var(--la-primary); }
.ring-label { font-size: 11px; fill: var(--la-muted); }
.quota-text div { font-size: 20px; font-weight: 600; color: var(--la-primary); }
.quota-text span { font-size: 14px; color: var(--la-muted); font-weight: 400; }
.quota-text p { color: var(--la-muted); font-size: 13px; }
.security-head { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; }
.security-title { font-size: 16px; font-weight: 600; display: flex; align-items: center; gap: 10px; }
.security-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.security-alert { margin-top: 16px; }
.device-wrap { margin-top: 18px; }
.device-wrap h4 { margin: 0 0 10px; font-size: 14px; }
.muted { color: var(--la-muted); font-size: 13px; line-height: 1.7; margin: 6px 0 0; }
.qr-wrap { display: flex; justify-content: center; padding: 10px 0; }
.qr-wrap img { width: 220px; height: 220px; border: 1px solid var(--el-border-color); border-radius: 8px; }
.bind-step { font-size: 13px; color: var(--la-text-color-regular, #303133); margin: 12px 0 8px; }
.secret-box { display: flex; align-items: center; gap: 8px; padding: 10px 12px; background: var(--el-fill-color-light); border-radius: 6px; margin-bottom: 14px; }
.secret-label { font-size: 12px; color: var(--la-muted); white-space: nowrap; }
.secret-box code { flex: 1; word-break: break-all; font-size: 13px; }
.code-watermark { position: relative; overflow: hidden; padding: 6px; margin-top: 14px; min-height: 160px; }
.code-watermark::after { content: attr(data-wm); position: absolute; left: -20%; top: 34%; width: 140%; text-align: center; transform: rotate(-24deg); font-size: 34px; font-weight: 700; color: #1f6b60; opacity: .08; pointer-events: none; white-space: nowrap; }
.recovery-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; position: relative; z-index: 1; }
.recovery-grid code { padding: 9px 10px; background: var(--el-fill-color-light); border-radius: 6px; font-size: 14px; letter-spacing: .5px; }
@media (max-width: 767px) { .el-row :deep(.el-col) { margin-bottom: 16px; } .security-head { flex-direction: column; } .security-actions { justify-content: flex-start; } .recovery-grid { grid-template-columns: 1fr; } }
</style>

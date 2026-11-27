<template>
  <div class="login-page">
    <div class="login-bg">
      <div class="login-brand">
        <BrandLogo :size="76" class="brand-mark" />
        <h1>轻舟 <span>LiteArk</span></h1>
        <p>轻量 · 私有 · 可靠的自托管文件网盘</p>
        <ul class="feature-list">
          <li>动态验证码由本机验证器生成</li>
          <li>验证码仅校验一次，不缓存不自动填充</li>
          <li>恢复码可应急登录，用后即失效</li>
        </ul>
      </div>
    </div>
    <div class="login-panel">
      <div class="login-card">
        <div class="card-brand">
          <BrandLogo :size="30" />
          <span>轻舟 <small>LiteArk</small></span>
        </div>
        <h2>二次验证</h2>
        <p class="subtitle">账号 {{ pending.username || '—' }} 已开启二次验证</p>

        <el-form v-if="!recoveryMode" label-position="top" @keyup.enter="submit">
          <el-form-item label="动态验证码">
            <el-input v-model="code" size="large" maxlength="6" inputmode="numeric"
              autocomplete="off" placeholder="请输入验证器中的 6 位数字" />
          </el-form-item>
          <el-checkbox v-model="trustDevice" class="trust">信任此设备（{{ ttlText }}内免验证）</el-checkbox>
          <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="submit">验证并登录</el-button>
        </el-form>

        <el-form v-else label-position="top" @keyup.enter="submit">
          <el-form-item label="备用恢复码">
            <el-input v-model="recoveryCode" size="large" autocomplete="off"
              placeholder="XXXX-XXXX-XXXX-XXXX" />
          </el-form-item>
          <p class="hint">恢复码仅可使用一次，登录后不会自动关闭二次验证，也不会信任当前设备。</p>
          <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="submit">使用恢复码登录</el-button>
        </el-form>

        <div class="switch-row">
          <el-button link type="primary" @click="recoveryMode = !recoveryMode">
            {{ recoveryMode ? '改用动态验证码' : '使用备用恢复码' }}
          </el-button>
          <el-button link @click="back">返回登录</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { totpApi } from '../api'
import { useUserStore } from '../stores/user'
import { useTotpPendingStore } from '../stores/totpPending'
import BrandLogo from '../components/BrandLogo.vue'

const router = useRouter()
const store = useUserStore()
const pending = useTotpPendingStore()
const code = ref('')
const recoveryCode = ref('')
const trustDevice = ref(false)
const recoveryMode = ref(false)
const loading = ref(false)
const ttlText = ref('30 天')

onMounted(() => {
  if (!pending.ready) {
    router.replace('/login')
    return
  }
  ttlText.value = Number(pending.ttlDays || 30) + ' 天'
})

async function submit() {
  if (!pending.ready) return router.replace('/login')
  loading.value = true
  try {
    const res = recoveryMode.value
      ? await totpApi.recoveryLogin({ pendingToken: pending.token, recoveryCode: recoveryCode.value.trim() })
      : await totpApi.verifyLogin({ pendingToken: pending.token, code: code.value.trim(), trustDevice: trustDevice.value })
    const redirect = pending.redirect
    store.setLogin(res.data.token, res.data.user)
    pending.clear()
    code.value = ''
    recoveryCode.value = ''
    ElMessage.success('登录成功')
    router.push(redirect || (res.data.user.role_id === 1 ? '/admin/overview' : '/files'))
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function back() {
  pending.clear()
  router.push('/login')
}
</script>

<style scoped>
.login-page { display: flex; min-height: 100vh; }
.login-bg {
  flex: 1; background: linear-gradient(135deg, #16433f 0%, #1f6b60 50%, #3aaf9f 100%);
  display: flex; align-items: center; justify-content: center; color: #fff;
}
.login-brand { max-width: 460px; padding: 40px; }
.brand-mark { border-radius: 18px; margin-bottom: 18px; box-shadow: 0 10px 28px rgba(0,0,0,.24); }
.login-brand h1 { font-size: 46px; margin: 0 0 6px; letter-spacing: 2px; }
.login-brand h1 span { font-size: 22px; font-weight: 300; opacity: .8; }
.login-brand p { font-size: 16px; opacity: .85; margin-bottom: 32px; }
.feature-list { list-style: none; padding: 0; line-height: 2.4; font-size: 15px; opacity: .92; }
.login-panel { width: 520px; display: flex; align-items: center; justify-content: center; background: #fff; }
.login-card { width: 360px; }
.card-brand { display: none; align-items: center; gap: 10px; margin-bottom: 20px; font-size: 18px; font-weight: 600; color: #1f4e4d; }
.card-brand small { font-size: 12px; font-weight: 300; color: var(--la-muted); }
.login-card h2 { font-size: 28px; margin: 0 0 6px; }
.subtitle { color: var(--la-muted); margin: 0 0 28px; }
.login-btn { width: 100%; margin-top: 14px; }
.trust { margin-top: 2px; }
.hint { color: var(--la-muted); font-size: 12px; line-height: 1.7; margin: 0; }
.switch-row { display: flex; justify-content: space-between; margin-top: 18px; }

@media (max-width: 900px) {
  .login-bg { display: none; }
  .login-panel { width: 100%; align-items: flex-start; padding-top: 12vh; }
  .login-card { width: 100%; max-width: 420px; padding: 0 24px; }
  .login-card h2 { font-size: 24px; }
  .card-brand { display: flex; }
}
</style>

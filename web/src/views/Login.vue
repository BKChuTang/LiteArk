<template>
  <div class="login-page">
    <div class="login-bg">
      <div class="login-brand">
        <BrandLogo :size="76" class="brand-mark" />
        <h1>轻舟 <span>LiteArk</span></h1>
        <p>轻量 · 私有 · 可靠的自托管文件网盘</p>
        <ul class="feature-list">
          <li>纯浏览器访问，无需安装客户端</li>
          <li>SQLite 单文件，内网离线可部署</li>
          <li>PC 与手机同一套界面，随时存取</li>
        </ul>
      </div>
    </div>
    <div class="login-panel">
      <div class="login-card">
        <div class="card-brand">
          <BrandLogo :size="30" />
          <span>轻舟 <small>LiteArk</small></span>
        </div>
        <h2>登录账号</h2>
        <p class="subtitle">欢迎回来，请登录以继续</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="onLogin">
          <el-form-item label="账号" prop="username">
            <el-input v-model="form.username" placeholder="请输入账号" size="large" :prefix-icon="User" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large"
              :prefix-icon="Lock" show-password />
          </el-form-item>
          <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onLogin">登 录</el-button>
        </el-form>
        <div class="register-row">还没有账号？<el-button link type="primary" @click="showRegister = true">立即注册</el-button></div>
      </div>
    </div>

    <el-dialog v-model="showRegister" title="注册账号" width="420px">
      <el-form ref="regRef" :model="reg" :rules="regRules" label-position="top">
        <el-form-item label="账号" prop="username"><el-input v-model="reg.username" placeholder="3-32 位" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="reg.nickname" placeholder="选填" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="reg.password" type="password" show-password placeholder="至少 6 位" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRegister = false">取消</el-button>
        <el-button type="primary" @click="onRegister">注册</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'
import { useUserStore } from '../stores/user'
import { useTotpPendingStore } from '../stores/totpPending'
import BrandLogo from '../components/BrandLogo.vue'

const router = useRouter()
const route = useRoute()
const store = useUserStore()
const pending = useTotpPendingStore()
const formRef = ref()
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const loading = ref(false)

async function onLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await authApi.login(form)
    if (res.data.needTotp) {
      pending.start(res.data.pendingToken, res.data.username, route.query.redirect || '', res.data.trustedDeviceTtlDays)
      router.push('/totp')
      return
    }
    pending.clear()
    store.setLogin(res.data.token, res.data.user)
    ElMessage.success('登录成功')
    router.push(route.query.redirect || (res.data.user.role_id === 1 ? '/admin/overview' : '/files'))
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

const showRegister = ref(false)
const regRef = ref()
const reg = reactive({ username: '', nickname: '', password: '' })
const regRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, min: 6, message: '至少 6 位', trigger: 'blur' }],
}
async function onRegister() {
  await regRef.value.validate()
  try {
    await authApi.register(reg)
    ElMessage.success('注册成功，请登录')
    showRegister.value = false
    form.username = reg.username
    form.password = ''
  } catch (e) { ElMessage.error(e.message) }
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
.card-brand :deep(.brand-logo) { border-radius: 7px; box-shadow: 0 1px 4px rgba(31,78,77,.16); }
.card-brand small { font-size: 12px; font-weight: 300; color: var(--la-muted); margin-left: 2px; }
.login-card h2 { font-size: 28px; margin: 0 0 6px; }
.subtitle { color: var(--la-muted); margin: 0 0 28px; }
.login-btn { width: 100%; margin-top: 8px; }
.register-row { text-align: center; margin-top: 18px; color: var(--la-muted); }

@media (max-width: 900px) {
  .login-bg { display: none; }
  .login-panel { width: 100%; align-items: flex-start; padding-top: 12vh; }
  .login-card { width: 100%; max-width: 420px; padding: 0 24px; }
  .login-card h2 { font-size: 24px; }
  .card-brand { display: flex; }
}
</style>

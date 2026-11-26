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
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'
import { useUserStore } from '../stores/user'

const store = useUserStore()
const nickname = ref(''), pwd = ref({ oldPassword: '', newPassword: '' })
onMounted(async () => {
  nickname.value = store.user?.nickname || ''
  try { const r = await authApi.me(); store.updateUser(r.data); nickname.value = r.data.nickname } catch (e) {}
})
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
@media (max-width: 767px) { .el-row :deep(.el-col) { margin-bottom: 16px; } }
</style>

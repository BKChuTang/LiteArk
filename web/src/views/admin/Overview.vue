<template>
  <div class="page-container">
    <div class="page-header"><h2 class="page-title">系统概览</h2><el-button :icon="Refresh" @click="load">刷新</el-button></div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <div class="stat-card"><div class="label">{{ c.label }}</div><div class="value">{{ c.value }}</div></div>
      </el-col>
    </el-row>
    <el-card header="快速入口" style="margin-top:18px">
      <el-button type="primary" @click="$router.push('/admin/users')">用户管理</el-button>
      <el-button @click="$router.push('/admin/roles')">角色管理</el-button>
      <el-button @click="$router.push('/admin/shares')">共享管理</el-button>
      <el-button @click="$router.push('/admin/logs')">操作日志</el-button>
      <el-button @click="$router.push('/admin/config')">系统配置</el-button>
    </el-card>
    <el-card header="构建基线（V1.1）" style="margin-top:18px">
      <p class="baseline">SpringBoot 2.7.18 · Java 8 · MyBatis-Plus · SQLite (sqlite-jdbc) · 单一 fat jar · 前端 dist 内嵌 static</p>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '../../api'

const data = ref({ users: 0, files: 0, totalSize: 0, shares: 0, logs: 0 })
async function load() { try { data.value = (await adminApi.overview()).data } catch (e) {} }
onMounted(load)
function size(n) { if (!n) return '0 B'; const u=['B','KB','MB','GB','TB'], i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),4); return (n/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i] }
const cards = computed(() => [
  { label: '用户总数', value: data.value.users },
  { label: '文件总数', value: data.value.files },
  { label: '存储占用', value: size(data.value.totalSize) },
  { label: '有效共享', value: data.value.shares },
])
</script>

<style scoped>
.baseline { color: var(--la-muted); line-height: 1.8; margin: 0; }
@media (max-width: 767px) { .el-row :deep(.el-col) { margin-bottom: 12px; } }
</style>

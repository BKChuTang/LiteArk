<template>
  <div class="page-container">
    <div class="page-header"><h2 class="page-title">最近</h2><el-button :icon="Refresh" @click="load">刷新</el-button></div>
    <el-alert type="info" :closable="false" title="按修改时间倒序展示最近访问的文件，点击可进入所在目录。" style="margin-bottom:16px" />
    <el-table :data="list" v-loading="loading" border style="width:100%">
      <el-table-column label="文件名" min-width="220">
        <template #default="{ row }">
          <el-icon style="vertical-align:middle;margin-right:6px"><component :is="row.isDir ? Folder : Document" /></el-icon>
          {{ row.name }}
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120"><template #default="{ row }">{{ row.isDir ? '—' : size(row.size) }}</template></el-table-column>
      <el-table-column prop="updatedAt" label="修改时间" width="180" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="open(row)">{{ row.isDir ? '进入' : '预览' }}</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无最近文件" /></template>
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Folder, Document } from '@element-plus/icons-vue'
import { fileApi } from '../api'

const router = useRouter()
const list = ref([]), loading = ref(false)
async function load() {
  loading.value = true
  try { list.value = (await fileApi.list(0, '')).data.slice(0, 50) } catch (e) {} finally { loading.value = false }
}
function size(n) {
  if (!n) return '0 B'; const u = ['B','KB','MB','GB','TB'], i = Math.min(Math.floor(Math.log(n)/Math.log(1024)),4)
  return (n/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i]
}
function open(row) { row.isDir ? router.push('/files') : window.open(fileApi.previewUrl(row.id), '_blank') }
onMounted(load)
</script>

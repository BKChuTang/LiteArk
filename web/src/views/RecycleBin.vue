<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">回收站</h2>
      <div><el-button :icon="Refresh" @click="load">刷新</el-button><el-button type="danger" :icon="Delete" :disabled="!list.length" @click="clear">清空</el-button></div>
    </div>
    <el-table :data="list" v-loading="loading" border style="width:100%">
      <el-table-column label="名称" min-width="220">
        <template #default="{ row }"><el-icon style="vertical-align:middle;margin-right:6px"><component :is="row.isDir ? Folder : Document" /></el-icon>{{ row.name }}</template>
      </el-table-column>
      <el-table-column prop="deletedAt" label="删除时间" width="190" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="restore(row)">还原</el-button>
          <el-button link type="danger" size="small" @click="perm(row)">永久删除</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="回收站是空的" /></template>
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Delete, Folder, Document } from '@element-plus/icons-vue'
import { fileApi } from '../api'

const list = ref([]), loading = ref(false)
async function load() { loading.value = true; try { list.value = (await fileApi.recycle()).data } catch (e) {} finally { loading.value = false } }
onMounted(load)
async function restore(row) { try { await fileApi.restore(row.id); ElMessage.success('已还原'); load() } catch (e) { ElMessage.error(e.message) } }
async function perm(row) {
  try { await ElMessageBox.confirm(`永久删除「${row.name}」？不可恢复。`, '警告', { type: 'error' }); await fileApi.permanentDelete(row.id); ElMessage.success('已删除'); load() }
  catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
async function clear() {
  try { await ElMessageBox.confirm('清空回收站？所有文件将永久删除。', '警告', { type: 'error' }); await fileApi.clearRecycle(); ElMessage.success('已清空'); load() }
  catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
</script>

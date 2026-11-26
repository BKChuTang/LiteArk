<template>
  <div class="page-container">
    <div class="page-header"><h2 class="page-title">共享管理</h2><el-button :icon="Refresh" @click="load">刷新</el-button></div>
    <el-table :data="list" v-loading="loading" border style="width:100%">
      <el-table-column label="文件ID" prop="fileId" width="100" />
      <el-table-column prop="fileName" label="文件名" min-width="160" />
      <el-table-column prop="ownerName" label="创建人" width="120" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }"><el-tag size="small" :type="row.shareType === 'internal' ? 'warning' : 'primary'" effect="plain">{{ row.shareType === 'internal' ? '内部分享' : '外部分享' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="权限" width="100"><template #default="{ row }"><el-tag size="small">{{ perm(row.permission) }}</el-tag></template></el-table-column>
      <el-table-column prop="expiresAt" label="过期时间" width="170"><template #default="{ row }">{{ row.expiresAt || '永久' }}</template></el-table-column>
      <el-table-column label="下载次数" width="120"><template #default="{ row }">{{ row.downloadCount || 0 }}{{ row.maxDownloads ? ' / ' + row.maxDownloads : ' / 不限' }}</template></el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">管控</el-button>
          <el-button link type="primary" size="small" @click="openAccess(row)">访问明细</el-button>
          <el-button link type="danger" size="small" @click="revoke(row)">撤销</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无共享链接" /></template>
    </el-table>
    <el-dialog v-model="editVisible" title="共享管控" width="460px">
      <el-form label-position="top">
        <el-form-item label="访问权限"><el-radio-group v-model="form.permission">
          <el-radio-button value="view">仅查看</el-radio-button><el-radio-button value="download">可下载</el-radio-button><el-radio-button value="edit">可编辑</el-radio-button>
        </el-radio-group></el-form-item>
        <el-form-item label="访问密码"><el-input v-model="form.password" /></el-form-item>
        <el-form-item label="有效期（天，0 永久）"><el-input-number v-model="form.expireDays" :min="0" :max="3650" /></el-form-item>
        <el-form-item label="下载次数限制（0 不限）"><el-input-number v-model="form.maxDownloads" :min="0" :max="100000" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editVisible = false">取消</el-button><el-button type="primary" @click="saveEdit">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="accessVisible" title="访问明细" width="720px">
      <el-table :data="accessList" size="small" border max-height="480">
        <el-table-column prop="accessedAt" label="时间" width="180" />
        <el-table-column prop="clientType" label="端类型" width="100" />
        <el-table-column prop="ip" label="IP" width="150" />
        <el-table-column prop="ua" label="UA" min-width="260" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { adminApi } from '../../api'

const list = ref([]), loading = ref(false)
async function load() { loading.value = true; try { list.value = (await adminApi.shares()).data } catch (e) {} finally { loading.value = false } }
onMounted(load)
function perm(p) { return { view:'仅查看', download:'可下载', edit:'可编辑' }[p] || p }
const editVisible = ref(false), editing = ref(null), form = ref({ permission:'view', password:'', expireDays:7, maxDownloads:0 })
function openEdit(row) { editing.value = row; form.value = { permission: row.permission, password: row.password, expireDays: 7, maxDownloads: row.maxDownloads || 0 }; editVisible.value = true }
async function saveEdit() { try { await adminApi.updateShare(editing.value.id, { permission: form.value.permission, password: form.value.password, expireDays: form.value.expireDays, maxDownloads: form.value.maxDownloads }); ElMessage.success('保存成功'); editVisible.value = false; load() } catch (e) { ElMessage.error(e.message) } }
async function revoke(row) { try { await ElMessageBox.confirm('确定撤销该共享？', '提示', { type: 'warning' }); await adminApi.updateShare(row.id, { revoked: 1 }); ElMessage.success('已撤销'); load() } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) } }
const accessVisible = ref(false), accessList = ref([])
async function openAccess(row) { try { accessList.value = (await adminApi.shareAccess(row.id)).data; accessVisible.value = true } catch (e) { ElMessage.error(e.message) } }
</script>

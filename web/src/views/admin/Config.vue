<template>
  <div class="page-container">
    <div class="page-header"><h2 class="page-title">系统配置</h2><el-button type="primary" :icon="Check" @click="save">保存配置</el-button></div>
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card header="上传配置（按端分开）">
          <el-form label-position="top">
            <el-form-item label="PC 分片大小（字节）"><el-input v-model="cfg.chunk_size_pc" /></el-form-item>
            <el-form-item label="移动端分片大小（字节）"><el-input v-model="cfg.chunk_size_mobile" /></el-form-item>
            <el-form-item label="单文件上限（字节）"><el-input v-model="cfg.max_file_size" /></el-form-item>
            <el-form-item label="同名冲突策略">
              <el-select v-model="cfg.conflict_strategy" style="width:100%">
                <el-option value="rename" label="自动重命名" /><el-option value="overwrite" label="覆盖" /><el-option value="skip" label="跳过" />
              </el-select>
            </el-form-item>
          </el-form>
        </el-card>
        <el-card header="安全与运维配置" style="margin-top:20px">
          <el-form label-position="top">
            <el-form-item label="共享默认有效期（天，0 永久）"><el-input-number v-model="cfg.default_share_expire" :min="0" :max="3650" /></el-form-item>
            <el-form-item label="PC 上传并发"><el-input-number v-model="cfg.upload_concurrency_pc" :min="1" :max="16" /></el-form-item>
            <el-form-item label="移动端上传并发"><el-input-number v-model="cfg.upload_concurrency_mobile" :min="1" :max="8" /></el-form-item>
            <el-form-item label="回收站保留天数"><el-input-number v-model="cfg.recycle_retention_days" :min="1" :max="3650" /></el-form-item>
            <el-form-item label="日志保留天数"><el-input-number v-model="cfg.log_retention_days" :min="1" :max="3650" /></el-form-item>
          </el-form>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card header="站点与文档配置">
          <el-form label-position="top">
            <el-form-item label="站点名称"><el-input v-model="cfg.site_name" /></el-form-item>
            <el-form-item label="默认用户配额（字节）"><el-input v-model="cfg.default_quota" /></el-form-item>
            <el-form-item label="在线编辑总开关"><el-switch v-model="cfg.online_edit_enabled" active-value="true" inactive-value="false" /></el-form-item>
            <el-form-item label="编辑后缀白名单（逗号分隔）"><el-input v-model="cfg.edit_ext_whitelist" /></el-form-item>
            <el-form-item label="Office 转码预览（需 LibreOffice）">
              <el-switch v-model="cfg.office_preview_enabled" active-value="true" inactive-value="false" />
            </el-form-item>
          </el-form>
        </el-card>
        <el-card header="部署说明" style="margin-top:20px">
          <p class="note">外置配置目录：<code>/opt/liteark/conf/</code><br />数据目录：<code>/opt/liteark/data/</code><br />启动：<code>java -jar liteark.jar --spring.config.additional-location=/opt/liteark/conf/</code></p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Check } from '@element-plus/icons-vue'
import { adminApi } from '../../api'

const cfg = ref({})
async function load() {
  try {
    const r = await adminApi.config()
    cfg.value = {
      chunk_size_pc: r.data.chunk_size_pc || '8388608',
      chunk_size_mobile: r.data.chunk_size_mobile || '2097152',
      max_file_size: r.data.max_file_size || '2147483648',
      conflict_strategy: r.data.conflict_strategy || 'rename',
      default_share_expire: Number(r.data.default_share_expire || 7),
      site_name: r.data.site_name || '轻舟 LiteArk',
      default_quota: r.data.default_quota || '21474836480',
      upload_concurrency_pc: Number(r.data.upload_concurrency_pc || 4),
      upload_concurrency_mobile: Number(r.data.upload_concurrency_mobile || 2),
      recycle_retention_days: Number(r.data.recycle_retention_days || 30),
      log_retention_days: Number(r.data.log_retention_days || 180),
      office_preview_enabled: r.data.office_preview_enabled || 'true',
      online_edit_enabled: r.data.online_edit_enabled || 'true',
      edit_ext_whitelist: r.data.edit_ext_whitelist || 'txt,md,csv,json,html',
    }
  } catch (e) {}
}
async function save() { try { await adminApi.saveConfig(cfg.value); ElMessage.success('配置已保存') } catch (e) { ElMessage.error(e.message) } }
onMounted(load)
</script>

<style scoped>
.note { color: var(--la-muted); line-height: 2; font-size: 13px; margin: 0; }
</style>

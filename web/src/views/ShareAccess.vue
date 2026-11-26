<template>
  <div class="share-page">
    <div class="share-header"><BrandLogo :size="24" class="mark" /> 轻舟 LiteArk <span class="sub">· 共享访问</span></div>
    <div class="share-body">
      <div v-if="loading" class="center-box">
        <el-skeleton :rows="6" animated class="skeleton" />
        <p class="loading-text">加载中...</p>
      </div>
      <div v-else-if="needPassword" class="center-box card">
        <el-icon :size="46" color="#1f6b60"><Lock /></el-icon>
        <h2>该共享需要访问密码</h2>
        <el-input v-model="password" type="password" placeholder="请输入访问密码" show-password class="pwd-input"
          @keyup.enter="loadInfo" />
        <el-button type="primary" class="pwd-btn" @click="loadInfo">访问</el-button>
      </div>
      <div v-else-if="error" class="center-box card">
        <el-icon :size="46" color="#d9534f"><CircleClose /></el-icon>
        <h2>{{ error }}</h2>
        <p>链接可能已失效、过期或被撤销</p>
        <el-button type="primary" size="small" @click="loadInfo">重试</el-button>
      </div>
      <template v-else>
        <div class="share-info card">
          <el-icon :size="30" color="#1f6b60"><component :is="info.file.isDir ? Folder : Document" /></el-icon>
          <div class="info-main">
            <div class="share-title">{{ info.file.name }}</div>
            <div class="share-meta">
              {{ info.shareType === 'internal' ? '内部分享' : '外部分享' }} · {{ permText(info.permission) }} · {{ info.file.isDir ? '文件夹' : size(info.file.size) }}
              <span v-if="info.expiresAt"> · 有效期至 {{ info.expiresAt }}</span>
              <span v-if="info.maxDownloads > 0"> · 已下载 {{ info.downloadCount || 0 }}/{{ info.maxDownloads }}</span>
            </div>
          </div>
          <el-button v-if="!info.file.isDir && info.permission !== 'view'" type="primary" :icon="Download" @click="download(info.file)">下载</el-button>
        </div>

        <div v-if="info.file.isDir" class="share-list card">
          <div v-for="f in list" :key="f.id" class="list-row" @click="f.isDir ? enter(f.id) : null">
            <el-icon :size="18" :color="f.isDir ? '#e0a030' : '#8492a6'"><component :is="f.isDir ? Folder : Document" /></el-icon>
            <span class="row-name">{{ f.name }}</span>
            <span class="row-size">{{ f.isDir ? '—' : size(f.size) }}</span>
            <el-button v-if="!f.isDir && info.permission !== 'view'" link type="primary" size="small" @click.stop="download(f)">下载</el-button>
          </div>
          <el-empty v-if="!list.length" description="此文件夹为空" />
        </div>

        <div v-else class="preview-box card">
          <el-image v-if="isImage(info.file)" :src="prevUrl" fit="contain" class="image-prev" />
          <video v-else-if="isVideo(info.file)" :src="prevUrl" class="video-prev" controls preload="metadata" />
          <audio v-else-if="isAudio(info.file)" :src="prevUrl" class="audio-prev" controls preload="metadata" />
          <iframe v-else-if="info.file.ext === 'pdf'" :src="prevUrl" width="100%" height="620px" />
          <div v-else-if="info.file.ext === 'md'" class="md-body" v-html="mdHtml" />
          <div v-else-if="info.file.ext === 'csv'">
            <el-table :data="csvRows" height="520px" border size="small">
              <el-table-column v-for="(h, i) in csvHeader" :key="i" :label="h" :prop="'c' + i" min-width="120" />
            </el-table>
            <p v-if="csvTruncated" class="tip">仅显示前 200 行，完整内容请下载查看</p>
          </div>
          <iframe v-else-if="isOffice(info.file) && !officeError" :src="officeUrl" width="100%" height="620px" />
          <pre v-else-if="isText(info.file)" class="text-prev">{{ text }}</pre>
          <div v-else class="no-prev">
            <el-icon :size="60"><Document /></el-icon>
            <p>{{ officeError || '该文件类型暂不支持在线预览' }}</p>
            <p v-if="isOffice(info.file)" class="tip">共享页 Office 文档一律只读，电脑端可下载后编辑</p>
            <el-button v-if="info.permission !== 'view'" type="primary" size="small" @click="download(info.file)">下载查看</el-button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading, Lock, CircleClose, Folder, Document, Download } from '@element-plus/icons-vue'
import { shareApi } from '../api'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import BrandLogo from '../components/BrandLogo.vue'

const route = useRoute()
const token = computed(() => route.params.token)
const loading = ref(true), needPassword = ref(false), error = ref(''), password = ref('')
const info = ref(null), list = ref([]), parentId = ref(0), text = ref('')
const mdHtml = ref(''), csvHeader = ref([]), csvRows = ref([]), csvTruncated = ref(false), officeError = ref('')
const prevUrl = computed(() => info.value ? shareApi.publicPreviewUrl(token.value, info.value.file.id, password.value) : '')
const officeUrl = computed(() => info.value ? shareApi.publicOfficePreviewUrl(token.value, info.value.file.id, password.value) : '')

function size(n) { if (!n) return '0 B'; const u=['B','KB','MB','GB','TB'], i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),4); return (n/Math.pow(1024,i)).toFixed(i?1:0)+' '+u[i] }
const IMAGE_EXT = ['png','jpg','jpeg','gif','webp','bmp','svg']
const VIDEO_EXT = ['mp4','mov','webm','ogv','m4v']
const AUDIO_EXT = ['mp3','m4a','wav','flac','ogg','aac']
function isImage(f) { return !!f && !f.isDir && IMAGE_EXT.includes(f.ext) }
function isVideo(f) { return !!f && !f.isDir && VIDEO_EXT.includes(f.ext) }
function isAudio(f) { return !!f && !f.isDir && AUDIO_EXT.includes(f.ext) }
function isText(f) { return ['txt','md','csv','json','html'].includes(f.ext) }
function isOffice(f) { return ['docx','xlsx','pptx','doc','xls','ppt'].includes(f.ext) }
function permText(p) { return { view:'仅查看', download:'可下载', edit:'可编辑' }[p] || p }

async function loadInfo() {
  loading.value = true; error.value = ''; needPassword.value = false
  try {
    const r = await shareApi.info(token.value, password.value)
    if (r.data.needPassword) { needPassword.value = true; return }
    info.value = r.data; parentId.value = 0
    mdHtml.value = ''; csvHeader.value = []; csvRows.value = []; csvTruncated.value = false; officeError.value = ''
    if (info.value.file.isDir) await loadList()
    else if (isOffice(info.value.file)) await checkOffice()
    else if (isText(info.value.file)) await loadText()
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function loadList() {
  try { list.value = (await shareApi.publicList(token.value, parentId.value, password.value)).data }
  catch (e) { ElMessage.error(e.message) }
}
async function checkOffice() {
  officeError.value = ''
  try {
    const res = await fetch(officeUrl.value)
    if (!res.ok) {
      const d = await res.json().catch(() => ({}))
      officeError.value = d.msg || 'Office 预览不可用'
    }
  } catch (e) { officeError.value = 'Office 预览不可用: ' + e.message }
}
function parseCsv(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.length)
  if (!lines.length) return { header: [], rows: [], truncated: false }
  const split = (l) => l.split(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/).map((s) => s.replace(/^"|"$/g, '').trim())
  const header = split(lines[0])
  const rows = lines.slice(1, 201).map((line) => {
    const cells = split(line), o = {}
    header.forEach((h, j) => { o['c' + j] = cells[j] || '' })
    return o
  })
  return { header, rows, truncated: lines.length > 201 }
}
async function loadText() {
  try {
    const raw = await (await fetch(shareApi.publicDownloadUrl(token.value, info.value.file.id, password.value))).text()
    if (info.value.file.ext === 'md') mdHtml.value = DOMPurify.sanitize(marked.parse(raw))
    else if (info.value.file.ext === 'csv') {
      const p = parseCsv(raw); csvHeader.value = p.header; csvRows.value = p.rows; csvTruncated.value = p.truncated
    } else text.value = raw
  } catch (e) { text.value = '加载失败' }
}
function enter(id) { parentId.value = id; loadList() }
function download(f) { window.open(shareApi.publicDownloadUrl(token.value, f.id, password.value), '_blank') }
watch(() => route.params.token, loadInfo)
onMounted(loadInfo)
</script>

<style scoped>
.share-page { min-height: 100vh; background: var(--la-bg); }
.share-header { background: #1f4e4d; color: #fff; padding: 14px 28px; font-size: 17px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
.share-header .mark { border-radius: 6px; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.share-header .sub { font-size: 12px; font-weight: 300; opacity: .7; }
.share-body { max-width: 900px; margin: 32px auto; padding: 0 16px; }
.card { background: #fff; border: 1px solid var(--la-border); border-radius: var(--la-radius); }
.center-box { text-align: center; padding: 60px 20px; }
.center-box h2 { margin: 16px 0 8px; font-size: 20px; }
.skeleton { max-width: 640px; margin: 0 auto; text-align: left; }
.loading-text { color: var(--la-muted); }
.pwd-input { width: 280px; margin: 16px 0; }
.pwd-btn { display: block; margin: 0 auto; min-width: 140px; }
.share-info { display: flex; align-items: center; gap: 14px; padding: 20px; }
.info-main { flex: 1; min-width: 0; }
.share-title { font-size: 18px; font-weight: 600; word-break: break-all; }
.share-meta { font-size: 13px; color: var(--la-muted); margin-top: 6px; }
.share-list { margin-top: 14px; overflow: hidden; }
.list-row { display: flex; align-items: center; gap: 10px; padding: 14px 18px; border-bottom: 1px solid #eef1f3; min-height: 48px; }
.row-name { flex: 1; word-break: break-all; }
.row-size { color: var(--la-muted); font-size: 12px; }
.preview-box { margin-top: 14px; padding: 16px; min-height: 320px; }
.image-prev { width: 100%; }
.video-prev { width: 100%; max-height: 70vh; background: #000; border-radius: 6px; }
.audio-prev { width: 100%; margin: 40px 0; }
.text-prev { white-space: pre-wrap; word-break: break-all; line-height: 1.7; font-size: 13px; }
.no-prev { text-align: center; color: var(--la-muted); padding: 60px 0; }
.no-prev .tip { font-size: 12px; color: var(--la-primary); }
.md-body { max-height: 60vh; overflow: auto; line-height: 1.8; padding: 0 6px; }
.md-body :deep(img) { max-width: 100%; }
.md-body :deep(table) { border-collapse: collapse; width: 100%; margin: 12px 0; font-size: 13px; }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid var(--la-border); padding: 7px 10px; text-align: left; }
.md-body :deep(th) { background: #f2f6f5; font-weight: 600; }
.md-body :deep(pre) { background: #f6f8f8; border: 1px solid var(--la-border); border-radius: 6px; padding: 12px; overflow: auto; }
.preview-box .tip { font-size: 12px; color: var(--la-muted); }
@media (max-width: 767px) {
  .share-header { padding: 12px 16px; }
  .share-body { margin: 16px auto; padding: 0 12px; }
  .share-info { flex-wrap: wrap; padding: 16px; }
  .share-info .el-button { width: 100%; min-height: 44px; }
  .pwd-input { width: 100%; max-width: 320px; }
  .list-row { padding: 12px; }
}
</style>

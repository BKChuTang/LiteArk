import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

// 与 vite base 对齐：/liteark/api；根路径部署时为 /api
const BASE_PATH = import.meta.env.BASE_URL.replace(/\/$/, '')
const api = axios.create({ baseURL: BASE_PATH + '/api', timeout: 120000 })

function isMobile() {
  return window.innerWidth < 768 || /Android|iPhone|iPad|Mobile|Harmony/i.test(navigator.userAgent)
}

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('liteark_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  config.headers['X-Client-Type'] = isMobile() ? 'Mobile' : 'PC'
  return config
})

api.interceptors.response.use(
  (res) => {
    const d = res.data
    if (d && d.code !== undefined && d.code !== 0) {
      if (d.code === 401) {
        localStorage.removeItem('liteark_token')
        router.push('/login')
      }
      return Promise.reject(new Error(d.msg || '请求失败'))
    }
    return d
  },
  (err) => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('liteark_token')
      router.push('/login')
    }
    const msg = err.response?.data?.msg || err.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default api

export const authApi = {
  login: (d) => api.post('/auth/login', d),
  register: (d) => api.post('/auth/register', d),
  me: () => api.get('/auth/me'),
  changePassword: (d) => api.put('/auth/password', d),
  updateProfile: (d) => api.put('/auth/profile', d),
}

export const fileApi = {
  tree: () => api.get('/files/tree'),
  list: (parentId, keyword) => api.get('/files/list', { params: { parentId, keyword } }),
  breadcrumb: (id) => api.get('/files/breadcrumb', { params: { id } }),
  createFolder: (d) => api.post('/files/folder', d),
  rename: (id, name) => api.put(`/files/${id}/rename`, { name }),
  copy: (ids, targetId) => api.post('/files/copy', { ids, targetId }),
  move: (ids, targetId) => api.post('/files/move', { ids, targetId }),
  remove: (ids) => api.delete('/files', { params: { ids: ids.join(',') } }),
  recycle: () => api.get('/files/recycle'),
  restore: (id) => api.post(`/files/${id}/restore`),
  permanentDelete: (id) => api.delete(`/files/${id}/permanent`),
  clearRecycle: () => api.delete('/files/recycle/clear'),
  versions: (id) => api.get(`/files/${id}/versions`),
  rollback: (id, versionId) => api.post(`/files/${id}/versions/${versionId}/rollback`),
  saveText: (id, content) => api.put(`/files/${id}/save-text`, content, {
    headers: { 'Content-Type': 'text/plain;charset=utf-8' },
  }),
  upload: (formData, onProgress) => api.post('/files/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round(e.loaded * 100 / e.total))
    },
  }),
  downloadUrl: (id) => `${BASE_PATH}/api/files/${id}/download?token=${localStorage.getItem('liteark_token') || ''}`,
  previewUrl: (id) => `${BASE_PATH}/api/files/${id}/preview?token=${localStorage.getItem('liteark_token') || ''}`,
  thumbnailUrl: (id, w = 200) => `${BASE_PATH}/api/files/${id}/thumbnail?w=${w}&token=${localStorage.getItem('liteark_token') || ''}`,
  officePreviewUrl: (id) => `${BASE_PATH}/api/files/${id}/office-preview?token=${localStorage.getItem('liteark_token') || ''}`,
  officePreviewInfo: (id) => api.get(`/files/${id}/office-preview/info`),
  folderInfo: (folderId) => api.get('/files/folder-info', { params: { folderId } }),
}

export const uploadApi = {
  config: () => api.get('/upload/config'),
  init: (d) => api.post('/upload/init', d),
  chunk: (formData, onProgress) => api.post('/upload/chunk', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round(e.loaded * 100 / e.total)) },
  }),
  status: (uploadId) => api.get('/upload/status', { params: { uploadId } }),
  complete: (uploadId) => api.post('/upload/complete', { uploadId }),
  cancel: (uploadId) => api.post('/upload/cancel', { uploadId }),
}

export const shareApi = {
  list: () => api.get('/shares'),
  users: () => api.get('/shares/users'),
  create: (d) => api.post('/shares', d),
  update: (id, d) => api.put(`/shares/${id}`, d),
  revoke: (id) => api.delete(`/shares/${id}`),
  access: (id) => api.get(`/shares/${id}/access`),
  info: (token, password) => api.get(`/share/${token}/info`, { params: { password } }),
  publicList: (token, parentId, password) => api.get(`/share/${token}/list`, { params: { parentId, password } }),
  publicDownloadUrl: (token, fileId, password) => `${BASE_PATH}/api/share/${token}/download/${fileId}?password=${encodeURIComponent(password || '')}`,
  publicPreviewUrl: (token, fileId, password) => `${BASE_PATH}/api/share/${token}/preview/${fileId}?password=${encodeURIComponent(password || '')}`,
  publicOfficePreviewUrl: (token, fileId, password) => `${BASE_PATH}/api/share/${token}/office-preview/${fileId}?password=${encodeURIComponent(password || '')}`,
}

// 普通用户侧自助授权：只能对自己拥有的文件夹操作，权限递归作用于全部下级
export const grantApi = {
  detail: (folderId) => api.get('/grants', { params: { folderId } }),
  mine: () => api.get('/grants/mine'),
  save: (d) => api.post('/grants', d),
  revoke: (id) => api.delete(`/grants/${id}`),
}

export const adminApi = {
  overview: () => api.get('/admin/overview'),
  users: () => api.get('/admin/users'),
  addUser: (d) => api.post('/admin/users', d),
  updateUser: (id, d) => api.put(`/admin/users/${id}`, d),
  resetPassword: (id, password) => api.put(`/admin/users/${id}/password`, { password }),
  deleteUser: (id) => api.delete(`/admin/users/${id}`),
  roles: () => api.get('/admin/roles'),
  addRole: (d) => api.post('/admin/roles', d),
  updateRole: (id, d) => api.put(`/admin/roles/${id}`, d),
  deleteRole: (id) => api.delete(`/admin/roles/${id}`),
  shares: () => api.get('/admin/shares'),
  updateShare: (id, d) => api.put(`/admin/shares/${id}`, d),
  shareAccess: (id) => api.get(`/admin/shares/${id}/access`),
  logs: (params) => api.get('/admin/logs', { params }),
  config: () => api.get('/admin/config'),
  saveConfig: (d) => api.put('/admin/config', d),
  permissions: (subjectType, subjectId) => api.get('/admin/permissions', { params: { subjectType, subjectId } }),
  folders: (ownerId) => api.get('/admin/folders', { params: { ownerId } }),
  savePermission: (d) => api.post('/admin/permissions', d),
  deletePermission: (id) => api.delete(`/admin/permissions/${id}`),
  effectivePermissions: (userId, folderId) => api.get('/admin/permissions/effective', { params: { userId, folderId } }),
  roleEffective: (roleId, folderId) => api.get('/admin/permissions/role-effective', { params: { roleId, folderId } }),
  logsExportUrl: (params) => `${BASE_PATH}/api/admin/logs/export?token=${localStorage.getItem('liteark_token') || ''}&${new URLSearchParams(params || {}).toString()}`,
}

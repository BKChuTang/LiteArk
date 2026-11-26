import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/s/:token', name: 'share', component: () => import('../views/ShareAccess.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/files',
    children: [
      { path: 'files', name: 'files', component: () => import('../views/Netdisk.vue'), meta: { title: '我的文件' } },
      { path: 'recent', name: 'recent', component: () => import('../views/Recent.vue'), meta: { title: '最近' } },
      { path: 'shares', name: 'shares', component: () => import('../views/MyShares.vue'), meta: { title: '共享' } },
      { path: 'recycle', name: 'recycle', component: () => import('../views/RecycleBin.vue'), meta: { title: '回收站' } },
      { path: 'profile', name: 'profile', component: () => import('../views/Profile.vue'), meta: { title: '我的' } },
    ],
  },
  {
    path: '/admin',
    component: () => import('../views/admin/AdminLayout.vue'),
    redirect: '/admin/overview',
    meta: { admin: true },
    children: [
      { path: 'overview', name: 'admin-overview', component: () => import('../views/admin/Overview.vue'), meta: { title: '系统概览' } },
      { path: 'users', name: 'admin-users', component: () => import('../views/admin/Users.vue'), meta: { title: '用户管理' } },
      { path: 'roles', name: 'admin-roles', component: () => import('../views/admin/Roles.vue'), meta: { title: '角色管理' } },
      { path: 'permissions', name: 'admin-permissions', component: () => import('../views/admin/Permissions.vue'), meta: { title: '文件夹权限' } },
      { path: 'shares', name: 'admin-shares', component: () => import('../views/admin/Shares.vue'), meta: { title: '共享管理' } },
      { path: 'logs', name: 'admin-logs', component: () => import('../views/admin/Logs.vue'), meta: { title: '操作日志' } },
      { path: 'config', name: 'admin-config', component: () => import('../views/admin/Config.vue'), meta: { title: '系统配置' } },
    ],
  },
]

const router = createRouter({
  // 子路径部署必须用 BASE_URL，否则刷新会 404
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to, from, next) => {
  const store = useUserStore()
  if (to.meta.public) return next()
  if (!store.isLogin) return next({ path: '/login', query: { redirect: to.fullPath } })
  if (to.meta.admin && !store.isAdmin) return next({ path: '/files' })
  next()
})

export default router

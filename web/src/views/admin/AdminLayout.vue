<template>
  <el-container class="admin-shell">
    <el-aside v-if="!isMobile" width="210px" class="side">
      <AdminMenu />
    </el-aside>
    <el-container>
      <el-header v-if="isMobile" class="m-top">
        <el-button :icon="Menu" text @click="drawer = true" />
        <BrandLogo :size="22" class="top-logo" />
        <span>管理后台 · {{ $route.meta.title }}</span>
        <span class="spacer" />
        <el-button link @click="back">返回</el-button>
        <el-button link type="danger" @click="logout">退出</el-button>
      </el-header>
      <el-header v-else class="topbar">
        <div class="crumb">管理后台 / {{ $route.meta.title }}</div>
        <div class="badge">
          <el-tag type="danger" size="small" effect="plain">超级管理员</el-tag>
          <el-avatar :size="28" class="avatar">{{ (store.user?.nickname || 'U').charAt(0) }}</el-avatar>
          <span>{{ store.user?.nickname || store.user?.username }}</span>
          <el-button link size="small" @click="back">返回网盘</el-button>
          <el-divider direction="vertical" />
          <el-button link size="small" type="danger" @click="logout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="content"><router-view /></el-main>
    </el-container>
    <el-drawer v-model="drawer" direction="ltr" size="240px" :with-header="false">
      <AdminMenu @navigate="drawer = false" />
    </el-drawer>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted, h } from 'vue'
import { useRouter } from 'vue-router'
import { ElIcon, ElMenu, ElMenuItem } from 'element-plus'
import { DataBoard, User, UserFilled, Share, Document, Setting, Back, Menu, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '../../stores/user'
import BrandLogo from '../../components/BrandLogo.vue'

const store = useUserStore()
const router = useRouter()
const drawer = ref(false)
const isMobile = ref(window.innerWidth < 768)
function resize() { isMobile.value = window.innerWidth < 768 }
onMounted(() => window.addEventListener('resize', resize))
onUnmounted(() => window.removeEventListener('resize', resize))
function back() { router.push('/files') }
function logout() {
  store.logout()
  router.push('/login')
}

const AdminMenu = {
  setup() {
    const rt = useRouter()
    const items = [
      { path: '/admin/overview', label: '系统概览', icon: DataBoard },
      { path: '/admin/users', label: '用户管理', icon: User },
      { path: '/admin/roles', label: '角色管理', icon: UserFilled },
      { path: '/admin/permissions', label: '文件夹权限', icon: Lock },
      { path: '/admin/shares', label: '共享管理', icon: Share },
      { path: '/admin/logs', label: '操作日志', icon: Document },
      { path: '/admin/config', label: '系统配置', icon: Setting },
    ]
    return () => h('div', { class: 'side-inner admin-side' }, [
      h('div', { class: 'side-brand', onClick: () => rt.push('/admin/overview') }, [
        h(BrandLogo, { size: 30 }), h('span', { class: 'name' }, ['轻舟 ', h('small', '管理后台')]),
      ]),
      h(ElMenu, { defaultActive: rt.currentRoute.value.path, router: true, class: 'side-menu' },
        items.map((it) => h(ElMenuItem, { index: it.path }, [h(ElIcon, { size: 18 }, { default: () => h(it.icon) }), h('span', null, it.label)]))
          .concat([h(ElMenuItem, { index: '/files' }, [h(ElIcon, { size: 18 }, { default: () => h(Back) }), h('span', null, '返回网盘')])])
      ),
    ])
  },
}
</script>

<style scoped>
.admin-shell { height: 100vh; }
.side { background: #263445; }
/* .side-inner / .side-brand / .side-menu 由 h() 渲染，scoped 命中不到，样式见 styles/main.css */
.topbar { background: #fff; border-bottom: 1px solid var(--la-border); display: flex; align-items: center; justify-content: space-between; padding: 0 22px; }
.crumb { font-size: 15px; font-weight: 600; }
.badge { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.badge .avatar { background: #0958d9; color: #fff; font-size: 13px; }
.m-top { background: #fff; display: flex; align-items: center; gap: 8px; padding: 0 10px; font-size: 14px; }
.m-top .top-logo { border-radius: 6px; }
.spacer { flex: 1; }
.content { padding: 0; overflow: auto; background: var(--la-bg); }
</style>

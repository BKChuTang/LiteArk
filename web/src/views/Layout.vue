<template>
  <el-container class="app-shell">
    <!-- 桌面侧边栏 ≥1200px -->
    <el-aside v-if="!isTablet && !isMobile" width="220px" class="side">
      <SideMenu />
    </el-aside>

    <el-container>
      <!-- 平板顶部抽屉按钮 768–1199 -->
      <el-header v-if="isTablet" class="topbar tablet-top">
        <el-button :icon="Menu" text @click="drawer = true" />
        <BrandLogo :size="24" class="top-logo" />
        <span class="crumb">{{ $route.meta.title || '轻舟网盘' }}</span>
        <span class="spacer" />
        <UserBadge />
      </el-header>

      <!-- 桌面顶栏 -->
      <el-header v-if="!isMobile && !isTablet" class="topbar">
        <div class="crumb">{{ $route.meta.title || '轻舟网盘' }}</div>
        <UserBadge />
      </el-header>

      <!-- 移动顶栏 <768 -->
      <el-header v-if="isMobile" class="mobile-top">
        <BrandLogo :size="22" class="top-logo" />
        <span class="mobile-title">{{ $route.meta.title || '轻舟' }}</span>
        <span class="spacer" />
        <el-button :icon="User" text @click="$router.push('/profile')" />
      </el-header>

      <el-main class="content"><router-view /></el-main>
    </el-container>

    <!-- 移动底部 Tab -->
    <nav v-if="isMobile" class="tabbar">
      <div v-for="t in tabs" :key="t.path" class="tab-item" :class="{ active: $route.path === t.path }"
        @click="$router.push(t.path)">
        <el-icon :size="21"><component :is="t.icon" /></el-icon>
        <span>{{ t.label }}</span>
      </div>
    </nav>

    <!-- 平板抽屉 -->
    <el-drawer v-model="drawer" direction="ltr" size="240px" :with-header="false" class="side-drawer">
      <SideMenu @navigate="drawer = false" />
    </el-drawer>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted, h } from 'vue'
import { useRouter } from 'vue-router'
import { ElIcon, ElMenu, ElMenuItem, ElTag, ElAvatar, ElButton } from 'element-plus'
import { Folder, Clock, Share, User, Menu, Setting, Delete, Tools } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'
import { authApi } from '../api'
import BrandLogo from '../components/BrandLogo.vue'

const drawer = ref(false)
const isMobile = ref(window.innerWidth < 768)
const isTablet = ref(window.innerWidth >= 768 && window.innerWidth < 1200)
function onResize() {
  isMobile.value = window.innerWidth < 768
  isTablet.value = window.innerWidth >= 768 && window.innerWidth < 1200
}
onMounted(() => {
  window.addEventListener('resize', onResize)
  authApi.me().then((r) => store.updateUser(r.data)).catch(() => {})
})
onUnmounted(() => window.removeEventListener('resize', onResize))

const store = useUserStore()
const router = useRouter()
const tabs = [
  { path: '/files', label: '文件', icon: Folder },
  { path: '/recent', label: '最近', icon: Clock },
  { path: '/shares', label: '共享', icon: Share },
  { path: '/profile', label: '我的', icon: User },
]

function logout() { store.logout(); router.push('/login') }

// 侧边菜单（复用组件）
const SideMenu = {
  setup() {
    const st = useUserStore()
    const rt = useRouter()
    const items = [
      { path: '/files', label: '我的文件', icon: Folder },
      { path: '/recent', label: '最近', icon: Clock },
      { path: '/shares', label: '我的分享', icon: Share },
      { path: '/recycle', label: '回收站', icon: Delete },
      { path: '/profile', label: '个人设置', icon: Setting },
    ]
    return () => h('div', { class: 'side-inner la-side' }, [
      h('div', { class: 'side-brand', onClick: () => rt.push('/files') }, [
        h(BrandLogo, { size: 30 }),
        h('span', { class: 'name' }, ['轻舟 ', h('small', 'LiteArk')]),
      ]),
      h(ElMenu, { defaultActive: rt.currentRoute.value.path, router: true, class: 'side-menu' },
        items.map((it) => h(ElMenuItem, { index: it.path }, [
          h(ElIcon, { size: 18 }, { default: () => h(it.icon) }),
          h('span', null, it.label),
        ])).concat(st.isAdmin ? [h(ElMenuItem, { index: '/admin/overview' }, [
          h(ElIcon, { size: 18 }, { default: () => h(Tools) }), h('span', null, '管理后台'),
        ])] : [])
      ),
    ])
  },
}

const UserBadge = {
  setup() {
    const st = useUserStore()
    const rt = useRouter()
    return () => h('div', { class: 'user-trigger' }, [
      st.isAdmin ? h(ElTag, { type: 'success', size: 'small', effect: 'plain' }, { default: () => '管理员' }) : null,
      h(ElAvatar, { size: 32, class: 'avatar' }, { default: () => (st.user?.nickname || 'U').charAt(0) }),
      h('span', null, st.user?.nickname || st.user?.username || ''),
      h(ElButton, { link: true, size: 'small', onClick: logout }, { default: () => '退出' }),
    ])
  },
}
</script>

<style scoped>
.app-shell { height: 100vh; }
.side { background: #1f4e4d; }
/* .side-inner / .side-brand / .side-menu 由 h() 渲染，scoped 命中不到，样式见 styles/main.css */
.topbar { background: #fff; border-bottom: 1px solid var(--la-border); display: flex; align-items: center; gap: 8px; justify-content: space-between; padding: 0 24px; }
.top-logo { border-radius: 6px; }
.crumb { font-size: 16px; font-weight: 600; }
.spacer { flex: 1; }
.user-trigger { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.avatar { background: var(--la-primary); color: #fff; }
.tablet-top { padding: 0 14px; }
.tablet-top .crumb { font-size: 15px; }
.mobile-top { background: var(--la-primary); color: #fff; display: flex; align-items: center; gap: 8px; padding: 0 12px; height: 52px; }
.mobile-top .mobile-title { font-size: 16px; font-weight: 600; }
.mobile-top :deep(.el-button) { color: #fff; }
.content { padding: 0; overflow: auto; background: var(--la-bg); }
.tabbar {
  position: fixed; bottom: 0; left: 0; right: 0; height: 58px; background: #fff;
  border-top: 1px solid var(--la-border); display: flex; z-index: 100;
  padding-bottom: env(safe-area-inset-bottom);
}
.tab-item { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; color: var(--la-muted); font-size: 11px; min-height: 58px; }
.tab-item.active { color: var(--la-primary); font-weight: 500; }
</style>

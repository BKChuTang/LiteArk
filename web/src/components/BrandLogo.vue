<template>
  <img
    class="brand-logo"
    :src="src"
    :width="size"
    :height="size"
    :alt="alt"
    draggable="false"
    :style="{ borderRadius: radius }"
  />
</template>

<script setup>
/**
 * 品牌标识（帆船）：静态文件 public/logo.svg，深浅底色都能立住。
 * size 为渲染边长（px）；radius 仅用于需要额外圆角裁切的场景，默认由 SVG 自带圆角。
 */
defineProps({
  size: { type: [Number, String], default: 32 },
  alt: { type: String, default: '轻舟 LiteArk' },
  radius: { type: String, default: '' },
})

// 与 vite 的 base 保持一致，避免部署到子路径后 404
const src = `${import.meta.env.BASE_URL}logo.svg`
</script>

<style scoped>
.brand-logo {
  display: block;
  flex: 0 0 auto;
  user-select: none;
  -webkit-user-drag: none;
}
</style>

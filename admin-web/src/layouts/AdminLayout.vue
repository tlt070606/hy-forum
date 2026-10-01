<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">Hy论坛 · 管理后台</div>
      <el-menu :default-active="route.path" router>
        <el-menu-item index="/posts">
          <el-icon><Document /></el-icon><span>帖子审核</span>
        </el-menu-item>
        <el-menu-item index="/comments">
          <el-icon><ChatDotRound /></el-icon><span>评论审核</span>
        </el-menu-item>
        <el-menu-item index="/reports">
          <el-icon><Warning /></el-icon><span>举报处理</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon><span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/boards">
          <el-icon><Grid /></el-icon><span>版块管理</span>
        </el-menu-item>
        <el-menu-item index="/words">
          <el-icon><Lock /></el-icon><span>敏感词</span>
        </el-menu-item>
        <el-menu-item index="/invites">
          <el-icon><Ticket /></el-icon><span>邀请码</span>
        </el-menu-item>
        <el-menu-item index="/config">
          <el-icon><Setting /></el-icon><span>系统配置</span>
        </el-menu-item>
        <el-menu-item index="/logs">
          <el-icon><List /></el-icon><span>操作日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="spacer" />
        <span class="who">{{ profile?.nickname ?? '管理员' }}</span>
        <el-button link type="danger" @click="onLogout">退出登录</el-button>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  ChatDotRound, Document, Grid, List, Lock, Setting, Ticket, User, Warning,
} from '@element-plus/icons-vue'
import { getProfile, clearAuth } from '../stores/auth'
import { adminLogout } from '../api/admin'

const route = useRoute()
const router = useRouter()
const profile = getProfile()

async function onLogout(): Promise<void> {
  await ElMessageBox.confirm('确定退出登录？', '退出', { type: 'warning' })
  try {
    await adminLogout()
  } catch {
    // 后端注销失败（网络/已过期）不阻塞本地登出：本地态清掉即达成目的
  }
  clearAuth()
  await router.replace({ name: 'login' })
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { border-right: 1px solid var(--el-border-color-light); }
.brand {
  font-weight: 700; padding: 18px 16px; color: var(--el-color-primary);
}
.header {
  display: flex; align-items: center; gap: 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.spacer { flex: 1; }
.who { color: var(--el-text-color-secondary); }
.main { background: var(--el-fill-color-lighter); }
</style>

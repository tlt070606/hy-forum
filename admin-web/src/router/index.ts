import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../stores/auth'

/**
 * Hash 路由（#/…）：Nginx 只需静态直出 /admin/ 的 index.html，
 * 不需要任何 try_files 回退配置 —— 部署面最小化（deployment.md §4 会补一节）。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
    },
    {
      path: '/',
      component: () => import('../layouts/AdminLayout.vue'),
      children: [
        { path: '', redirect: '/posts' },
        { path: 'posts', name: 'posts', component: () => import('../views/PostAuditView.vue') },
        { path: 'comments', name: 'comments', component: () => import('../views/CommentAuditView.vue') },
        { path: 'reports', name: 'reports', component: () => import('../views/ReportView.vue') },
        { path: 'users', name: 'users', component: () => import('../views/UserView.vue') },
        { path: 'boards', name: 'boards', component: () => import('../views/BoardView.vue') },
        { path: 'words', name: 'words', component: () => import('../views/SensitiveWordView.vue') },
        { path: 'invites', name: 'invites', component: () => import('../views/InviteView.vue') },
        { path: 'config', name: 'config', component: () => import('../views/ConfigView.vue') },
        { path: 'logs', name: 'logs', component: () => import('../views/LogView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/** 路由守卫：无后台 token 一律去登录页（任务书 §4 第 2 条）。 */
router.beforeEach((to) => {
  if (to.name !== 'login' && !getToken()) {
    return { name: 'login' }
  }
  if (to.name === 'login' && getToken()) {
    return { path: '/' }
  }
  return true
})

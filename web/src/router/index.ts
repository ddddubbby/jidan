import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../ui/PlaceholderPage.vue'

// meta.nav 是侧栏里高亮的那一项；各页由对应任务（P4、P5）替换掉空页面
const pages: [string, string][] = [
  ['orders', '采购入库单'],
  ['prices', '每日基准价'],
  ['rules', '定价规则'],
  ['basics', '规格与皮重'],
  ['archive', '好生意档案'],
  ['accounts', '磅秤员账号'],
  ['settings', '系统设置'],
]

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/orders' },
    ...pages.map(([k, title]) => ({ path: '/' + k, component: PlaceholderPage, meta: { nav: k, title } })),
  ],
})

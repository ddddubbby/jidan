import type { Component } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '../stores/session'
import PlaceholderPage from '../ui/PlaceholderPage.vue'
import LoginPage from '../features/login/LoginPage.vue'
import ArchivePage from '../features/archive/ArchivePage.vue'
import AccountsPage from '../features/accounts/AccountsPage.vue'
import SettingsPage from '../features/settings/SettingsPage.vue'
import OptionsPage from '../features/options/OptionsPage.vue'
import PricesPage from '../features/prices/PricesPage.vue'
import BasicsPage from '../features/basics/BasicsPage.vue'
import RulesPage from '../features/rules/RulesPage.vue'
import OrdersPage from '../features/orders/OrdersPage.vue'

// meta.nav 是侧栏里高亮的那一项；各页由对应任务（P4、P5）替换掉空页面
const pages: [string, string][] = [
  ['orders', '采购入库单'],
  ['prices', '每日基准价'],
  ['basics', '规格与皮重'],
  ['archive', '好生意档案'],
  ['accounts', '磅秤员账号'],
  ['settings', '系统设置'],
  ['options', '高级选项'],
]

// 已经做出来的页面
const done: Record<string, Component> = {
  archive: ArchivePage,
  accounts: AccountsPage,
  settings: SettingsPage,
  options: OptionsPage,
  prices: PricesPage,
  basics: BasicsPage,
  orders: OrdersPage,
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/orders' },
    { path: '/login', component: LoginPage, meta: { public: true } },
    // 单据详情（P5-2）与新建单（P5-4）做出来之前先放空页面；new 要排在 :id 前面
    { path: '/orders/new', component: PlaceholderPage, meta: { nav: 'orders', title: '新建入库单' } },
    { path: '/orders/:id(\\d+)', component: PlaceholderPage, meta: { nav: 'orders', title: '单据详情' } },
    { path: '/rules', component: RulesPage, meta: { nav: 'rules', title: '定价规则' } },
    ...pages.map(([k, title]) => ({ path: '/' + k, component: done[k] ?? PlaceholderPage, meta: { nav: k, title } })),
    // 公共组件的走查页，只在开发服务器里有（build 时整段剔除）
    ...(import.meta.env.DEV
      ? [{ path: '/_ui', component: () => import('../ui/Gallery.vue'), meta: { nav: '', title: '组件走查' } }]
      : []),
  ],
})

// 未登录访问任何页都跳登录页，登录后回到原来要去的页
router.beforeEach((to) => {
  const session = useSession()
  if (to.meta.public) return session.loggedIn ? '/' : true
  if (!session.loggedIn) return { path: '/login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  return true
})

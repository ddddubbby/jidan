import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '../stores/session'
import PlaceholderPage from '../ui/PlaceholderPage.vue'
import LoginPage from '../features/login/LoginPage.vue'
import RulesPage from '../features/rules/RulesPage.vue'

// meta.nav 是侧栏里高亮的那一项；各页由对应任务（P4、P5）替换掉空页面
const pages: [string, string][] = [
  ['orders', '采购入库单'],
  ['prices', '每日基准价'],
  ['basics', '规格与皮重'],
  ['archive', '好生意档案'],
  ['accounts', '磅秤员账号'],
  ['settings', '系统设置'],
]

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/orders' },
    { path: '/login', component: LoginPage, meta: { public: true } },
    { path: '/rules', component: RulesPage, meta: { nav: 'rules', title: '定价规则' } },
    ...pages.map(([k, title]) => ({ path: '/' + k, component: PlaceholderPage, meta: { nav: k, title } })),
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

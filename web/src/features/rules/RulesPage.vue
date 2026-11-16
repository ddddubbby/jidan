<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { errorText } from '../../api/http'
import { kindText, listRules, listSpecs, searchArchive, type ArchiveRow, type Kind, type RuleView, type Spec } from '../../api/rules'
import RuleEditDrawer from './RuleEditDrawer.vue'

// 定价规则列表（原型 pageRules，WEB-05）：按属性、商品、规格、关键词筛选，点一行打开编辑抽屉。
const kind = ref<'all' | Kind>('all')
const productId = ref<'all' | number>('all')
const specId = ref<'all' | number>('all')
const q = ref('')

const rules = ref<RuleView[]>([])
const products = ref<ArchiveRow[]>([])
const specs = ref<Spec[]>([])
const loaded = ref(false)
const error = ref('')

const drawer = ref(false)
const editing = ref<RuleView | null>(null)

let seq = 0
async function load() {
  const my = ++seq
  try {
    const list = await listRules({
      kind: kind.value === 'all' ? undefined : kind.value,
      productId: productId.value === 'all' ? undefined : productId.value,
      specId: specId.value === 'all' ? undefined : specId.value,
      q: q.value.trim() || undefined,
    })
    if (my !== seq) return
    rules.value = list
    error.value = ''
  } catch (e) {
    if (my === seq) error.value = errorText(e)
  } finally {
    if (my === seq) loaded.value = true
  }
}

// 关键词输入停一下再查；其余筛选立即查
let timer: ReturnType<typeof setTimeout> | undefined
watch(q, () => { clearTimeout(timer); timer = setTimeout(load, 250) })
watch([kind, productId, specId], load)

onMounted(async () => {
  load()
  // 商品、规格取不到不影响看列表（筛选条只是少几个按钮）
  searchArchive('products').then((r) => (products.value = r)).catch(() => {})
  listSpecs().then((r) => (specs.value = r)).catch(() => {})
})

function edit(r: RuleView | null) {
  editing.value = r
  drawer.value = true
}
const bindText = (r: RuleView) => r.binds.map((b) => b.name).join('、')
const bindTitle = (r: RuleView) => r.binds.map((b) => b.name + (b.tail4 ? `（尾号 ${b.tail4}）` : '')).join('、')
</script>

<template>
  <div class="top"><div><h1>定价规则</h1></div><div class="acts"><button class="btn dark" @click="edit(null)">新建规则</button></div></div>

  <div class="bar">
    <div class="seg sm">
      <button :aria-pressed="kind === 'all'" @click="kind = 'all'">全部</button>
      <button :aria-pressed="kind === 'BUY'" @click="kind = 'BUY'">收购</button>
      <button :aria-pressed="kind === 'SELL'" @click="kind = 'SELL'">发货</button>
    </div>
    <div class="seg sm">
      <button :aria-pressed="productId === 'all'" @click="productId = 'all'">全部商品</button>
      <button v-for="p in products" :key="p.id" :aria-pressed="productId === p.id" @click="productId = p.id">{{ p.name }}</button>
    </div>
    <div class="seg sm">
      <button :aria-pressed="specId === 'all'" @click="specId = 'all'">全部规格</button>
      <button v-for="s in specs" :key="s.id" :aria-pressed="specId === s.id" @click="specId = s.id">{{ s.name }}</button>
    </div>
    <input v-model="q" class="search" placeholder="规则、农户或客户" aria-label="搜索规则" style="width: 240px" />
  </div>

  <p v-if="error" class="inline-err" role="alert">{{ error }}<button class="btn sm" style="margin-left: 10px" @click="load()">重试</button></p>

  <div v-if="rules.length" class="twrap">
    <table class="t">
      <thead><tr><th>规则名称</th><th>属性</th><th>商品规格</th><th class="r">每 kg 尾差</th><th>绑定</th><th>状态</th></tr></thead>
      <tbody>
        <tr v-for="r in rules" :key="r.id" class="go" tabindex="0" @click="edit(r)" @keydown.enter="edit(r)">
          <td class="name" :title="r.name"><span class="nm">{{ r.name }}</span></td>
          <td><span class="pill plain">{{ kindText(r.kind) }}</span></td>
          <td class="nb">{{ r.productName }} {{ r.specName }}</td>
          <td class="r">{{ r.coef }} 元</td>
          <td class="fill">
            <span v-if="r.binds.length" class="clip" :title="bindTitle(r)">{{ r.binds.length > 2 ? `${r.bindCount} 户：` : '' }}{{ bindText(r) }}</span>
            <span v-else class="tag">未绑定</span>
          </td>
          <td class="nb"><span v-if="!r.enabled" class="tag">已停用</span><span v-else class="muted">启用</span></td>
        </tr>
      </tbody>
    </table>
  </div>
  <div v-else-if="loaded && !error" class="card empty">没有匹配的规则</div>

  <RuleEditDrawer v-model:open="drawer" :rule="editing" :products="products" :specs="specs" @changed="load()" />
</template>

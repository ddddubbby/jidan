<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { getTares } from '../../api/basics'
import { errorText } from '../../api/http'
import { conflictsOf, createRule, updateRule, type ArchiveRow, type Conflict, type Kind, type RuleView, type Spec } from '../../api/rules'
import type { TareBox } from '../../api/types'
import Drawer from '../../ui/Drawer.vue'
import { toast } from '../../ui/toast'

// 新建 / 编辑规则抽屉（原型 drawerRuleEdit，WEB-05，按附录 E R-1 行改版）。
// 规则不绑农户或客户，建单时按名称选，所以名称不能重复；一条规则打包了商品、规格、包装箱（皮重）、每箱损耗与每斤尾差。
// 重名时后端返回 RULE_CONFLICT，就地标在名称下面，保存按钮变成“改规则名称”。
const props = defineProps<{ rule: RuleView | null; products: ArchiveRow[]; specs: Spec[] }>()
const open = defineModel<boolean>('open', { default: false })
const emit = defineEmits<{ changed: [] }>()

interface Form {
  name: string; enabled: boolean; kind: Kind; productId: number | null; specId: number | null
  tareBoxId: number | null; lossJin: string; coef: string
}
const blank = (): Form => ({ name: '', enabled: true, kind: 'BUY', productId: null, specId: null, tareBoxId: null, lossJin: '0', coef: '0.5' })
const form = ref<Form>(blank())
const saving = ref(false)
const saveError = ref('')
const conflict = ref<Conflict | null>(null)
const conflictName = ref('') // 冲突时的名称；名称一改，冲突提示就撤掉
const tares = ref<TareBox[]>([])

const isNew = computed(() => props.rule == null)

watch(open, (o) => {
  if (!o) return
  const r = props.rule
  form.value = r
    ? { name: r.name, enabled: r.enabled, kind: r.kind, productId: r.productId, specId: r.specId, tareBoxId: r.tareBoxId, lossJin: String(r.lossJin ?? 0), coef: String(r.coef) }
    : blank()
  saveError.value = ''
  conflict.value = null
  getTares().then((l) => (tares.value = l)).catch(() => (tares.value = []))
  if (!r) nextTick(() => nameInput.value?.focus())
})
watch(() => form.value.name, (n) => { if (conflict.value && n.trim() !== conflictName.value) conflict.value = null })

const nameInput = ref<HTMLInputElement>()
const coefInput = ref<HTMLInputElement>()
const lossInput = ref<HTMLInputElement>()

// ---- 每箱损耗：正数加、负数减，步进 0.5 斤 ----
const lossOk = computed(() => /^-?\d{1,3}(\.\d{1,2})?$/.test(form.value.lossJin.trim()))
function stepLoss(d: number) {
  const cur = Number(form.value.lossJin)
  form.value.lossJin = String(Math.round(((isNaN(cur) ? 0 : cur) + d) * 100) / 100)
}

// ---- 保存 ----
const coefOk = computed(() => /^\d{1,4}(\.\d{1,4})?$/.test(form.value.coef.trim()))
const nameOk = computed(() => form.value.name.trim() !== '')

// 保存按钮就是下一步：缺什么写什么，点了直达
const next = computed<{ text: string; cls: string; go: () => void } | null>(() => {
  const f = form.value
  if (!nameOk.value) return { text: '填写规则名称', cls: 'todo', go: () => nameInput.value?.focus() }
  if (conflict.value) return { text: '改规则名称', cls: 'fix', go: () => nameInput.value?.focus() }
  if (f.productId == null) return { text: '选择商品', cls: 'todo', go: () => document.getElementById('rule-products')?.querySelector('button')?.focus() }
  if (f.specId == null) return { text: '选择规格', cls: 'todo', go: () => document.getElementById('rule-specs')?.querySelector('button')?.focus() }
  if (f.tareBoxId == null) return { text: '选择包装箱', cls: 'todo', go: () => document.getElementById('rule-tares')?.querySelector('button')?.focus() }
  if (!lossOk.value) return { text: '填写每箱损耗', cls: 'todo', go: () => lossInput.value?.focus() }
  if (!coefOk.value) return { text: '填写每斤尾差', cls: 'todo', go: () => coefInput.value?.focus() }
  return null
})

async function save() {
  const f = form.value
  if (next.value || saving.value || f.productId == null || f.specId == null || f.tareBoxId == null) return
  saving.value = true
  saveError.value = ''
  const body = {
    name: f.name.trim(), kind: f.kind, productId: f.productId, specId: f.specId, tareBoxId: f.tareBoxId,
    lossJin: Number(f.lossJin), coef: Number(f.coef), enabled: f.enabled,
  }
  try {
    if (props.rule) await updateRule(props.rule.id, body)
    else await createRule(body)
    open.value = false
    toast('规则已保存')
    emit('changed')
  } catch (e) {
    const c = conflictsOf(e)
    if (c) {
      conflict.value = c[0] ?? { ruleId: 0, ruleName: f.name.trim() }
      conflictName.value = f.name.trim()
      nameInput.value?.focus()
    } else saveError.value = errorText(e)
  } finally {
    saving.value = false
  }
}

const enabledSpecs = computed(() => props.specs.filter((s) => s.enabled || s.id === form.value.specId))
const enabledTares = computed(() => tares.value.filter((t) => t.enabled || t.id === form.value.tareBoxId))
</script>

<template>
  <Drawer v-model:open="open" :title="isNew ? '新建规则' : '编辑规则'" :sub="rule ? rule.name : undefined">
    <div class="formgrid">
      <label class="field"><span>规则名称</span>
        <input ref="nameInput" v-model="form.name" class="in" :class="{ bad: conflict }" autocomplete="off" maxlength="100" />
      </label>
      <div v-if="conflict" class="conflict" role="alert" style="margin-top: -8px">已有同名规则「{{ conflict.ruleName }}」，建单时按名称选，换个名称</div>

      <div class="setrow">
        <button class="switch" role="switch" :aria-checked="form.enabled" aria-label="启用这条规则" @click="form.enabled = !form.enabled"></button>
        <span>{{ form.enabled ? '启用' : '已停用' }}</span>
      </div>

      <div class="field"><span>属性</span>
        <div class="seg">
          <button :aria-pressed="form.kind === 'BUY'" @click="form.kind = 'BUY'">收购规则</button>
          <button :aria-pressed="form.kind === 'SELL'" @click="form.kind = 'SELL'">发货规则</button>
        </div>
      </div>

      <div class="field"><span>商品</span>
        <div id="rule-products" class="seg">
          <button v-for="p in products" :key="p.id" :aria-pressed="form.productId === p.id" @click="form.productId = p.id">{{ p.name }}</button>
        </div>
        <RouterLink v-if="!products.length" class="btn todo" to="/archive" @click="open = false">好生意档案是空的，去同步</RouterLink>
      </div>

      <div class="field"><span>规格</span>
        <div id="rule-specs" class="seg">
          <button v-for="s in enabledSpecs" :key="s.id" :aria-pressed="form.specId === s.id" @click="form.specId = s.id">{{ s.name }}</button>
        </div>
        <RouterLink v-if="!enabledSpecs.length" class="btn todo" to="/basics" @click="open = false">还没有规格，去添加</RouterLink>
      </div>

      <div class="field"><span>包装箱（皮重）</span>
        <div id="rule-tares" class="seg">
          <button v-for="t in enabledTares" :key="t.id" :aria-pressed="form.tareBoxId === t.id" @click="form.tareBoxId = t.id">{{ t.name }} <span class="num">{{ t.tareJin }} 斤</span></button>
        </div>
        <RouterLink v-if="!enabledTares.length" class="btn todo" to="/basics" @click="open = false">还没有包装箱，去添加</RouterLink>
      </div>

      <div class="field"><span>每箱损耗（斤）</span>
        <div class="stepper">
          <button aria-label="减少损耗" @click="stepLoss(-0.5)">−</button>
          <input ref="lossInput" v-model="form.lossJin" class="in num" :class="{ bad: form.lossJin !== '' && !lossOk }" inputmode="decimal" autocomplete="off" aria-label="每箱损耗" />
          <button aria-label="增加损耗" @click="stepLoss(0.5)">+</button>
        </div>
      </div>

      <label class="field"><span>每斤尾差（元）</span>
        <input ref="coefInput" v-model="form.coef" class="in numin" :class="{ bad: form.coef !== '' && !coefOk }" inputmode="decimal" autocomplete="off" style="width: 160px" />
      </label>

      <p v-if="saveError" class="inline-err" role="alert">{{ saveError }}</p>
    </div>

    <template #foot>
      <button v-if="next" class="btn" :class="next.cls" @click="next.go()">{{ next.text }}</button>
      <button v-else class="btn primary" :disabled="saving" @click="save()">保存</button>
    </template>
  </Drawer>
</template>

<style>
/* 规则抽屉（原型“规则抽屉”一段） */
.formgrid { display: grid; gap: 16px; }
.setrow { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.conflict { padding: 10px 12px; border-radius: 10px; background: var(--err-bg); color: var(--err-ink); font-size: 13px; font-weight: 600; }
</style>

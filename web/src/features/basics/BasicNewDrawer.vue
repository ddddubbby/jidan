<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { createSpec, createTare } from '../../api/basics'
import { errorText } from '../../api/http'
import Drawer from '../../ui/Drawer.vue'
import { toast } from '../../ui/toast'

// 新增规格 / 新增包装箱（原型 drawerBasicNew）。主按钮写明还缺哪一项，点了直达那一格（v7 4.4、4.5-1）。
const props = defineProps<{ kind: 'spec' | 'tare' }>()
const emit = defineEmits<{ created: [] }>()
const open = defineModel<boolean>('open', { default: false })

const isSpec = computed(() => props.kind === 'spec')
const name = ref('')
const count = ref('')
const val = ref('')
const err = ref('')
const busy = ref(false)
const nameEl = ref<HTMLInputElement>()
const countEl = ref<HTMLInputElement>()
const valEl = ref<HTMLInputElement>()

const okVal = (s: string) => /^\d{1,6}(\.\d{1,2})?$/.test(s.trim()) && Number(s) > 0

// 缺哪项就写哪项；都齐了才是“新增”
const todo = computed(() => {
  if (!name.value.trim()) return { text: '填写名称', el: nameEl }
  if (isSpec.value && !/^[1-9]\d*$/.test(count.value.trim())) return { text: '填写每箱枚数', el: countEl }
  if (!okVal(val.value)) return { text: `填写${isSpec.value ? '标准净含量' : '皮重'}`, el: valEl }
  return null
})

// 每次打开都是一张空表单，光标在名称上
watch(open, async (o) => {
  if (!o) return
  name.value = count.value = val.value = err.value = ''
  await nextTick()
  nameEl.value?.focus()
})

async function submit() {
  if (todo.value) return todo.value.el.value?.focus()
  busy.value = true
  err.value = ''
  try {
    if (isSpec.value) await createSpec({ name: name.value.trim(), eggCount: Number(count.value), stdNetJin: Number(val.value) })
    else await createTare({ name: name.value.trim(), tareJin: Number(val.value) })
    toast(`已新增${isSpec.value ? '规格' : '包装箱'}：${name.value.trim()}`)
    open.value = false
    emit('created')
  } catch (e) {
    err.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Drawer v-model:open="open" :title="isSpec ? '新增规格' : '新增包装箱'">
    <form class="formgrid" @submit.prevent="submit">
      <label class="field">
        <span>{{ isSpec ? '规格名称' : '包装箱名称' }}</span>
        <input ref="nameEl" v-model="name" class="in" :placeholder="isSpec ? '如 300枚/箱' : '如 4号包装箱'" autocomplete="off" autofocus />
      </label>
      <label v-if="isSpec" class="field">
        <span>每箱枚数</span>
        <input ref="countEl" v-model="count" class="in numin" inputmode="numeric" style="width: 160px" autocomplete="off" />
      </label>
      <label class="field">
        <span>{{ isSpec ? '标准净含量（斤/箱）' : '皮重（斤）' }}</span>
        <input ref="valEl" v-model="val" class="in numin" inputmode="decimal" style="width: 160px" autocomplete="off" />
      </label>
      <div v-if="err" class="inline-err">{{ err }}</div>
      <button type="submit" hidden></button>
    </form>
    <template #foot>
      <button v-if="todo" class="btn todo" @click="submit">{{ todo.text }}</button>
      <button v-else class="btn primary" :disabled="busy" @click="submit">新增</button>
    </template>
  </Drawer>
</template>

<style>
.formgrid { display: grid; gap: 16px; }
</style>

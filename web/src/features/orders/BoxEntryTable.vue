<script setup lang="ts">
import { nextTick, ref } from 'vue'

// 逐箱净重录入表（WEB-06，原型 boxTable / addBoxes）：一箱一回车；也可粘贴一整列（Excel 复制来的多行）。
// 箱序号按录入顺序自动编号，删一箱后后面的往前排。净重是 kg，最多两位小数。
const boxes = defineModel<number[]>({ required: true })

const addEl = ref<HTMLInputElement | null>(null)
// 正在改的那一行的输入框（v-for 里的 ref 是数组，用函数形式只记最新一个）
let editEl: HTMLInputElement | null = null
const setEdit = (el: unknown) => { if (el) editEl = el as HTMLInputElement }
const pasteEl = ref<HTMLTextAreaElement | null>(null)
const add = ref('')
const addErr = ref('')
const editing = ref(-1)
const editVal = ref('')
const editBad = ref(false)
const paste = ref(false)
const pasteText = ref('')

const validKg = (v: string) => /^\d{1,3}(\.\d{1,2})?$/.test(v.trim()) && parseFloat(v) > 0
const kg = (n: number) => String(Math.round(n * 100) / 100)

/** 一次可以给多箱：按换行、空格、逗号拆开；有一个不合法就一箱都不加，并指出是哪个 */
function addBoxes(text: string): boolean {
  const parts = text.split(/[\s,，、;；]+/).filter(Boolean)
  if (!parts.length) return false
  const bad = parts.find((p) => !validKg(p))
  if (bad) {
    addErr.value = `“${bad}”不是有效的净重`
    return false
  }
  boxes.value = [...boxes.value, ...parts.map((p) => Math.round(parseFloat(p) * 100) / 100)]
  addErr.value = ''
  return true
}

function onAddEnter() {
  if (addBoxes(add.value)) add.value = ''
  addEl.value?.scrollIntoView({ block: 'nearest' })
}

// 粘贴：输入框里粘进多个数时一次加完；单个数照常粘进输入框，回车再加
function onAddPaste(e: ClipboardEvent) {
  const txt = e.clipboardData?.getData('text') ?? ''
  if (!/[\s,，]/.test(txt.trim())) return
  e.preventDefault()
  addBoxes(txt)
  add.value = ''
}

function togglePaste() {
  paste.value = !paste.value
  if (paste.value) nextTick(() => pasteEl.value?.focus())
}
function onPasteBox(e: ClipboardEvent) {
  e.preventDefault()
  const txt = e.clipboardData?.getData('text') ?? ''
  commitPaste(txt)
}
function commitPaste(text: string) {
  if (addBoxes(text)) {
    pasteText.value = ''
    paste.value = false
    nextTick(() => addEl.value?.focus())
  } else if (addErr.value) {
    pasteText.value = text
  }
}

function startEdit(i: number) {
  editing.value = i
  editVal.value = kg(boxes.value[i])
  editBad.value = false
  nextTick(() => {
    editEl?.focus()
    editEl?.select()
  })
}
function saveEdit() {
  if (!validKg(editVal.value)) {
    editBad.value = true
    editEl?.focus()
    return
  }
  const next = boxes.value.slice()
  next[editing.value] = Math.round(parseFloat(editVal.value) * 100) / 100
  boxes.value = next
  editing.value = -1
  addEl.value?.focus()
}
const cancelEdit = () => (editing.value = -1)

function remove(i: number) {
  boxes.value = boxes.value.filter((_, j) => j !== i)
  if (editing.value >= 0) editing.value = -1
}

defineExpose({ focus: () => addEl.value?.focus() })
</script>

<template>
  <table class="t boxes">
    <thead>
      <tr><th class="seqc">箱序号</th><th>净重</th><th class="r"></th></tr>
    </thead>
    <tbody>
      <tr v-for="(n, i) in boxes" :key="i">
        <td class="num seqc">第 {{ i + 1 }} 箱</td>
        <td>
          <input
            v-if="editing === i"
            :ref="setEdit"
            v-model="editVal"
            class="in numin edin"
            :class="{ bad: editBad }"
            inputmode="decimal"
            :aria-label="`第 ${i + 1} 箱净重`"
            @keydown.enter.prevent="saveEdit"
            @keydown.esc.prevent="cancelEdit"
            @input="editBad = false"
          />
          <button v-else class="netbtn" :aria-label="`改第 ${i + 1} 箱净重`" @click="startEdit(i)">{{ kg(n) }} kg</button>
        </td>
        <td class="r nb">
          <button v-if="editing !== i" class="btn sm ghost del" :aria-label="`删除第 ${i + 1} 箱`" @click="remove(i)">删除</button>
        </td>
      </tr>
      <tr class="addrow">
        <td class="num muted seqc">第 {{ boxes.length + 1 }} 箱</td>
        <td colspan="2">
          <span class="setrow" style="gap: 8px">
            <input
              ref="addEl"
              v-model="add"
              class="in numin"
              :class="{ bad: !!addErr }"
              inputmode="decimal"
              placeholder="净重，回车添加"
              autocomplete="off"
              :aria-label="`第 ${boxes.length + 1} 箱净重`"
              @keydown.enter.prevent="onAddEnter"
              @paste="onAddPaste"
              @input="addErr = ''"
            />
            <button class="btn sm ghost" :aria-expanded="paste" @click="togglePaste">粘贴一列</button>
            <span class="inline-err" role="alert">{{ addErr }}</span>
          </span>
          <template v-if="paste">
            <textarea
              ref="pasteEl"
              v-model="pasteText"
              class="pastebox"
              aria-label="粘贴一列净重"
              placeholder="46.5&#10;45.8&#10;46"
              @paste="onPasteBox"
            ></textarea>
            <button class="btn sm" style="margin-top: 8px" :disabled="!pasteText.trim()" @click="commitPaste(pasteText)">加入这些箱</button>
          </template>
        </td>
      </tr>
    </tbody>
  </table>
</template>

<style>
.t.boxes td { padding: 4px 12px; height: 46px; }
.boxes .seqc { width: 96px; white-space: nowrap; }
.netbtn { padding: 4px 8px; margin-left: -8px; border: 0; border-radius: 8px; background: none; font-weight: 700; font-variant-numeric: tabular-nums; text-decoration: underline dashed #b3bab5; text-decoration-thickness: 1px; text-underline-offset: 5px; }
.netbtn:hover { background: var(--sunken); }
.boxes .edin { height: 34px; width: 110px; border-radius: 9px; }
.boxes .del { opacity: 0; color: var(--ink-2); }
.boxes tr:hover .del, .boxes tr:focus-within .del { opacity: 1; }
.addrow td { background: #fafbf9; }
.boxes .addrow input.in { height: 36px; width: 200px; }
.pastebox { display: block; width: 100%; min-height: 96px; margin-top: 8px; padding: 10px 12px; border: 1.5px dashed var(--edge); border-radius: 12px; background: var(--surface); resize: vertical; font-variant-numeric: tabular-nums; }
</style>

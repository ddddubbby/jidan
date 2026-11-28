<script setup lang="ts">
import { ref } from 'vue'
import ConfirmDialog from './ConfirmDialog.vue'
import DateField from './DateField.vue'
import Drawer from './Drawer.vue'
import InlineEdit from './InlineEdit.vue'
import { today } from './date'
import { toast } from './toast'

// 公共组件的走查页：只在开发服务器的 /_ui 有，build 里没有。后面的页面任务照这里的用法接。
const drawer = ref(false)
const approve = ref(false)
const pw = ref(false)
const date = ref(today())
const w0 = ref('45')
const draft = ref('')

function saveW0(close: () => void) {
  if (!(Number(draft.value) > 0)) return
  w0.value = draft.value
  toast('已保存')
  close()
}
</script>

<template>
  <div class="top"><div><h1>组件走查</h1><p>开发服务器专用，不进正式构建</p></div></div>

  <div class="card" style="padding: 20px; display: grid; gap: 22px">
    <section>
      <h3>就地编辑</h3>
      <p>
        标准净含量：
        <InlineEdit label="标准净含量" @update:open="(o: boolean) => o && (draft = w0)">
          <b>{{ w0 }}斤</b>
          <template #editor="{ close }">
            <input v-model="draft" class="in numin" aria-label="标准净含量" @keydown.enter.prevent="saveW0(close)" />
          </template>
        </InlineEdit>
      </p>
      <p>已锁定：<InlineEdit label="标准净含量" locked><b>45斤</b></InlineEdit></p>
    </section>

    <section>
      <h3>日期框</h3>
      <p><DateField v-model="date" /> <span class="muted num">{{ date }}</span></p>
    </section>

    <section>
      <h3>抽屉、确认框、提示条</h3>
      <div class="bar">
        <button class="btn" @click="drawer = true">打开抽屉</button>
        <button class="btn dark" @click="approve = true">审核确认框</button>
        <button class="btn" @click="pw = true">只有“完成”的提示框</button>
        <button class="btn" @click="toast('已保存')">提示条</button>
      </div>
    </section>
  </div>

  <Drawer v-model:open="drawer" title="示例抽屉" sub="标题下的说明">
    <p v-for="i in 40" :key="i">第 {{ i }} 行内容，用来看正文滚动时页头页脚不动。</p>
    <template #foot><button class="btn" @click="drawer = false">取消</button><button class="btn primary" @click="drawer = false; toast('已保存')">保存</button></template>
  </Drawer>

  <ConfirmDialog v-model:open="approve" title="审核并回传好生意？" confirm-text="审核并回传" @confirm="toast('已确认')">
    <div class="amt">1,840.00<small> 元</small></div>
    <p class="muted" style="margin: 14px 0 0; font-size: 13px">回传到「示例账套」，之后单据锁定</p>
  </ConfirmDialog>

  <ConfirmDialog v-model:open="pw" title="已新增张师傅" confirm-text="完成" hide-cancel>
    <p class="muted" style="margin: 6px 0 0">只显示这一次</p>
  </ConfirmDialog>
</template>

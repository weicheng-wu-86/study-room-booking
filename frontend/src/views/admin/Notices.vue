<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import api from '../../api'
import { confirm } from '../../ui'
import { mdhm } from '../../utils'

const list = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const editingId = ref(null)
const form = reactive({ title: '', content: '', pinned: false })
const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入内容', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    list.value = await api.notices()
  } finally {
    loading.value = false
  }
}

function open(row) {
  editingId.value = row?.id ?? null
  Object.assign(form, { title: row?.title ?? '', content: row?.content ?? '', pinned: row?.pinned ?? false })
  visible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editingId.value) {
      await api.admin.updateNotice(editingId.value, { ...form })
    } else {
      await api.admin.createNotice({ ...form })
    }
    ElMessage.success('已保存')
    visible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  if (!(await confirm(`确定删除公告“${row.title}”吗？`, '删除公告'))) return
  await api.admin.deleteNotice(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="head">
      <div>
        <h1 class="page-title">公告管理</h1>
        <p class="page-desc">发布的公告显示在学生端首页，置顶公告排在最前并默认展开。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="open()">发布公告</el-button>
    </div>

    <div class="card">
      <el-table v-loading="loading" :data="list">
        <el-table-column label="标题" min-width="240">
          <template #default="{ row }">
            <el-tag v-if="row.pinned" size="small" type="warning" effect="plain" class="pin">置顶</el-tag>{{ row.title }}
          </template>
        </el-table-column>
        <el-table-column label="内容" prop="content" min-width="320" show-overflow-tooltip />
        <el-table-column label="发布时间" width="130">
          <template #default="{ row }"><span class="num">{{ mdhm(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="visible" :title="editingId ? '编辑公告' : '发布公告'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="64px">
        <el-form-item label="标题" prop="title"><el-input v-model="form.title" maxlength="100" show-word-limit /></el-form-item>
        <el-form-item label="内容" prop="content"><el-input v-model="form.content" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="置顶"><el-switch v-model="form.pinned" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.pin {
  margin-right: 8px;
}
</style>

// 界面交互的小工具
import { ElMessageBox } from 'element-plus'

/**
 * 确认框。用户点确定返回 true，点取消或关闭返回 false（而不是抛出异常），调用处可以直接 if 判断。
 */
export function confirm(message, title = '提示', options = {}) {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
    ...options
  })
    .then(() => true)
    .catch(() => false)
}

/* ============================================================
 * 接口层：XApi
 * 职责：统一封装所有后端接口地址与参数，页面不直接拼 URL。
 * 依赖：XHttp（js/http.js）
 * ============================================================ */

const XApi = {
  /* ---------------- 服务信息 ---------------- */
  serverInfo() { return XHttp.get('/admin/server-info'); },

  /* ---------------- 仪表盘 ---------------- */
  dashboard() { return XHttp.get('/admin/dashboard'); },
  modelStats() { return XHttp.get('/admin/dashboard/models'); },
  modelFailStats() { return XHttp.get('/admin/dashboard/model-fail-stats'); },
  modelWeightStats() { return XHttp.get('/admin/dashboard/model-weight-stats'); },
  customerStats() { return XHttp.get('/admin/dashboard/customers'); },

  /* ---------------- API Key（对外客户） ---------------- */
  listKeys() { return XHttp.get('/admin/channels'); },
  saveKey(dto, editing) {
    return XHttp.request('/admin/channel', { method: editing ? 'PUT' : 'POST', body: dto });
  },
  updateKey(dto) {
    return XHttp.put('/admin/channel', dto);
  },
  deleteKey(id) { return XHttp.delete('/admin/channel/' + id); },

  /* ---------------- 渠道管理 ---------------- */
  listProviders() { return XHttp.get('/admin/providers'); },
  saveProvider(dto, editing) {
    return XHttp.request('/admin/provider', { method: editing ? 'PUT' : 'POST', body: dto });
  },
  deleteProvider(id) { return XHttp.delete('/admin/provider/' + id); },
  testProvider(id) { return XHttp.post('/admin/provider/' + id + '/test'); },
  testAllProviders() { return XHttp.post('/admin/providers/test-all'); },

  /* ---------------- 调用日志 ---------------- */
  queryLogs(params) {
    const qs = new URLSearchParams();
    Object.entries(params || {}).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') qs.set(k, v);
    });
    return XHttp.get('/admin/logs?' + qs.toString());
  },
};

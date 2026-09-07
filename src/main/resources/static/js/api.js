/* ============================================================
 * 接口层：XApi
 * 职责：统一封装所有后端接口地址与参数，页面不直接拼 URL。
 * 依赖：XHttp（js/http.js）
 * ============================================================ */

const XApi = {
  /* ---------------- 仪表盘 ---------------- */
  dashboard() { return XHttp.get('/admin/dashboard'); },
  tokenTrend(hours) { return XHttp.get('/admin/dashboard/trend?hours=' + (hours || 24)); },
  modelStats() { return XHttp.get('/admin/dashboard/models'); },

  /* ---------------- API Key（对客服务） ---------------- */
  listKeys() { return XHttp.get('/admin/channels'); },
  saveKey(dto, editing) {
    return XHttp.request('/admin/channel', { method: editing ? 'PUT' : 'POST', body: dto });
  },
  deleteKey(id) { return XHttp.delete('/admin/channel/' + id); },

  /* ---------------- 模型服务 ---------------- */
  listProviders() { return XHttp.get('/admin/providers'); },
  listEnabledProviders() { return XHttp.get('/admin/providers/enabled'); },
  saveProvider(dto, editing) {
    return XHttp.request('/admin/provider', { method: editing ? 'PUT' : 'POST', body: dto });
  },
  deleteProvider(id) { return XHttp.delete('/admin/provider/' + id); },
  testProvider(id) { return XHttp.post('/admin/provider/' + id + '/test'); },

  /* ---------------- 调用日志 ---------------- */
  queryLogs(params) {
    const qs = new URLSearchParams();
    Object.entries(params || {}).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') qs.set(k, v);
    });
    return XHttp.get('/admin/logs?' + qs.toString());
  },
};

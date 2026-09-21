/* ============================================================
 * 接口层：XApi
 * 职责：统一封装所有后端接口地址与参数，页面不直接拼 URL。
 * 依赖：XHttp（js/http.js）
 * ============================================================ */

/**
 * XApi 接口封装层
 * @namespace
 */
const XApi = {
  /** 获取服务器基础信息（IP、端口） */
  serverInfo() { return XHttp.get('/admin/server-info'); },

  /** 获取仪表盘汇总数据 */
  dashboard() { return XHttp.get('/admin/dashboard'); },

  /** 获取模型调用权重统计（按上游 Provider 聚合） */
  modelWeightStats() { return XHttp.get('/admin/dashboard/model-weight-stats'); },

  /** 获取今日客户调用 Top5 统计 */
  customerStats() { return XHttp.get('/admin/dashboard/customers'); },

  /** 获取所有 API KEY 列表（模型通道） */
  listKeys() { return XHttp.get('/admin/channels'); },

  /**
   * 保存或更新模型通道（新增/编辑统一接口）
   * @param {object} dto - ChannelDTO 数据对象
   * @param {boolean} editing - 是否为编辑模式（true=PUT，false=POST）
   */
  saveKey(dto, editing) {
    const url = editing ? '/admin/channel/' + dto.id : '/admin/channel';
    return XHttp.request(url, { method: editing ? 'PUT' : 'POST', body: dto });
  },

  /**
   * 更新模型通道（快捷方法，等价于 saveKey(dto, true)）
   * @param {object} dto - ChannelDTO 数据对象（须含 id）
   */
  updateKey(dto) {
    return XHttp.put('/admin/channel/' + dto.id, dto);
  },

  /** 删除模型通道 */
  deleteKey(id) { return XHttp.delete('/admin/channel/' + id); },

  /**
   * 获取所有渠道列表（含其下模型）
   * 返回元素：{ id, name, baseUrl, enabled, remark, createdAt, models: [{ id, modelName, enabled, failCount, remark }] }
   */
  listProviders() { return XHttp.get('/admin/providers'); },

  /**
   * 保存或更新渠道（含其下模型列表，新增/编辑统一接口）
   * @param {object} dto - ProviderDTO，形如 { name, baseUrl, apiKey, enabled, remark, models: [{ modelName, enabled, remark }] }
   * @param {boolean} editing - 是否为编辑模式（true=PUT，false=POST）
   */
  saveProvider(dto, editing) {
    const url = editing ? '/admin/provider/' + dto.id : '/admin/provider';
    return XHttp.request(url, { method: editing ? 'PUT' : 'POST', body: dto });
  },

  /** 删除渠道（级联删除其下全部模型） */
  deleteProvider(id) { return XHttp.delete('/admin/provider/' + id); },

  /** 测试单个模型行的连通性 */
  testModel(id) { return XHttp.post('/admin/model/' + id + '/test'); },

  /**
   * 探测上游渠道可用模型列表（新增/编辑渠道表单一键导入）
   * @param {object} body - { id?, baseUrl, apiKey }，编辑时 apiKey 留空复用已保存 Key
   * @returns {Promise<{ usedStoredKey: boolean, models: string[] }>}
   */
  fetchProviderModels(body) { return XHttp.post('/admin/provider/fetch-models', body); },

  /**
   * 分页查询调用日志
   * @param {object} params - LogQueryDTO 参数对象（可选字段将被过滤）
   * @returns {Promise<object>} 包含 total/page_num/page_size/list 的分页结果
   */
  queryLogs(params) {
    const qs = new URLSearchParams();
    Object.entries(params || {}).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') qs.set(k, v);
    });
    return XHttp.get('/admin/logs?' + qs.toString());
  },

  /* ==================== 流控管理 ==================== */

  /**
   * 列出候选模型（按优先级由高到低），含失败次数、并发上限、当前在途
   * @returns {Promise<Array>} 元素含 channelName/modelName/failCount/maxConcurrency/inFlight/priority
   */
  listConcurrencyModels() { return XHttp.get('/admin/concurrency/models'); },

  /**
   * 更新指定模型行的并发上限与失败次数
   * @param {number} id - 模型行 ID
   * @param {number} maxConcurrency - 并发上限；0 表示不限制
   * @param {number} failCount - 累计失败次数；0 表示无失败记录
   */
  updateConcurrencyLimit(id, maxConcurrency, failCount) {
    return XHttp.request('/admin/concurrency/model/' + id,
      { method: 'PUT', body: { maxConcurrency, fail_count: failCount } });
  },
};

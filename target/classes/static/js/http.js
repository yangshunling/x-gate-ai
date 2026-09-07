/* ============================================================
 * 请求层：XHttp
 * 职责：封装 fetch、统一处理 JSON 序列化、HTTP 错误、业务码错误。
 * 上层（api.js / 页面）一律通过 XHttp 访问后端。
 * ============================================================ */

const XHttp = {
  /**
   * 通用请求
   * @param {string} url      接口地址
   * @param {object} [options] { method, body }
   * @returns {Promise<any>}  后端 HttpResponse.result 值
   */
  async request(url, options = {}) {
    const opts = { method: options.method || 'GET', headers: {} };
    if (options.body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(options.body);
    }

    let resp;
    try {
      resp = await fetch(url, opts);
    } catch (e) {
      throw new Error('网络请求失败，请检查服务是否可用');
    }

    let data = null;
    try { data = await resp.json(); } catch (e) { /* 响应无 JSON 体 */ }

    if (!resp.ok) {
      throw new Error((data && data.message) || ('请求失败：HTTP ' + resp.status));
    }
    if (data && data.code !== undefined && data.code !== 200) {
      throw new Error(data.message || '操作失败');
    }
    return data ? data.result : undefined;
  },

  get(url) { return this.request(url); },
  post(url, body) { return this.request(url, { method: 'POST', body }); },
  put(url, body) { return this.request(url, { method: 'PUT', body }); },
  delete(url) { return this.request(url, { method: 'DELETE' }); },
};

/* ============================================================
 * 大模型统一网关 · 公共逻辑（所有页面共享）
 * 通过全局 mixin 注入：request / toast / 工具方法
 * ============================================================ */

const XGateNav = {
  brand: '大模型统一网关',
  items: [
    { key: 'dashboard', label: '仪表盘', href: 'index.html' },
    { key: 'models',    label: '对客服务', href: 'models.html' },
    { key: 'services',  label: '模型服务', href: 'services.html' },
    { key: 'logs',      label: '调用日志', href: 'logs.html' },
  ],
};

/* 通过 mixin 给每个页面实例注入公共能力，避免各页重复代码 */
const XGateMixin = {
  data() {
    return {
      toasts: [],
      toastSeq: 0,
    };
  },
  methods: {
    /* ---------------- 请求封装 ---------------- */
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
      try { data = await resp.json(); } catch (e) { /* 无 JSON 体 */ }
      if (!resp.ok) {
        throw new Error((data && data.message) || ('请求失败：HTTP ' + resp.status));
      }
      if (data && data.code !== undefined && data.code !== 200) {
        throw new Error(data.message || '操作失败');
      }
      return data ? data.result : undefined;
    },

    /* ---------------- Toast 提示 ---------------- */
    message(msg, type = 'success') {
      const id = ++this.toastSeq;
      this.toasts.push({ id, message: msg, type });
      setTimeout(() => {
        this.toasts = this.toasts.filter(t => t.id !== id);
      }, 2600);
    },
    toastError(err) {
      const m = (err && err.message) ? err.message : String(err || '未知错误');
      this.message(m, 'error');
    },

    /* ---------------- 工具 ---------------- */
    fmtTokens(n) {
      if (n == null || n === '') return '0';
      const num = Number(n);
      if (isNaN(num)) return String(n);
      if (num >= 100000000) return (num / 100000000).toFixed(2) + ' 亿';
      if (num >= 10000) return (num / 10000).toFixed(1) + ' 万';
      return String(num);
    },
  },
};

/* 为兼容原生 confirm 弹窗的自定义样式可在此扩展 */

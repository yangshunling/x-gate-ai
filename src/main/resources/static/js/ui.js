/* ============================================================
 * 界面层：XUi
 * 职责：
 *   1. 注册全站通用组件（x-nav 侧栏 / x-head 顶栏 / x-toast 提示）
 *   2. 提供 Toast 状态与管理方法的公共 mixin
 *   3. 提供通用工具方法（数字格式化、剪贴板复制等）
 * 页面模板只写自身内容，布局由组件渲染，避免重复。
 * ============================================================ */

/**
 * XUi 界面基础框架
 * @namespace
 */
const XUi = (() => {
  /** 导航菜单配置 */
  const NAV_ITEMS = [
    {
      key: 'dashboard', label: '仪表盘', href: 'index.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="2"/></svg>',
    },
    {
      key: 'services', label: '渠道管理', href: 'services.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="3" width="14" height="7" rx="2"/><rect x="5" y="14" width="14" height="7" rx="2"/><path d="M9 6.5h.01M9 17.5h.01"/><path d="M12 10v4"/></svg>',
    },
    {
      key: 'models', label: '客户管理', href: 'models.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2.4"/><circle cx="18" cy="18" r="2.4"/><circle cx="18" cy="6" r="2.4"/><path d="M8.3 7.3 15.7 16.7M8.3 4.7h7.4"/><path d="M4.7 8.3v7.4h9"/></svg>',
    },
    {
      key: 'logs', label: '调用日志', href: 'logs.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h9M4 12h16M4 18h7"/><circle cx="16.5" cy="6" r="1.8"/><circle cx="20" cy="18" r="1.8"/></svg>',
    },
  ];

  /* ---------------- 通用组件 ---------------- */

  /** 侧栏导航组件 */
  const Nav = {
    name: 'XNav',
    props: { active: { type: String, required: true } },
    template: `
      <aside class="sidebar">
        <div class="brand">
          <div class="brand-mark">
            <svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h7l-1 8 10-12h-7l1-8z"/></svg>
          </div>
          <div class="brand-name">大模型统一网关<small>管理控制台</small></div>
        </div>
        <div class="nav-label">功能导航</div>
        <nav class="menu">
          <a v-for="item in items" :key="item.key" class="menu-item"
             :class="{ active: item.key === active }" :href="item.href">
            <span class="nav-ico" v-html="item.icon"></span>
            <span>{{ item.label }}</span>
          </a>
        </nav>
      </aside>
    `,
    data() { return { items: NAV_ITEMS }; },
  };

  /** 顶栏组件 */
  const Head = {
    name: 'XHead',
    props: { title: { type: String, required: true } },
    template: `
      <div class="head">
        <h1><span class="bar"></span>{{ title }}</h1>
      </div>
    `,
  };

  /** Toast 通知组件 */
  const Toast = {
    name: 'XToast',
    props: { list: { type: Array, required: true } },
    template: `
      <div class="toast-wrap">
        <div v-for="t in list" :key="t.id" class="toast" :class="t.type">
          <span class="dot"></span>{{ t.message }}
        </div>
      </div>
    `,
  };

  /* ---------------- 公共 mixin ---------------- */

  /**
   * 全站通用 mixin
   * 每个页面 Vue 实例通过 mixins: [XUi.mixin] 继承以下方法
   */
  const mixin = {
    data() {
      return { toasts: [], toastSeq: 0 };
    },
    methods: {
      /**
       * 显示通知消息
       * @param {string} msg - 消息内容
       * @param {string} [type='success'] - 消息类型：success / error / warn / info
       */
      message(msg, type = 'success') {
        const id = ++this.toastSeq;
        this.toasts.push({ id, message: msg, type });
        setTimeout(() => {
          this.toasts = this.toasts.filter(t => t.id !== id);
        }, 2600);
      },

      /**
       * 显示错误通知
       * @param {Error|*} err - 错误对象或字符串
       */
      toastError(err) {
        const m = (err && err.message) ? err.message : String(err || '未知错误');
        this.message(m, 'error');
      },

      /**
       * 中文数字格式化（支持万/亿单位）
       * @param {*} n - 数值
       * @returns {string} 格式化后字符串
       */
      fmtTokens(n) {
        if (n == null || n === '') return '0';
        const num = Number(n);
        if (isNaN(num)) return String(n);
        if (num >= 100000000) return (num / 100000000).toFixed(2) + ' 亿';
        if (num >= 10000) return (num / 10000).toFixed(1) + ' 万';
        return String(num);
      },

      /**
       * 异步复制文本到剪贴板，支持安全上下文优先降级
       * @param {string} text - 待复制文本
       * @param {string} [successMsg='已复制'] - 成功提示文案
       */
      async copyText(text, successMsg = '已复制') {
        let done = false;
        if (navigator.clipboard && window.isSecureContext) {
          try { await navigator.clipboard.writeText(text); done = true; } catch (e) { /* fallback */ }
        }
        if (!done) {
          try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.focus();
            ta.select();
            done = document.execCommand('copy');
            document.body.removeChild(ta);
          } catch (e) { done = false; }
        }
        this.message(done ? successMsg : '复制失败，请手动复制', done ? 'success' : 'error');
      },

      /**
       * 初始化 BaseURL：优先使用服务端提供的局域网 IP，失败回退 origin
       */
      async initBaseURL() {
        if (!this.baseURL) this.baseURL = window.location.origin + '/v1';
        try {
          const info = await XApi.serverInfo();
          if (info && info.host && info.port) {
            this.baseURL = `${window.location.protocol}//${info.host}:${info.port}/v1`;
          }
        } catch (e) { /* 回退 window.location.origin */ }
      },
    },
  };

  return {
    NAV_ITEMS,
    components: { Nav, Head, Toast },
    mixin,

    /**
     * 创建并挂载页面 Vue 实例，自动注册公共组件
     * @param {object} appOptions - Vue 组件配置（含 mixins/data/methods）
     * @returns {Vue} 已挂载的 Vue 实例
     */
    mount(appOptions) {
      const app = Vue.createApp(appOptions);
      app.component('x-nav', Nav);
      app.component('x-head', Head);
      app.component('x-toast', Toast);
      return app.mount('#app');
    },
  };
})();

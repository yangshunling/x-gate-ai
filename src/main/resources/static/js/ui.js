/* ============================================================
 * 界面层：XUi
 * 职责：
 *   1. 注册全站通用组件（x-nav 侧栏 / x-head 顶栏 / x-toast 提示）
 *   2. 提供 Toast 状态与管理方法的公共 mixin
 *   3. 提供通用工具方法（数字格式化等）
 * 页面模板只写自身内容，布局由组件渲染，避免重复。
 * ============================================================ */

const XUi = (() => {
  /* ---------------- 导航配置 ---------------- */
  const NAV_ITEMS = [
    {
      key: 'dashboard', label: '仪表盘', href: 'index.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="2"/></svg>',
    },
    {
      key: 'models', label: 'API Key', href: 'models.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2.4"/><circle cx="18" cy="18" r="2.4"/><circle cx="18" cy="6" r="2.4"/><path d="M8.3 7.3 15.7 16.7M8.3 4.7h7.4"/><path d="M4.7 8.3v7.4h9"/></svg>',
    },
    {
      key: 'services', label: '模型服务', href: 'services.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="3" width="14" height="7" rx="2"/><rect x="5" y="14" width="14" height="7" rx="2"/><path d="M9 6.5h.01M9 17.5h.01"/><path d="M12 10v4"/></svg>',
    },
    {
      key: 'logs', label: '调用日志', href: 'logs.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h9M4 12h16M4 18h7"/><circle cx="16.5" cy="6" r="1.8"/><circle cx="20" cy="18" r="1.8"/></svg>',
    },
    {
      key: 'usage', label: '接入示例', href: 'usage.html',
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3v5h5"/><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M10 14l-1.5-1.5L10 11M14 11l1.5 1.5L14 14"/></svg>',
    },
  ];

  /* ---------------- 通用组件 ---------------- */

  /* 侧栏 */
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
        <div class="side-foot">
          <span class="pulse"></span>
          <div>
            <div class="t">服务运行中 <b>ONLINE</b></div>
            <div class="v">/v1 · OpenAI 兼容</div>
          </div>
        </div>
      </aside>
    `,
    data() { return { items: NAV_ITEMS }; },
  };

  /* 顶栏 */
  const Head = {
    name: 'XHead',
    props: {
      title: { type: String, required: true },
      sub: { type: String, default: '' },
    },
    template: `
      <div class="head">
        <h1><span class="bar"></span>{{ title }}</h1>
        <span class="sub">{{ sub }}</span>
      </div>
    `,
  };

  /* Toast 容器 */
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

  /* ---------------- 公共 mixin：每个页面实例都具备 ---------------- */
  const mixin = {
    data() {
      return { toasts: [], toastSeq: 0 };
    },
    methods: {
      /* 提示消息 */
      message(msg, type = 'success') {
        const id = ++this.toastSeq;
        this.toasts.push({ id, message: msg, type });
        setTimeout(() => {
          this.toasts = this.toasts.filter(t => t.id !== id);
        }, 2600);
      },
      /* 错误提示 */
      toastError(err) {
        const m = (err && err.message) ? err.message : String(err || '未知错误');
        this.message(m, 'error');
      },
      /* token 数字格式化 */
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

  return {
    NAV_ITEMS,
    components: { Nav, Head, Toast },
    mixin,

    /**
     * 创建并挂载页面实例，自动注册公共组件
     * @param {object} appOptions Vue 组件配置（含 mixins/data/methods）
     * @returns {Vue} 已挂载的实例
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

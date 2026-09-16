/* ============================================================
 * 皮肤管理：SkinManager + SkinSwitcher 组件
 * 职责：
 *   1. 提供 7 套皮肤元数据（名称、色深、色板预览）
 *   2. 切换 html[data-theme] 并持久化到 localStorage
 *   3. 提供 Vue 组件 x-skin，嵌入顶栏右上角
 * 依赖：themes.css（令牌定义）
 * ============================================================ */

/**
 * 皮肤元数据
 * swatch 值与 themes.css 中 --logo-gradient 对应
 */
const SKIN_THEMES = [
  { key: 'maidAtelier',   name: '女仆工坊',  scheme: '浅色', swatch: 'linear-gradient(135deg, #526aa8, #c5a468)' },
  { key: 'deepseekChan',  name: '深海回响',  scheme: '深色', swatch: 'linear-gradient(135deg, #4d9fff, #0b1425)' },
  { key: 'cloudLab',      name: '云海实验室', scheme: '浅色', swatch: 'linear-gradient(135deg, #5e7ce2, #c8e6ff)' },
  { key: 'inkAlgorithm', name: '山海算境',  scheme: '浅色', swatch: 'linear-gradient(135deg, #b13a34, #f2f0ea)' },
  { key: 'abyssStarport', name: '深海星港',  scheme: '深色', swatch: 'linear-gradient(135deg, #28d7d0, #071b24)' },
  { key: 'deepseaWhale',  name: '深海鲸歌',  scheme: '深色', swatch: 'linear-gradient(135deg, #55bdf2, #082039)' },
  { key: 'orcaLink',      name: '虎鲸链路',  scheme: '浅色', swatch: 'linear-gradient(135deg, #4b483f, #20c7e8)' },
];

/**
 * 皮肤管理器（全局单例）
 * @namespace
 */
const SkinManager = {
  /** 当前皮肤 key（默认 maidAtelier） */
  current: 'maidAtelier',

  /** localStorage 键名 */
  STORAGE_KEY: 'x-gate-skin',

  /** 初始化：读取已保存皮肤并应用 */
  init() {
    this.current = localStorage.getItem(this.STORAGE_KEY) || 'maidAtelier';
    this.apply(this.current);
  },

  /**
   * 应用皮肤
   * @param {string} key - 皮肤 key
   */
  apply(key) {
    this.current = key;
    if (key) {
      document.documentElement.dataset.theme = key;
    } else {
      delete document.documentElement.dataset.theme;
    }
    localStorage.setItem(this.STORAGE_KEY, key);
    this.syncMaidChars(key);
  },

  /**
   * 女仆工坊主题需要注入角色立绘 <img>
   * 其他主题则移除
   */
  syncMaidChars(key) {
    const existing = document.querySelectorAll('.maid-char');
    if (key === 'maidAtelier') {
      if (existing.length) return;
      const left = document.createElement('img');
      left.className = 'maid-char maid-left';
      left.src = 'img/maid-left.webp';
      left.alt = '';
      const right = document.createElement('img');
      right.className = 'maid-char maid-right';
      right.src = 'img/maid-right.webp';
      right.alt = '';
    document.body.prepend(left, right);
    } else {
      existing.forEach(el => el.remove());
    }
  },
};

/* 立即初始化，避免页面闪烁 */
SkinManager.init();

/**
 * 皮肤切换器 Vue 组件
 * 注册为 <x-skin>，嵌入顶栏右上角
 */
const SkinSwitcher = {
  name: 'XSkin',
  template: `
    <div class="skin-wrap">
      <button class="skin-btn" @click.stop="togglePanel" title="切换皮肤">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="9"/>
          <path d="M12 3a9 9 0 0 0 0 18" />
          <path d="M3 12h18" />
          <path d="M12 3a9 9 0 0 1 0 18" />
          <circle cx="12" cy="12" r="3" fill="currentColor" stroke="none" opacity=".3"/>
        </svg>
      </button>
      <transition name="skin-drop">
        <div v-if="open" class="skin-panel">
          <div class="skin-panel-title">皮肤切换</div>
          <div class="skin-list">
            <div v-for="t in themes" :key="t.key || 'default'"
                 class="skin-item" :class="{ active: t.key === current }"
                 @click="select(t.key)">
              <div class="skin-swatch" :style="{ background: t.swatch }"></div>
              <div class="skin-info">
                <div class="skin-name">{{ t.name }}</div>
                <div class="skin-scheme">{{ t.scheme }}</div>
              </div>
              <svg v-if="t.key === current" class="skin-check" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>
            </div>
          </div>
        </div>
      </transition>
    </div>
  `,
  data() {
    return {
      open: false,
      themes: SKIN_THEMES,
      current: SkinManager.current,
    };
  },
  mounted() {
    document.addEventListener('click', this.handleOutside);
  },
  beforeUnmount() {
    document.removeEventListener('click', this.handleOutside);
  },
  methods: {
    togglePanel() {
      this.open = !this.open;
    },
    select(key) {
      this.current = key;
      SkinManager.apply(key);
      this.open = false;
    },
    handleOutside(e) {
      if (!this.$el.contains(e.target)) this.open = false;
    },
  },
};

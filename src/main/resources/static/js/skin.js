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
  { key: 'classic',       name: '经典后台',  scheme: '浅色', swatch: 'linear-gradient(135deg, #2563eb, #1d4ed8)', wallpaper: 'url("preview/classic.png") center / cover no-repeat', sidebar: '#ffffff' },
  { key: 'maidAtelier',   name: '女仆工坊',  scheme: '浅色', swatch: 'linear-gradient(135deg, #526aa8, #c5a468)', wallpaper: 'url("preview/maidAtelier.png") center / cover no-repeat', sidebar: '#10204d' },
  { key: 'deepseekChan',  name: '深海回响',  scheme: '深色', swatch: 'linear-gradient(135deg, #4d9fff, #0b1425)', wallpaper: 'url("preview/deepseekChan.png") center / cover no-repeat', sidebar: '#0b1425' },
  { key: 'cloudLab',      name: '云海实验室', scheme: '浅色', swatch: 'linear-gradient(135deg, #5e7ce2, #c8e6ff)', wallpaper: 'url("preview/cloudLab.png") center / cover no-repeat', sidebar: '#e8f2fb' },
  { key: 'inkAlgorithm', name: '山海算境',  scheme: '浅色', swatch: 'linear-gradient(135deg, #b13a34, #f2f0ea)', wallpaper: 'url("preview/inkAlgorithm.png") center / cover no-repeat', sidebar: '#f7f3e8' },
  { key: 'deepseaWhale',  name: '深海鲸歌',  scheme: '深色', swatch: 'linear-gradient(135deg, #55bdf2, #082039)', wallpaper: 'url("preview/deepseaWhale.png") center / cover no-repeat', sidebar: '#0d2c4c' },
];

/**
 * 皮肤管理器（全局单例）
 * @namespace
 */
const SkinManager = {
  /** 当前皮肤 key（默认 classic） */
  current: 'classic',

  /** localStorage 键名 */
  STORAGE_KEY: 'x-gate-skin',

  /** 初始化：读取已保存皮肤并应用 */
  init() {
    this.current = localStorage.getItem(this.STORAGE_KEY) || 'classic';
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
      <button class="skin-pill" @click.stop="togglePanel" title="切换皮肤">
        <span class="skin-pill-dot" :style="{ background: currentSwatch }"></span>
        <span class="skin-pill-label">{{ currentName }}</span>
        <svg class="skin-pill-caret" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
      </button>
      <transition name="skin-drop">
        <div v-if="open" class="skin-panel">
          <div class="skin-grid">
            <div v-for="t in themes" :key="t.key || 'default'"
                 class="skin-chip" :class="{ active: t.key === current }"
                 :title="t.name + ' · ' + t.scheme"
                 @click="select(t.key)">
              <div class="skin-chip-preview" :style="{ background: t.wallpaper }">
                <div class="sp-side" :style="{ background: t.sidebar }"></div>
                <svg v-if="t.key === current" class="skin-chip-check" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>
              </div>
              <div class="skin-chip-text">
                <span class="skin-chip-name">{{ t.name }}</span>
                <span class="skin-chip-scheme">{{ t.scheme }}</span>
              </div>
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
  computed: {
    currentTheme() {
      return this.themes.find(t => t.key === this.current) || this.themes[0];
    },
    currentSwatch() { return this.currentTheme.swatch; },
    currentName() { return this.currentTheme.name; },
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

/**
 * 玻璃浓度调节器 Vue 组件
 * 注册为 <x-glass>，嵌入顶栏右上角（皮肤按钮左侧）
 * 拖动滑块即时改写 html.style.--glass-alpha 并持久化到 localStorage
 */
const GlassControl = {
  name: 'XGlass',
  template: `
    <div class="skin-wrap">
      <button class="skin-pill" @click.stop="toggle" title="玻璃浓度">
        <svg class="glass-pill-ico" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z"/><path d="M9 14a3 3 0 0 0 3 3" stroke-width="1.4" opacity=".55"/></svg>
        <span class="glass-pill-val">{{ value }}%</span>
        <svg class="skin-pill-caret" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
      </button>
      <transition name="skin-drop">
        <div v-if="open" class="skin-panel glass-panel">
          <div class="glass-ctl">
            <input type="range" min="0" max="300" step="1" v-model.number="value" @input="apply">
            <div class="glass-info">
              <span class="glass-val">{{ value }}%</span>
              <a class="glass-reset" @click="reset">重置</a>
            </div>
          </div>
        </div>
      </transition>
    </div>
  `,
  data() {
    const raw = Number(localStorage.getItem('x-gate-glass'));
    return {
      open: false,
      value: isNaN(raw) ? 100 : Math.min(300, Math.max(0, raw)),
    };
  },
  mounted() {
    this.apply();
    document.addEventListener('click', this.handleOutside);
  },
  beforeUnmount() {
    document.removeEventListener('click', this.handleOutside);
  },
  methods: {
    apply() {
      const a = (this.value / 100).toFixed(2);
      document.documentElement.style.setProperty('--glass-alpha', a);
      localStorage.setItem('x-gate-glass', this.value);
    },
    reset() { this.value = 100; this.apply(); },
    toggle() { this.open = !this.open; },
    handleOutside(e) {
      if (!this.$el.contains(e.target)) this.open = false;
    },
  },
};

/* ============================================================
 * 仪表盘页面
 * 含：今日指标卡 / 模型分布表 / 模型失败次数 / 客户用量 Top5
 * ============================================================ */

/** 模型分布图表色板（循环使用） */
const MODEL_COLORS = [
  '#2563eb', '#10b981', '#8b5cf6', '#f59e0b',
  '#ef4444', '#06b6d4', '#6366f1', '#14b8a6',
  '#d946ef', '#f97316',
];

/**
 * DashboardApp 仪表盘页面实例
 */
const DashboardApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      dash: {},
      loading: false,
      providersTotal: 0,

      /* 模型分布 */
      dist: [],
      distLoading: false,

      /* 模型失败次数 */
      failStats: [],
      failLoading: false,

      /* 模型调用权重 */
      weightStats: [],
      weightLoading: false,

      /* 客户用量 Top5 */
      custStats: [],
      custLoading: false,
    };
  },

  created() {
    this.loadDash();
    this.loadDist();
    this.loadFail();
    this.loadCustomers();
    this.loadWeights();
    this.loadProvidersTotal();
  },

  methods: {
    async loadDash() {
      this.loading = true;
      try {
        this.dash = await XApi.dashboard() || {};
      } catch (e) {
        this.toastError(e);
      } finally {
        this.loading = false;
      }
    },

    async loadDist() {
      this.distLoading = true;
      try {
        this.dist = await XApi.modelStats() || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.distLoading = false;
      }
    },

    async loadFail() {
      this.failLoading = true;
      try {
        this.failStats = await XApi.modelFailStats() || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.failLoading = false;
      }
    },

    async loadCustomers() {
      this.custLoading = true;
      try {
        this.custStats = await XApi.customerStats() || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.custLoading = false;
      }
    },

    async loadWeights() {
      this.weightLoading = true;
      try {
        this.weightStats = await XApi.modelWeightStats() || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.weightLoading = false;
      }
    },

    async loadProvidersTotal() {
      try {
        const list = await XApi.listProviders() || [];
        this.providersTotal = list.length;
      } catch (e) {
        /* 忽略，不影响主流程 */
      }
    },

    /* ==================== 模型分布辅助 ==================== */

    /** 返回分布数据中调用次数的最大值 */
    distMax() {
      return Math.max(...this.dist.map(d => d.calls), 1);
    },

    /** 根据索引返回对应色板颜色 */
    distColor(i) {
      return MODEL_COLORS[i % MODEL_COLORS.length];
    },

    /** 返回成功率的 CSS 类名（ok/warn/bad） */
    distRateClass(d) {
      const success = d.calls > 0 ? (d.successCalls / d.calls) : 1;
      if (success >= 0.95) return 'ok';
      if (success >= 0.8) return 'warn';
      return 'bad';
    },

    /** 返回成功率百分比字符串 */
    distRate(d) {
      const success = d.calls > 0 ? (d.successCalls / d.calls) : 1;
      return (success * 100).toFixed(1) + '%';
    },

    /* ==================== 模型失败辅助 ==================== */

    /** 返回失败次数最大值 */
    failMax() {
      return Math.max(...this.failStats.map(f => f.failCount || 0), 1);
    },

    /** 前 3 项绿色，其余红色 */
    failColor(i) {
      return i < 3 ? '#10b981' : '#ef4444';
    },

    /** 根据失败次数计算进度条宽度百分比 */
    failWidth(v) {
      return Math.round((v / this.failMax()) * 100);
    },

    /* ==================== 工具 ==================== */

    /** 短格式 Token 数字（K/M 单位） */
    shortToken(v) {
      if (v == null) return '0';
      if (v >= 1000000) return (v / 1000000).toFixed(1) + 'M';
      if (v >= 1000) return (v / 1000).toFixed(1) + 'K';
      return String(v);
    },
  },
};

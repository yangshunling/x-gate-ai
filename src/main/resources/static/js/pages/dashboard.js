/* ============================================================
 * 仪表盘页面
 * 含：今日指标卡 / 模型分布表 / 模型失败次数 / 客户用量 Top5
 * ============================================================ */

const MODEL_COLORS = [
  '#2563eb', '#10b981', '#8b5cf6', '#f59e0b',
  '#ef4444', '#06b6d4', '#6366f1', '#14b8a6',
  '#d946ef', '#f97316',
];

const DashboardApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      dash: {},
      loading: false,

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
  },

  methods: {
    /* ==================== 数据加载 ==================== */
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

    /* ==================== 模型分布辅助 ==================== */
    distMax() {
      return Math.max(...this.dist.map(d => d.calls), 1);
    },
    distColor(i) {
      return MODEL_COLORS[i % MODEL_COLORS.length];
    },
    distRateClass(d) {
      const success = d.calls > 0 ? (d.successCalls / d.calls) : 1;
      if (success >= 0.95) return 'ok';
      if (success >= 0.8) return 'warn';
      return 'bad';
    },
    distRate(d) {
      const success = d.calls > 0 ? (d.successCalls / d.calls) : 1;
      return (success * 100).toFixed(1) + '%';
    },

    /* ==================== 模型失败辅助 ==================== */
    failMax() {
      return Math.max(...this.failStats.map(f => f.failCount || 0), 1);
    },
    failColor(i) {
      // 红色渐变：越多越红
      return i < 3 ? '#10b981' : '#ef4444';
    },
    failWidth(v) {
      return Math.round((v / this.failMax()) * 100);
    },

    /* ==================== 工具 ==================== */
    shortToken(v) {
      if (v == null) return '0';
      if (v >= 1000000) return (v / 1000000).toFixed(1) + 'M';
      if (v >= 1000) return (v / 1000).toFixed(1) + 'K';
      return String(v);
    },
  },
};

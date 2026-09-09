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

const DashboardApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      dash: {},
      loading: false,
      providersTotal: 0,
      dist: [],
      distLoading: false,
      failStats: [],
      failLoading: false,
      weightStats: [],
      weightLoading: false,
      custStats: [],
      custLoading: false,
    };
  },
  created() {
    this.loading = true;
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
      this.dash = await this.asyncLoad(() => XApi.dashboard()) || {};
      this.loading = false;
    },
    async loadDist() {
      this.distLoading = true;
      this.dist = await this.asyncLoad(() => XApi.modelStats()) || [];
      this.distLoading = false;
    },
    async loadFail() {
      this.failLoading = true;
      this.failStats = await this.asyncLoad(() => XApi.modelFailStats()) || [];
      this.failLoading = false;
    },
    async loadCustomers() {
      this.custLoading = true;
      this.custStats = await this.asyncLoad(() => XApi.customerStats()) || [];
      this.custLoading = false;
    },
    async loadWeights() {
      this.weightLoading = true;
      this.weightStats = await this.asyncLoad(() => XApi.modelWeightStats()) || [];
      this.weightLoading = false;
    },
    async loadProvidersTotal() {
      const list = await this.asyncLoad(() => XApi.listProviders()) || [];
      this.providersTotal = list.length;
    },

    distMax() { return Math.max(...this.dist.map(d => d.calls), 1); },
    distColor(i) { return MODEL_COLORS[i % MODEL_COLORS.length]; },
    distRateClass(d) {
      const s = d.calls > 0 ? (d.successCalls / d.calls) : 1;
      if (s >= 0.95) return 'ok';
      if (s >= 0.8) return 'warn';
      return 'bad';
    },
    distRate(d) {
      const s = d.calls > 0 ? (d.successCalls / d.calls) : 1;
      return (s * 100).toFixed(1) + '%';
    },
    failMax() { return Math.max(...this.failStats.map(f => f.failCount || 0), 1); },
    failColor(i) { return i < 3 ? '#10b981' : '#ef4444'; },
    failWidth(v) { return Math.round((v / this.failMax()) * 100); },
  },
};

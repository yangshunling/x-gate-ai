/* ============================================================
 * 仪表盘页面
 * 含：今日指标卡 / Token 趋势图 / 模型分布表
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

      /* Token 趋势 */
      trend: [],
      trendLoading: false,
      trendHours: 24,
      showInput: true,
      showOutput: true,

      /* 模型分布 */
      dist: [],
      distLoading: false,
    };
  },

  created() {
    this.loadDash();
    this.loadTrend();
    this.loadDist();
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

    /* ---------------- Token 趋势 ---------------- */
    async loadTrend() {
      this.trendLoading = true;
      try {
        this.trend = await XApi.tokenTrend(this.trendHours) || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.trendLoading = false;
      }
    },
    switchHours(h) {
      this.trendHours = h;
      this.loadTrend();
    },

    /* ---------------- 模型分布 ---------------- */
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

    /* ---------------- 趋势图计算 ---------------- */
    trendMax() {
      let max = 0;
      this.trend.forEach(p => {
        if (this.showInput && p.inputTokens > max) max = p.inputTokens;
        if (this.showOutput && p.outputTokens > max) max = p.outputTokens;
      });
      return max > 0 ? max : 1;
    },
    trendPoint(v, max, chartH) {
      return max > 0 ? (chartH - (v / max) * chartH) : chartH;
    },
    trendPath(key, chartW, chartH) {
      const n = this.trend.length;
      if (n === 0) return '';
      const step = n > 1 ? chartW / (n - 1) : chartW;
      const max = this.trendMax();
      let d = '';
      this.trend.forEach((p, i) => {
        const x = i * step;
        const y = this.trendPoint(p[key] || 0, max, chartH);
        d += (i === 0 ? 'M' : ' L') + ' ' + x.toFixed(1) + ' ' + y.toFixed(1);
      });
      return d;
    },
    trendAreaPath(key, chartW, chartH) {
      const line = this.trendPath(key, chartW, chartH);
      if (!line) return '';
      const n = this.trend.length;
      const lastX = n > 1 ? (n - 1) * (chartW / (n - 1)) : chartW;
      return line + ' L ' + lastX.toFixed(1) + ' ' + chartH + ' L 0 ' + chartH + ' Z';
    },
    trendLabels(step) {
      return this.trend.filter((p, i) => i % step === 0);
    },
    trendLabel(p) {
      if (!p) return '';
      const s = String(p.hour || '');
      // hour 形如 yyyy-MM-dd HH
      const m = s.match(/(\d{4}-\d{2}-\d{2}) (\d{2})/);
      if (!m) return s;
      return this.trendHours >= 24 * 7 ? m[1] : m[2] + ':00';
    },
    shortToken(v) {
      if (v == null) return '0';
      if (v >= 1000000) return (v / 1000000).toFixed(1) + 'M';
      if (v >= 1000) return (v / 1000).toFixed(1) + 'K';
      return String(v);
    },

    /* ---------------- 模型分布辅助 ---------------- */
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
  },
};

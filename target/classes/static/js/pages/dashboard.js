/* ============================================================
 * 仪表盘页面逻辑
 * ============================================================ */

const DashboardApp = {
  mixins: [XGateMixin],
  data() {
    return {
      dash: {},
      dashLoading: false,
    };
  },
  created() {
    this.loadDashboard();
  },
  methods: {
    async loadDashboard() {
      this.dashLoading = true;
      try {
        this.dash = await this.request('/admin/dashboard') || {};
      } catch (e) {
        this.toastError(e);
      } finally {
        this.dashLoading = false;
      }
    },
  },
};

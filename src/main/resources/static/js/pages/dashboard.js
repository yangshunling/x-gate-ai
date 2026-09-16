/* ============================================================
 * 仪表盘页面
 * 含：历史/今日指标卡 / 资源统计 / 客户用量 Top5 / 模型调用权重
 * ============================================================ */

const DashboardApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      dash: {},
      loading: false,
      providersTotal: 0,
      custStats: [],
      custLoading: false,
      weightStats: [],
      weightLoading: false,
    };
  },
  created() {
    this.loadDash();
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
  },
};

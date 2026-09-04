/* ============================================================
 * 调用日志页面逻辑
 * ============================================================ */

const LogsApp = {
  mixins: [XGateMixin],
  data() {
    return {
      log: { publicModel: '', page: 1, pageSize: 10, total: 0, totalPages: 1 },
      logs: { list: [] },
      logsLoading: false,
    };
  },
  created() {
    this.searchLogs(1);
  },
  methods: {
    async searchLogs(page) {
      this.logsLoading = true;
      const qs = new URLSearchParams();
      if (this.log.publicModel) qs.set('publicModel', this.log.publicModel);
      qs.set('pageNum', page || 1);
      qs.set('pageSize', this.log.pageSize);
      try {
        const r = await this.request('/admin/logs?' + qs.toString()) || {};
        this.log.page = r.page_num || page || 1;
        this.log.pageSize = r.page_size || this.log.pageSize;
        this.log.total = r.total || 0;
        this.log.totalPages = Math.max(1, Math.ceil(this.log.total / this.log.pageSize));
        this.logs.list = r.list || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.logsLoading = false;
      }
    },
    resetLogs() {
      this.log.publicModel = '';
      this.log.page = 1;
      this.searchLogs(1);
    },
  },
};

/* ============================================================
 * 调用日志页面
 * 含：高级筛选（客户名/日期/状态码）、行内详情展开
 * ============================================================ */

const LogsApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      filter: { customerName: '', dateFrom: '', dateTo: '', status: '' },
      page: { num: 1, size: 10, total: 0, totalPages: 1 },
      list: [],
      loading: false,
      expanded: null,
    };
  },
  created() { this.search(1); },
  methods: {
    async search(page) {
      this.loading = true;
      const r = await this.asyncLoad(() => XApi.queryLogs({
        publicModel: this.filter.customerName,
        dateFrom: this.filter.dateFrom,
        dateTo: this.filter.dateTo,
        status: this.filter.status,
        pageNum: page || 1,
        pageSize: this.page.size,
      }));
      if (r) {
        this.page.num = r.page_num || page || 1;
        this.page.size = r.page_size || this.page.size;
        this.page.total = r.total || 0;
        this.page.totalPages = Math.max(1, Math.ceil(this.page.total / this.page.size));
        this.list = r.list || [];
        this.expanded = null;
      }
      this.loading = false;
    },

    reset() {
      this.filter = { customerName: '', dateFrom: '', dateTo: '', status: '' };
      this.search(1);
    },

    toggleDetail(log) {
      this.expanded = this.expanded === log.id ? null : log.id;
    },

    requestMessages(log) {
      if (!log.request_body) return [];
      try {
        const body = JSON.parse(log.request_body);
        const msgs = Array.isArray(body.messages) ? body.messages : [];
        return msgs.map(m => ({
          role: m.role || 'unknown',
          content: typeof m.content === 'string'
            ? m.content
            : (m.content && typeof m.content === 'object' ? JSON.stringify(m.content) : ''),
        }));
      } catch (e) { return []; }
    },

    userMessages(log) {
      return this.requestMessages(log).filter(m => m.role === 'user');
    },

    requestSummary(log) {
      if (!log.request_body) return null;
      try {
        const body = JSON.parse(log.request_body);
        return (body && (body.type === 'chat' || body.type === 'embedding')) ? body : null;
      } catch (e) { return null; }
    },
  },
};

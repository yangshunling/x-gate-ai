/* ============================================================
 * 调用日志页面
 * 含：高级筛选（模型/日期/状态码）、行内详情展开
 * ============================================================ */

const LogsApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      /* 筛选条件 */
      filter: { model: '', dateFrom: '', dateTo: '', status: '' },

      /* 分页 */
      page: { num: 1, size: 10, total: 0, totalPages: 1 },
      list: [],
      loading: false,

      /* 展开详情 */
      expanded: null,
    };
  },

  created() { this.search(1); },

  methods: {
    async search(page) {
      this.loading = true;
      try {
        const r = await XApi.queryLogs({
          publicModel: this.filter.model,
          dateFrom: this.filter.dateFrom,
          dateTo: this.filter.dateTo,
          status: this.filter.status,
          pageNum: page || 1,
          pageSize: this.page.size,
        }) || {};
        this.page.num = r.page_num || page || 1;
        this.page.size = r.page_size || this.page.size;
        this.page.total = r.total || 0;
        this.page.totalPages = Math.max(1, Math.ceil(this.page.total / this.page.size));
        this.list = r.list || [];
        this.expanded = null;
      } catch (e) {
        this.toastError(e);
      } finally {
        this.loading = false;
      }
    },
    reset() {
      this.filter = { model: '', dateFrom: '', dateTo: '', status: '' };
      this.search(1);
    },

    /* ---------------- 详情展开 ---------------- */
    toggleDetail(log) {
      this.expanded = this.expanded === log.id ? null : log.id;
    },

    /* 解析请求体 messages（兼容历史数据：request_body 为完整请求体 JSON） */
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
      } catch (e) {
        return [];
      }
    },
    /* 解析轻量摘要（方案 B：新日志 request_body 为摘要 JSON，不含 messages 全文） */
    requestSummary(log) {
      if (!log.request_body) return null;
      try {
        const body = JSON.parse(log.request_body);
        return (body && (body.type === 'chat' || body.type === 'embedding')) ? body : null;
      } catch (e) {
        return null;
      }
    },
    /* 角色分布文案：如 system ×1 · user ×2 · assistant ×1 */
    summaryRolesText(summary) {
      if (!summary || !summary.roles) return '';
      const roleNames = { system: 'System', user: 'User', assistant: 'AI', tool: 'Tool' };
      const parts = Object.keys(summary.roles).map(r => {
        const label = roleNames[r] || r;
        return label + ' ×' + summary.roles[r];
      });
      return parts.join(' · ');
    },
    roleLabel(role) {
      return { system: 'System', user: 'User', assistant: 'AI', tool: 'Tool' }[role] || role;
    },
    roleClass(role) {
      return {
        system: 'role-system', user: 'role-user', assistant: 'role-ai', tool: 'role-tool',
      }[role] || '';
    },
    prettyJson(json) {
      if (!json) return '';
      try { return JSON.stringify(JSON.parse(json), null, 2); } catch (e) { return json; }
    },
  },
};

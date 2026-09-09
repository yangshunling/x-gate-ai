/* ============================================================
 * 调用日志页面
 * 含：高级筛选（客户名/日期/状态码）、行内详情展开
 * ============================================================ */

/**
 * LogsApp 调用日志页面实例
 */
const LogsApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      /* 筛选条件 */
      filter: { customerName: '', dateFrom: '', dateTo: '', status: '' },

      /* 分页 */
      page: { num: 1, size: 10, total: 0, totalPages: 1 },
      list: [],
      loading: false,

      /* 当前展开详情的行 ID，null 表示未展开 */
      expanded: null,
    };
  },

  created() { this.search(1); },

  methods: {
    /**
     * 执行搜索并刷新列表
     * @param {number} [page] - 目标页码，默认为 1
     */
    async search(page) {
      this.loading = true;
      try {
        const r = await XApi.queryLogs({
          publicModel: this.filter.customerName,
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

    /** 重置筛选条件并重新搜索第 1 页 */
    reset() {
      this.filter = { customerName: '', dateFrom: '', dateTo: '', status: '' };
      this.search(1);
    },

    /** 切换指定日志行的详情展开/折叠 */
    toggleDetail(log) {
      this.expanded = this.expanded === log.id ? null : log.id;
    },

    /**
     * 解析请求体 messages 数组（兼容历史数据）
     * @param {object} log - CallLog 对象
     * @returns {Array<{role:string, content:string}>} 消息列表
     */
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

    /**
     * 解析轻量摘要（新格式 request_body 为摘要 JSON）
     * @param {object} log - CallLog 对象
     * @returns {object|null} 摘要对象或 null
     */
    requestSummary(log) {
      if (!log.request_body) return null;
      try {
        const body = JSON.parse(log.request_body);
        return (body && (body.type === 'chat' || body.type === 'embedding')) ? body : null;
      } catch (e) {
        return null;
      }
    },

    /**
     * 渲染角色分布文案
     * @param {object} summary - requestSummary 返回值
     * @returns {string} 如 "System ×1 · User ×2 · AI ×1"
     */
    summaryRolesText(summary) {
      if (!summary || !summary.roles) return '';
      const roleNames = { system: 'System', user: 'User', assistant: 'AI', tool: 'Tool' };
      const parts = Object.keys(summary.roles).map(r => {
        const label = roleNames[r] || r;
        return label + ' ×' + summary.roles[r];
      });
      return parts.join(' · ');
    },

    /** 角色显示标签映射 */
    roleLabel(role) {
      return { system: 'System', user: 'User', assistant: 'AI', tool: 'Tool' }[role] || role;
    },

    /** 角色 CSS class 映射 */
    roleClass(role) {
      return {
        system: 'role-system', user: 'role-user', assistant: 'role-ai', tool: 'role-tool',
      }[role] || '';
    },

    /**
     * 格式化 JSON 字符串（带缩进）
     * @param {string} json - JSON 字符串
     * @returns {string} 格式化后的字符串
     */
    prettyJson(json) {
      if (!json) return '';
      try { return JSON.stringify(JSON.parse(json), null, 2); } catch (e) { return json; }
    },
  },
};

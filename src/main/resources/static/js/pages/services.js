/* ============================================================
 * 模型服务页面逻辑（真实接入的上游大模型）
 * ============================================================ */

const ServicesApp = {
  mixins: [XGateMixin],
  data() {
    return {
      providers: [],
      providersLoading: false,
      providerModal: {
        open: false, editId: null, saving: false,
        form: { name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, remark: '' },
      },
    };
  },
  created() {
    this.loadProviders();
  },
  methods: {
    /* ---------------- 列表 ---------------- */
    async loadProviders() {
      this.providersLoading = true;
      try {
        this.providers = await this.request('/admin/providers') || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.providersLoading = false;
      }
    },

    /* ---------------- 弹窗 ---------------- */
    openProviderModal(p) {
      this.providerModal.saving = false;
      if (p) {
        this.providerModal.editId = p.id;
        this.providerModal.form = {
          name: p.name, baseUrl: p.baseUrl, apiKey: '', // 编辑不回显 Key，留空 = 不修改
          modelName: p.modelName, enabled: !!p.enabled, remark: p.remark || '',
        };
      } else {
        this.providerModal.editId = null;
        this.providerModal.form = { name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, remark: '' };
      }
      this.providerModal.open = true;
    },
    closeProviderModal() {
      if (this.providerModal.saving) return;
      this.providerModal.open = false;
    },

    async saveProvider() {
      const f = this.providerModal.form;
      if (!f.name) { this.message('请输入名称', 'warn'); return; }
      if (!f.baseUrl) { this.message('请输入 Base URL', 'warn'); return; }
      if (!f.modelName) { this.message('请输入模型名', 'warn'); return; }
      const editing = !!this.providerModal.editId;
      if (!editing && !f.apiKey) { this.message('请填写 API Key', 'warn'); return; }
      const body = {
        name: f.name, baseUrl: f.baseUrl, apiKey: editing ? (f.apiKey || '') : f.apiKey,
        modelName: f.modelName, enabled: f.enabled ? 1 : 0, remark: f.remark || '',
      };
      if (editing) body.id = this.providerModal.editId;
      this.providerModal.saving = true;
      try {
        await this.request('/admin/provider', { method: editing ? 'PUT' : 'POST', body });
        this.message(editing ? '模型服务已更新' : '模型服务新增成功');
        this.providerModal.open = false;
        await this.loadProviders();
      } catch (e) {
        this.toastError(e);
      } finally {
        this.providerModal.saving = false;
      }
    },

    /* ---------------- 启停 ---------------- */
    async toggleProvider(p) {
      const prev = p.enabled;
      p.enabled = prev ? 0 : 1;
      const body = { name: p.name, baseUrl: p.baseUrl, apiKey: '', modelName: p.modelName, enabled: p.enabled, remark: p.remark || '' };
      try {
        await this.request('/admin/provider', { method: 'PUT', body: { ...body, id: p.id } });
        this.message(p.enabled ? '模型服务已启用' : '模型服务已停用');
      } catch (e) {
        p.enabled = prev;
        this.toastError(e);
      }
    },

    /* ---------------- 连通测试 ---------------- */
    async testProvider(p) {
      this.message(`正在测试「${p.name}」连通性…`, 'info');
      try {
        const r = await this.request('/admin/provider/' + p.id + '/test', { method: 'POST' });
        if (r && r.ok) {
          this.message(`「${p.name}」连通正常 · 耗时 ${r.latencyMs != null ? r.latencyMs : '-'} ms`);
        } else {
          this.message(`「${p.name}」连通失败：${(r && r.message) || '未知原因'}`, 'error');
        }
      } catch (e) {
        this.toastError(e);
      }
    },

    async delProvider(p) {
      if (!confirm(`确定删除模型服务「${p.name}」吗？`)) return;
      try {
        await this.request('/admin/provider/' + p.id, { method: 'DELETE' });
        this.message('模型服务已删除');
        await this.loadProviders();
      } catch (e) {
        this.toastError(e);
      }
    },
  },
};

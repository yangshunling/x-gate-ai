/* ============================================================
 * 模型服务页面（真实接入的上游大模型）
 * ============================================================ */

const ServicesApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      providers: [],
      loading: false,
      modal: {
        open: false, editId: null, saving: false,
        form: { name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, remark: '' },
      },
    };
  },

  created() { this.load(); },

  methods: {
    /* ---------------- 列表 ---------------- */
    async load() {
      this.loading = true;
      try {
        this.providers = await XApi.listProviders() || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.loading = false;
      }
    },

    /* ---------------- 弹窗 ---------------- */
    openModal(p) {
      this.modal.saving = false;
      if (p) {
        this.modal.editId = p.id;
        this.modal.form = {
          name: p.name, baseUrl: p.baseUrl, apiKey: '', // 编辑不回显 Key，留空 = 不修改
          modelName: p.modelName, enabled: !!p.enabled, remark: p.remark || '',
        };
      } else {
        this.modal.editId = null;
        this.modal.form = { name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, remark: '' };
      }
      this.modal.open = true;
    },
    closeModal() {
      if (this.modal.saving) return;
      this.modal.open = false;
    },

    async save() {
      const f = this.modal.form;
      if (!f.name) { this.message('请输入名称', 'warn'); return; }
      if (!f.baseUrl) { this.message('请输入 Base URL', 'warn'); return; }
      if (!f.modelName) { this.message('请输入模型名', 'warn'); return; }
      const editing = !!this.modal.editId;
      if (!editing && !f.apiKey) { this.message('请填写 API Key', 'warn'); return; }
      const body = {
        name: f.name, baseUrl: f.baseUrl, apiKey: editing ? (f.apiKey || '') : f.apiKey,
        modelName: f.modelName, enabled: f.enabled ? 1 : 0, remark: f.remark || '',
      };
      if (editing) body.id = this.modal.editId;
      this.modal.saving = true;
      try {
        await XApi.saveProvider(body, editing);
        this.message(editing ? '模型服务已更新' : '模型服务新增成功');
        this.modal.open = false;
        await this.load();
      } catch (e) {
        this.toastError(e);
      } finally {
        this.modal.saving = false;
      }
    },

    /* ---------------- 启停 ---------------- */
    async toggle(p) {
      const prev = p.enabled;
      p.enabled = prev ? 0 : 1;
      const body = { name: p.name, baseUrl: p.baseUrl, apiKey: '', modelName: p.modelName, enabled: p.enabled, remark: p.remark || '' };
      try {
        await XApi.saveProvider({ ...body, id: p.id }, true);
        this.message(p.enabled ? '模型服务已启用' : '模型服务已停用');
      } catch (e) {
        p.enabled = prev;
        this.toastError(e);
      }
    },

    /* ---------------- 连通测试 ---------------- */
    async test(p) {
      this.message(`正在测试「${p.name}」连通性…`, 'info');
      try {
        const r = await XApi.testProvider(p.id);
        if (r && r.ok) {
          this.message(`「${p.name}」连通正常 · 耗时 ${r.latencyMs != null ? r.latencyMs : '-'} ms`);
        } else {
          this.message(`「${p.name}」连通失败：${(r && r.message) || '未知原因'}`, 'error');
        }
      } catch (e) {
        this.toastError(e);
      }
    },

    async remove(p) {
      if (!confirm(`确定删除模型服务「${p.name}」吗？`)) return;
      try {
        await XApi.deleteProvider(p.id);
        this.message('模型服务已删除');
        await this.load();
      } catch (e) {
        this.toastError(e);
      }
    },
  },
};

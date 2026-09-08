/* ============================================================
 * 渠道管理页面（真实接入的上游大模型）
 * ============================================================ */

const ServicesApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      providers: [],
      loading: false,
      testingAll: false,
      connMap: {},  // { id: { ok, latencyMs, message } }
      modal: {
        open: false, editId: null, saving: false,
        form: { name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, weight: 100, remark: '' },
      },
    };
  },

  created() { this.load(); },

  methods: {
    /* ---------------- 全局 / 单个测试 ---------------- */
    async testAll() {
      this.testingAll = true;
      try {
        const results = await XApi.testAllProviders();
        if (Array.isArray(results)) {
          this.connMap = {};
          for (const r of results) this.connMap[r.id] = { ok: r.ok, latencyMs: r.latencyMs, message: r.message };
          if (results.length === 0) {
            this.message('暂无渠道可测试', 'warn');
          } else {
            this.message(`已完成 ${results.length} 个渠道的全局连通测试`);
          }
        }
      } catch (e) {
        this.toastError(e);
      } finally {
        this.testingAll = false;
      }
    },
    async testOne(p) {
      try {
        const r = await XApi.testProvider(p.id);
        this.connMap[p.id] = { ok: r.ok, latencyMs: r.latencyMs, message: r.message };
        this.message(`${p.name}：${r.ok ? '已连通' : '未连通'} · ${r.latencyMs != null ? r.latencyMs + 'ms' : ''}`, r.ok ? 'success' : 'error');
      } catch (e) {
        this.connMap[p.id] = { ok: false, message: e.message };
        this.toastError(e);
      }
    },

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
          modelName: p.modelName, enabled: !!p.enabled, weight: p.weight || 100, remark: p.remark || '',
        };
      } else {
        this.modal.editId = null;
        this.modal.form = { name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, weight: 100, remark: '' };
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
          modelName: f.modelName, enabled: f.enabled ? 1 : 0, weight: f.weight || 100, remark: f.remark || '',
      };
      if (editing) body.id = this.modal.editId;
      this.modal.saving = true;
      try {
        await XApi.saveProvider(body, editing);
        this.message(editing ? '渠道已更新' : '渠道新增成功');
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
      const body = { name: p.name, baseUrl: p.baseUrl, apiKey: '', modelName: p.modelName, enabled: p.enabled, weight: p.weight || 100, remark: p.remark || '' };
      try {
        await XApi.saveProvider({ ...body, id: p.id }, true);
        this.message(p.enabled ? '渠道已启用' : '渠道已停用');
      } catch (e) {
        p.enabled = prev;
        this.toastError(e);
      }
    },

    /* ---------------- 连通测试（行内「测试」链接，复用 testOne） ---------------- */

    async remove(p) {
      if (!confirm(`确定删除渠道「${p.name}」吗？`)) return;
      try {
        await XApi.deleteProvider(p.id);
        this.message('渠道已删除');
        await this.load();
      } catch (e) {
        this.toastError(e);
      }
    },
  },
};

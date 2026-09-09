/* ============================================================
 * 渠道管理页面（渠道账号 + 其下挂载的模型，两级结构）
 * ============================================================ */

/**
 * ServicesApp 渠道管理页面实例
 * 数据模型：一个渠道(x_gate_channel)下挂多个模型(x_gate_model，一个模型一行)。
 */
const ServicesApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      providers: [],
      loading: false,
      importing: false,
      connMap: {},           // { modelId: { ok, latencyMs, message } }
      expandedIds: new Set(),
      modal: {
        open: false, editId: null, saving: false,
        form: { name: '', baseUrl: '', apiKey: '', enabled: true, remark: '', models: [] },
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

    /** 展开/折叠渠道行 */
    toggleExpand(p) {
      if (this.expandedIds.has(p.id)) {
        this.expandedIds.delete(p.id);
      } else {
        this.expandedIds.add(p.id);
      }
    },

    /** 该渠道下的模型名数组（展示用） */
    modelNames(p) {
      return (p.models || []).map(m => m.modelName);
    },

    enabledModelCount(p) {
      return (p.models || []).filter(m => m.enabled).length;
    },

    /* ---------------- 连通性测试 ---------------- */

    /** 测试单个渠道下全部模型 */
    async testChannel(p) {
      try {
        const results = await XApi.testProvider(p.id) || [];
        results.forEach(r => {
          this.connMap[r.modelId] = { ok: r.ok, latencyMs: r.latencyMs, message: r.message };
        });
        const okCount = results.filter(r => r.ok).length;
        this.message(`渠道「${p.name}」：${okCount}/${results.length} 个模型连通`, okCount === results.length ? 'success' : 'warn');
      } catch (e) {
        this.toastError(e);
      }
    },

    /** 测试单个模型行 */
    async testModel(m) {
      try {
        const r = await XApi.testModel(m.id);
        this.connMap[m.id] = { ok: r.ok, latencyMs: r.latencyMs, message: r.message };
        const suffix = r.latencyMs != null ? ` · ${r.latencyMs}ms` : '';
        this.message(`模型 ${m.modelName}：${r.ok ? '已连通' + suffix : '未连通'}`, r.ok ? 'success' : 'error');
      } catch (e) {
        this.connMap[m.id] = { ok: false, message: e.message };
        this.toastError(e);
      }
    },

    /* ---------------- 弹窗 ---------------- */

    /** 打开新增/编辑弹窗 */
    openModal(p) {
      this.modal.saving = false;
      if (p) {
        this.modal.editId = p.id;
        this.modal.form = {
          name: p.name,
          baseUrl: p.baseUrl,
          apiKey: p.apiKey || '', // 明文回显
          enabled: !!p.enabled,
          remark: p.remark || '',
          models: (p.models || []).map(m => ({ modelName: m.modelName, enabled: !!m.enabled, remark: m.remark || '' })),
        };
      } else {
        this.modal.editId = null;
        this.modal.form = {
          name: '', baseUrl: '', apiKey: '', enabled: true, remark: '',
          models: [{ modelName: '', enabled: true, remark: '' }],
        };
      }
      this.modal.open = true;
    },

    closeModal() {
      if (this.modal.saving) return;
      this.modal.open = false;
    },

    /** 弹窗内动态添加一条模型行 */
    addModelRow() {
      this.modal.form.models.push({ modelName: '', enabled: true, remark: '' });
    },

    removeModelRow(index) {
      this.modal.form.models.splice(index, 1);
    },

    /**
     * 一键导入：用表单的 Base URL + API Key 探测上游可用模型并回填到模型行
     * 编辑态 API Key 留空时后端会复用渠道已保存的 Key。
     */
    async fetchModels() {
      const f = this.modal.form;
      if (!f.baseUrl) { this.message('请先填写 Base URL', 'warn'); return; }
      if (!this.modal.editId && !f.apiKey) { this.message('请先填写 API Key', 'warn'); return; }
      this.importing = true;
      try {
        const r = await XApi.fetchProviderModels({
          id: this.modal.editId || null,
          baseUrl: f.baseUrl,
          apiKey: f.apiKey || '',
        });
        const list = r.models || [];
        if (list.length === 0) {
          this.message('上游未返回模型，请检查 Base URL / API Key', 'warn');
          return;
        }
        const existing = new Set();
        this.modal.form.models.forEach(row => {
          const n = (row.modelName || '').trim();
          if (n) existing.add(n);
        });
        let added = 0;
        list.forEach(name => {
          const n = (name || '').trim();
          if (n && !existing.has(n)) {
            existing.add(n);
            this.modal.form.models.push({ modelName: n, enabled: true, remark: '' });
            added++;
          }
        });
        const prefix = r.usedStoredKey ? '已用渠道保存的 Key 探测。' : '';
        this.message(`${prefix}探测到 ${list.length} 个模型，回填新增 ${added} 个`);
      } catch (e) {
        this.toastError(e);
      } finally {
        this.importing = false;
      }
    },

    /** 保存渠道（新增 POST / 编辑 PUT），含模型列表 */
    async save() {
      const f = this.modal.form;
      if (!f.name) { this.message('请输入渠道名称', 'warn'); return; }
      if (!f.baseUrl) { this.message('请输入 Base URL', 'warn'); return; }
      const editing = !!this.modal.editId;
      if (!editing && !f.apiKey) { this.message('请填写 API Key', 'warn'); return; }

      // 模型行校验：去空白、去空、查重
      const models = [];
      const seen = {};
      for (const row of f.models) {
        const name = (row.modelName || '').trim();
        if (!name) { this.message('存在空白的模型名，请填写或删除该行', 'warn'); return; }
        if (name.indexOf(',') >= 0) { this.message('一个模型单独一行，模型名不能包含逗号：' + name, 'warn'); return; }
        if (seen[name]) { this.message('同一渠道下模型重复：' + name, 'warn'); return; }
        seen[name] = true;
        models.push({ modelName: name, enabled: row.enabled ? 1 : 0, remark: (row.remark || '').trim() });
      }
      if (models.length === 0) { this.message('请至少添加一个模型', 'warn'); return; }

      const body = {
        name: f.name,
        baseUrl: f.baseUrl,
        apiKey: editing ? (f.apiKey || '') : f.apiKey,
        enabled: f.enabled ? 1 : 0,
        remark: f.remark || '',
        models,
      };
      if (editing) body.id = this.modal.editId;

      this.modal.saving = true;
      try {
        await XApi.saveProvider(body, editing);
        this.message(editing ? '渠道已更新' : '渠道新增成功');
        this.modal.open = false;
        this.connMap = {};
        await this.load();
      } catch (e) {
        this.toastError(e);
      } finally {
        this.modal.saving = false;
      }
    },

    /* ---------------- 快捷操作 ---------------- */

    /** 渠道级启用/停用：整体覆盖其下模型集合不变 */
    async toggleChannel(p) {
      const prev = p.enabled;
      p.enabled = prev ? 0 : 1;
      try {
        await this.commitChannel(p, { enabled: p.enabled });
        this.message(p.enabled ? '渠道已启用' : '渠道已停用');
      } catch (e) {
        p.enabled = prev;
        this.toastError(e);
      }
    },

    /** 模型级启用/停用 */
    async toggleModel(m) {
      const prev = m.enabled;
      m.enabled = prev ? 0 : 1;
      try {
        const p = this.findChannelByModel(m);
        if (!p) return;
        await this.commitChannel(p, {});
        this.message(m.enabled ? `模型 ${m.modelName} 已启用` : `模型 ${m.modelName} 已停用`);
      } catch (e) {
        m.enabled = prev;
        this.toastError(e);
      }
    },

    /** 删除渠道（级联删除其下全部模型） */
    async remove(p) {
      const count = (p.models || []).length;
      const tip = count > 0 ? `，其下 ${count} 个模型将一并删除` : '';
      if (!confirm(`确定删除渠道「${p.name}」吗${tip}？`)) return;
      try {
        await XApi.deleteProvider(p.id);
        this.message('渠道已删除');
        this.connMap = {};
        await this.load();
      } catch (e) {
        this.toastError(e);
      }
    },

    /** 从渠道下移除单个模型（提交差量：删除该模型行） */
    async removeModel(m) {
      const p = this.findChannelByModel(m);
      if (!p) return;
      if (!confirm(`确定从渠道「${p.name}」移除模型「${m.modelName}」吗？`)) return;
      p.models = p.models.filter(x => x.id !== m.id);
      try {
        await this.commitChannel(p, {});
        this.message('模型已移除');
        this.connMap = {};
        await this.load();
      } catch (e) {
        await this.load();
        this.toastError(e);
      }
    },

    /* ---------------- 内部辅助 ---------------- */

    /** 根据模型行反查所属渠道 */
    findChannelByModel(m) {
      return this.providers.find(p => (p.models || []).some(x => x.id === m.id));
    },

    /**
     * 以当前渠道数据为基准做一次全量 PUT（apiKey 留空不修改）
     * @param {object} p - 渠道分组对象
     * @param {object} overrides - 覆盖字段
     */
    async commitChannel(p, overrides) {
      const body = {
        id: p.id,
        name: overrides.name != null ? overrides.name : p.name,
        baseUrl: p.baseUrl,
        apiKey: '',                       // 编辑保留原密钥
        enabled: overrides.enabled != null ? overrides.enabled : p.enabled,
        remark: p.remark || '',
        models: (p.models || []).map(m => ({ modelName: m.modelName, enabled: m.enabled ? 1 : 0, remark: m.remark || '' })),
      };
      await XApi.saveProvider(body, true);
    },
  },
};

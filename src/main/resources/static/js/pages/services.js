/* ============================================================
 * 渠道管理页面（渠道账号 + 其下挂载的模型，两级结构）
 * ============================================================ */

const ServicesApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      providers: [],
      loading: false,
      importing: false,
      connMap: {},
      testingChannels: {},
      expandedIds: new Set(),
      modal: {
        open: false, editId: null, saving: false,
        form: { name: '', baseUrl: '', apiKey: '', enabled: true, remark: '', models: [] },
      },
    };
  },
  created() { this.load(); },
  methods: {
    async load() {
      this.loading = true;
      this.providers = await this.asyncLoad(() => XApi.listProviders()) || [];
      this.loading = false;
    },

    toggleExpand(p) {
      if (this.expandedIds.has(p.id)) this.expandedIds.delete(p.id);
      else this.expandedIds.add(p.id);
    },
    modelNames(p) { return (p.models || []).map(m => m.modelName); },

    async testChannel(p) {
      const models = p.models || [];
      if (models.length === 0) { this.message(`渠道「${p.name}」无模型`, 'warn'); return; }
      this.testingChannels[p.id] = true;
      models.forEach(m => { this.connMap[m.id] = { testing: true }; });
      let okCount = 0;
      await Promise.allSettled(models.map(m =>
        XApi.testModel(m.id).then(r => {
          this.connMap[m.id] = { ok: r.ok, latencyMs: r.latencyMs, message: r.message };
          if (r.ok) okCount++;
        }).catch(e => {
          this.connMap[m.id] = { ok: false, message: e.message };
        })
      ));
      delete this.testingChannels[p.id];
      this.message(`渠道「${p.name}」：${okCount}/${models.length} 个模型连通`, okCount === models.length ? 'success' : 'warn');
    },

    async testModel(m) {
      this.connMap[m.id] = { testing: true };
      const r = await this.asyncLoad(() => XApi.testModel(m.id));
      if (r) {
        this.connMap[m.id] = { ok: r.ok, latencyMs: r.latencyMs, message: r.message };
        const suffix = r.latencyMs != null ? ` · ${r.latencyMs}ms` : '';
        this.message(`模型 ${m.modelName}：${r.ok ? '已连通' + suffix : '未连通'}`, r.ok ? 'success' : 'error');
      } else {
        this.connMap[m.id] = { ok: false, message: '测试失败' };
      }
    },

    openModal(p) {
      this.modal.saving = false;
      if (p) {
        this.modal.editId = p.id;
        this.modal.form = {
          name: p.name, baseUrl: p.baseUrl,
          apiKey: p.apiKey || '', enabled: !!p.enabled, remark: p.remark || '',
          models: (p.models || []).map(m => ({ modelName: m.modelName, enabled: !!m.enabled, remark: m.remark || '' })),
        };
      } else {
        this.modal.editId = null;
        this.modal.form = { name: '', baseUrl: '', apiKey: '', enabled: true, remark: '', models: [{ modelName: '', enabled: true, remark: '' }] };
      }
      this.modal.open = true;
    },
    closeModal() { if (!this.modal.saving) this.modal.open = false; },
    addModelRow() { this.modal.form.models.push({ modelName: '', enabled: true, remark: '' }); },
    removeModelRow(index) { this.modal.form.models.splice(index, 1); },

    async fetchModels() {
      const f = this.modal.form;
      if (!f.baseUrl) { this.message('请先填写 Base URL', 'warn'); return; }
      if (!this.modal.editId && !f.apiKey) { this.message('请先填写 API Key', 'warn'); return; }
      this.importing = true;
      const r = await this.asyncLoad(() => XApi.fetchProviderModels({ id: this.modal.editId || null, baseUrl: f.baseUrl, apiKey: f.apiKey || '' }));
      if (r) {
        const list = r.models || [];
        if (list.length === 0) { this.message('上游未返回模型，请检查 Base URL / API Key', 'warn'); }
        else {
          const existing = new Set();
          this.modal.form.models.forEach(row => { const n = (row.modelName || '').trim(); if (n) existing.add(n); });
          let added = 0;
          list.forEach(name => {
            const n = (name || '').trim();
            if (n && !existing.has(n)) { existing.add(n); this.modal.form.models.push({ modelName: n, enabled: true, remark: '' }); added++; }
          });
          const prefix = r.usedStoredKey ? '已用渠道保存的 Key 探测。' : '';
          this.message(`${prefix}探测到 ${list.length} 个模型，回填新增 ${added} 个`);
        }
      }
      this.importing = false;
    },

    async save() {
      const f = this.modal.form;
      if (!f.name) { this.message('请输入渠道名称', 'warn'); return; }
      if (!f.baseUrl) { this.message('请输入 Base URL', 'warn'); return; }
      const editing = !!this.modal.editId;
      if (!editing && !f.apiKey) { this.message('请填写 API Key', 'warn'); return; }

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

      const body = { name: f.name, baseUrl: f.baseUrl, apiKey: editing ? (f.apiKey || '') : f.apiKey, enabled: f.enabled ? 1 : 0, remark: f.remark || '', models };
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

    async toggleChannel(p) {
      await this.toggleState(p, 'enabled', () => this.commitChannel(p, { enabled: p.enabled }), { on: '渠道已启用', off: '渠道已停用' });
    },

    async toggleModel(m) {
      const p = this.findChannelByModel(m);
      if (!p) return;
      await this.toggleState(m, 'enabled', () => this.commitChannel(p, {}), { on: `模型 ${m.modelName} 已启用`, off: `模型 ${m.modelName} 已停用` });
    },

    async remove(p) {
      const count = (p.models || []).length;
      const tip = count > 0 ? `，其下 ${count} 个模型将一并删除` : '';
      if (await this.confirmThen(`确定删除渠道「${p.name}」吗${tip}？`, () => XApi.deleteProvider(p.id), '渠道已删除')) {
        this.connMap = {};
      }
    },

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

    findChannelByModel(m) {
      return this.providers.find(p => (p.models || []).some(x => x.id === m.id));
    },

    async commitChannel(p, overrides) {
      const body = {
        id: p.id,
        name: overrides.name != null ? overrides.name : p.name,
        baseUrl: p.baseUrl,
        apiKey: '',
        enabled: overrides.enabled != null ? overrides.enabled : p.enabled,
        remark: p.remark || '',
        models: (p.models || []).map(m => ({ modelName: m.modelName, enabled: m.enabled ? 1 : 0, remark: m.remark || '' })),
      };
      await XApi.saveProvider(body, true);
    },
  },
};

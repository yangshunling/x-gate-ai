/* ============================================================
 * API KEY 页面（对外客户接入）
 * ============================================================ */

const ModelsApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      channels: [],
      modelOptions: [],
      loading: false,
      modal: { open: false, editId: null, saving: false, form: { customerName: '', modelName: '', enabled: true, remark: '' } },
      keyResult: { open: false, key: '', serviceName: '', copying: false },
      baseURL: window.location.origin + '/v1',
    };
  },
  created() {
    this.initBaseURL();
    this.load();
  },
  methods: {
    async load() {
      this.loading = true;
      try {
        const [keys, providers] = await Promise.all([XApi.listKeys(), XApi.listProviders()]);
        this.channels = keys || [];
        this.modelOptions = this.collectModels(providers || []);
      } catch (e) {
        this.toastError(e);
      } finally {
        this.loading = false;
      }
    },

    collectModels(providers) {
      const set = [];
      const seen = {};
      (providers || []).forEach(p => {
        if (p.enabled !== 1) return;
        (p.models || []).forEach(m => {
          if (m.enabled !== 1 || !m.modelName) return;
          const name = m.modelName.trim();
          if (name && !seen[name]) { seen[name] = true; set.push(name); }
        });
      });
      return set;
    },

    openModal(c) {
      this.modal.saving = false;
      if (c) {
        this.modal.editId = c.id;
        this.modal.form = { customerName: c.public_model_name, modelName: c.model_name || '', enabled: !!c.enabled, remark: c.remark || '' };
      } else {
        this.modal.editId = null;
        this.modal.form = { customerName: '', modelName: '', enabled: true, remark: '' };
      }
      this.modal.open = true;
    },
    closeModal() { if (!this.modal.saving) this.modal.open = false; },

    async save() {
      const f = this.modal.form;
      if (!f.customerName) { this.message('请输入客户名', 'warn'); return; }
      const body = { publicModelName: f.customerName, modelName: f.modelName || '', enabled: f.enabled ? 1 : 0, remark: f.remark || '' };
      if (this.modal.editId) body.id = this.modal.editId;
      this.modal.saving = true;
      try {
        const result = await XApi.saveKey(body, !!this.modal.editId);
        this.message(this.modal.editId ? 'API Key 已更新' : 'API Key 创建成功');
        this.modal.open = false;
        if (!this.modal.editId && result) {
          this.keyResult = { open: true, key: result, serviceName: body.publicModelName, copying: false };
        }
        await this.load();
      } catch (e) {
        this.toastError(e);
      } finally {
        this.modal.saving = false;
      }
    },

    async remove(c) {
      await this.confirmThen(
        `确定删除客户「${c.public_model_name}」的 API Key 吗？删除后使用该 Key 的客户端将立即无法访问。`,
        () => XApi.deleteKey(c.id),
        'API Key 已删除'
      );
    },

    async toggleEnabled(c, evt) {
      evt.target.disabled = true;
      const prev = c.enabled;
      c.enabled = evt.target.checked ? 1 : 0;
      try {
        await XApi.updateKey({ id: c.id, publicModelName: c.public_model_name, modelName: c.model_name || '', enabled: c.enabled, remark: c.remark || '' });
        this.message(c.enabled ? '已启用' : '已停用');
      } catch (e) {
        c.enabled = prev;
        evt.target.checked = prev;
        this.toastError(e);
      } finally {
        evt.target.disabled = false;
      }
    },

    closeKeyResult() {
      this.keyResult.open = false;
      this.keyResult.key = '';
    },
    copyKey() { this.copyText(this.keyResult.key, 'Key 已复制'); },
    copyListKey(c, text) { this.copyText(text || c.api_key, 'Key 已复制'); },
  },
};

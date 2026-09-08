/* ============================================================
 * API Key 页面（对外客户接入）
 * ============================================================ */

const ModelsApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      channels: [],
      modelOptions: [],
      loading: false,

      modal: {
        open: false, editId: null, saving: false,
        form: { customerName: '', modelName: '', enabled: true, remark: '' },
      },

      keyResult: { open: false, key: '', serviceName: '', copying: false },
      baseURL: window.location.origin + '/v1',
    };
  },

  created() {
    this.initBaseURL();
    this.load();
  },

  methods: {
    async initBaseURL() {
      try {
        const info = await XApi.serverInfo();
        if (info && info.host && info.port) {
          this.baseURL = `${window.location.protocol}//${info.host}:${info.port}/v1`;
        }
      } catch (e) {
        // 回退到 window.location.origin
      }
    },

    /* ---------------- 列表 ---------------- */
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

    /* 汇总池内渠道提供的去重模型名（仅启用渠道），default 之外的选项 */
    collectModels(providers) {
      const set = [];
      const seen = {};
      (providers || []).forEach(p => {
        if (p.enabled !== 1 || !p.modelName) return;
        if (!seen[p.modelName]) {
          seen[p.modelName] = true;
          set.push(p.modelName);
        }
      });
      return set;
    },

    /* ---------------- 弹窗 ---------------- */
    openModal(c) {
      this.modal.saving = false;
      if (c) {
        this.modal.editId = c.id;
        this.modal.form = {
          customerName: c.public_model_name,
          modelName: c.model_name || '',
          enabled: !!c.enabled,
          remark: c.remark || '',
        };
      } else {
        this.modal.editId = null;
        this.modal.form = { customerName: '', modelName: '', enabled: true, remark: '' };
      }
      this.modal.open = true;
    },
    closeModal() {
      if (this.modal.saving) return;
      this.modal.open = false;
    },

    async save() {
      const f = this.modal.form;
      if (!f.customerName) { this.message('请输入客户名', 'warn'); return; }
      const body = {
        publicModelName: f.customerName,
        modelName: f.modelName || '',
        enabled: f.enabled ? 1 : 0,
        remark: f.remark || '',
      };
      if (this.modal.editId) body.id = this.modal.editId;
      this.modal.saving = true;
      try {
        const result = await XApi.saveKey(body, !!this.modal.editId);
        this.message(this.modal.editId ? 'API Key 已更新' : 'API Key 创建成功');
        this.modal.open = false;
        // 新增时后端返回自动生成的专属 Key，仅展示这一次
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
      if (!confirm(`确定删除客户「${c.public_model_name}」的 API Key 吗？删除后使用该 Key 的客户端将立即无法访问。`)) return;
      try {
        await XApi.deleteKey(c.id);
        this.message('API Key 已删除');
        await this.load();
      } catch (e) {
        this.toastError(e);
      }
    },

    async toggleEnabled(c, evt) {
      const enabled = evt.target.checked ? 1 : 0;
      evt.target.disabled = true;
      try {
        await XApi.updateKey({ id: c.id, publicModelName: c.public_model_name, modelName: c.model_name || '', enabled, remark: c.remark || '' });
        c.enabled = enabled;
        this.message(enabled ? '已启用' : '已停用');
      } catch (e) {
        evt.target.checked = !enabled;
        this.toastError(e);
      } finally {
        evt.target.disabled = false;
      }
    },

    /* ---------------- 专属 Key 展示与复制 ---------------- */
    closeKeyResult() {
      this.keyResult.open = false;
      this.keyResult.key = '';
    },
    async copyText(text) {
      let done = false;
      if (navigator.clipboard && window.isSecureContext) {
        try { await navigator.clipboard.writeText(text); done = true; } catch (e) { /* fallback */ }
      }
      if (!done) {
        try {
          const ta = document.createElement('textarea');
          ta.value = text;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.focus();
          ta.select();
          done = document.execCommand('copy');
          document.body.removeChild(ta);
        } catch (e) { done = false; }
      }
      this.message(done ? 'Key 已复制' : '复制失败，请手动复制', done ? 'success' : 'error');
    },
    copyKey() { this.copyText(this.keyResult.key); },
    copyListKey(c, text) { this.copyText(text || c.api_key); },
  },
};

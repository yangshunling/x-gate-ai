/* ============================================================
 * API Key 页面（对客服务）
 * ============================================================ */

const ModelsApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      channels: [],
      loading: false,
      enabledProviders: [],

      modal: {
        open: false, editId: null, saving: false,
        form: { publicModelName: '', enabled: true, remark: '', strategy: 'ROUND_ROBIN' },
        selectedIds: [],
      },

      keyResult: { open: false, key: '', serviceName: '', copying: false },
      baseURL: window.location.origin + '/v1',
    };
  },

  created() {
    this.load();
    this.loadEnabledProviders();
  },

  methods: {
    /* ---------------- 列表 ---------------- */
    async load() {
      this.loading = true;
      try {
        this.channels = await XApi.listKeys() || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.loading = false;
      }
    },

    async loadEnabledProviders() {
      try {
        this.enabledProviders = await XApi.listEnabledProviders() || [];
      } catch (e) {
        this.toastError(e);
      }
    },

    /* ---------------- 弹窗 ---------------- */
    openModal(c) {
      this.modal.saving = false;
      if (c) {
        this.modal.editId = c.id;
        this.modal.form = {
          publicModelName: c.public_model_name,
          enabled: !!c.enabled,
          remark: c.remark || '',
          strategy: c.strategy || 'ROUND_ROBIN',
        };
        this.modal.selectedIds = (c.provider_ids || []).slice();
      } else {
        this.modal.editId = null;
        this.modal.form = { publicModelName: '', enabled: true, remark: '', strategy: 'ROUND_ROBIN' };
        this.modal.selectedIds = [];
      }
      this.modal.open = true;
    },
    closeModal() {
      if (this.modal.saving) return;
      this.modal.open = false;
    },

    async save() {
      const f = this.modal.form;
      if (!f.publicModelName) { this.message('请输入模型名', 'warn'); return; }
      if (!this.modal.selectedIds.length) { this.message('请至少绑定一个启用的模型服务', 'warn'); return; }
      const body = {
        publicModelName: f.publicModelName,
        enabled: f.enabled ? 1 : 0,
        strategy: 'ROUND_ROBIN',
        remark: f.remark || '',
        providerIds: this.modal.selectedIds.slice(),
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
      if (!confirm(`确定删除 API Key「${c.public_model_name}」吗？删除后使用该 Key 的客户端将立即无法访问。`)) return;
      try {
        await XApi.deleteKey(c.id);
        this.message('API Key 已删除');
        await this.load();
      } catch (e) {
        this.toastError(e);
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
    copyListKey(c) { this.copyText(c.api_key); },
  },
};

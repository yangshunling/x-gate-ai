/* ============================================================
 * 对客服务页面逻辑（对外暴露的模型通道）
 * ============================================================ */

const ModelsApp = {
  mixins: [XGateMixin],
  data() {
    return {
      channels: [],
      channelsLoading: false,
      enabledProviders: [],
      channelModal: {
        open: false, editId: null, saving: false,
        form: { publicModelName: '', enabled: true, remark: '', strategy: 'ROUND_ROBIN' },
        selectedIds: [],
      },
    };
  },
  created() {
    this.loadChannels();
    this.loadEnabledProviders();
  },
  methods: {
    /* ---------------- 列表 ---------------- */
    async loadChannels() {
      this.channelsLoading = true;
      try {
        this.channels = await this.request('/admin/channels') || [];
      } catch (e) {
        this.toastError(e);
      } finally {
        this.channelsLoading = false;
      }
    },

    async loadEnabledProviders() {
      try {
        this.enabledProviders = await this.request('/admin/providers/enabled') || [];
      } catch (e) {
        this.toastError(e);
      }
    },

    /* ---------------- 弹窗 ---------------- */
    openChannelModal(c) {
      this.channelModal.saving = false;
      if (c) {
        this.channelModal.editId = c.id;
        this.channelModal.form = {
          publicModelName: c.public_model_name,
          enabled: !!c.enabled,
          remark: c.remark || '',
          strategy: c.strategy || 'ROUND_ROBIN',
        };
        this.channelModal.selectedIds = (c.provider_ids || []).slice();
      } else {
        this.channelModal.editId = null;
        this.channelModal.form = { publicModelName: '', enabled: true, remark: '', strategy: 'ROUND_ROBIN' };
        this.channelModal.selectedIds = [];
      }
      this.channelModal.open = true;
    },
    closeChannelModal() {
      if (this.channelModal.saving) return;
      this.channelModal.open = false;
    },

    async saveChannel() {
      const f = this.channelModal.form;
      if (!f.publicModelName) { this.message('请输入对客服务名称', 'warn'); return; }
      if (!this.channelModal.selectedIds.length) { this.message('请至少绑定一个启用的模型服务', 'warn'); return; }
      const body = {
        publicModelName: f.publicModelName,
        enabled: f.enabled ? 1 : 0,
        strategy: 'ROUND_ROBIN',
        remark: f.remark || '',
        providerIds: this.channelModal.selectedIds.slice(),
      };
      if (this.channelModal.editId) body.id = this.channelModal.editId;
      this.channelModal.saving = true;
      try {
        await this.request('/admin/channel', { method: this.channelModal.editId ? 'PUT' : 'POST', body });
        this.message(this.channelModal.editId ? '对客服务已更新' : '对客服务创建成功');
        this.channelModal.open = false;
        await this.loadChannels();
      } catch (e) {
        this.toastError(e);
      } finally {
        this.channelModal.saving = false;
      }
    },

    async delChannel(c) {
      if (!confirm(`确定删除对客服务「${c.public_model_name}」吗？删除后客户端将无法调用该模型。`)) return;
      try {
        await this.request('/admin/channel/' + c.id, { method: 'DELETE' });
        this.message('对客服务已删除');
        await this.loadChannels();
      } catch (e) {
        this.toastError(e);
      }
    },
  },
};

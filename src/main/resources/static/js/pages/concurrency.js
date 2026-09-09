/* ============================================================
 * 流控管理页面：候选模型优先级列表，逐行设置并发上限
 * ============================================================ */

const ConcurrencyApp = {
  mixins: [XUi.mixin],
  data() {
    return { rows: [], loading: false };
  },
  created() { this.load(); },
  methods: {
    async load() {
      this.loading = true;
      const list = await this.asyncLoad(() => XApi.listConcurrencyModels());
      if (list) this.rows = list.map(r => ({ ...r, draft: r.maxConcurrency, saving: false }));
      this.loading = false;
    },

    isDirty(r) { return Number(r.draft) !== Number(r.maxConcurrency); },

    async save(r) {
      let v = r.draft;
      if (v == null || v === '' || isNaN(v)) v = 0;
      v = Math.max(0, Math.floor(Number(v)));
      r.saving = true;
      try {
        await XApi.updateConcurrencyLimit(r.modelId, v);
        r.maxConcurrency = v;
        r.draft = v;
        this.message(v > 0 ? `${r.modelName} 并发上限已设为 ${v}` : `${r.modelName} 已设为不限制并发`);
      } catch (e) {
        this.toastError(e);
      } finally {
        r.saving = false;
      }
    },
  },
};

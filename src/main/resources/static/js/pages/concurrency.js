/* ============================================================
 * 流控管理页面：候选模型优先级列表，逐行设置失败次数与并发上限
 * 支持 3 秒自动刷新（纯前端开关，默认关闭）
 * ============================================================ */

const ConcurrencyApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      rows: [],
      loading: false,
      auto: false,
      lastRefresh: '',
      savingIds: {},
    };
  },
  created() { this.load(); },
  unmounted() { this.stopAuto(); },
  methods: {
    isSaving(r) { return !!this.savingIds[r.modelId]; },

    async load(silent) {
      if (!silent) this.loading = true;
      const list = await this.asyncLoad(() => XApi.listConcurrencyModels());
      if (list) this.rows = this.mergeRows(list);
      if (!silent) this.loading = false;
      this.lastRefresh = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    },

    /* 合并后保留用户尚未保存的输入，避免刷新覆盖正在编辑的内容 */
    mergeRows(list) {
      const prev = new Map(this.rows.map(r => [r.modelId, r]));
      return list.map(r => {
        const old = prev.get(r.modelId);
        return {
          ...r,
          draft: old && old.draft != null ? old.draft : r.maxConcurrency,
          draftFail: old && old.draftFail != null ? old.draftFail : r.failCount,
        };
      });
    },

    isDirty(r) {
      return Number(r.draft) !== Number(r.maxConcurrency) || Number(r.draftFail) !== Number(r.failCount);
    },

    /* 自动刷新开关：默认关闭，点一下开启，再点一下关闭 */
    toggleAuto() {
      if (this.auto) {
        this.stopAuto();
        this.message('自动刷新已关闭');
      } else {
        this.auto = true;
        this._timer = setInterval(() => this.load(true), 3000);
        this.message('自动刷新已开启，每 3 秒刷新一次');
      }
    },

    stopAuto() {
      if (this._timer) { clearInterval(this._timer); this._timer = null; }
      this.auto = false;
    },

    async save(r) {
      let v = r.draft;
      if (v == null || v === '' || isNaN(v)) v = 0;
      v = Math.max(0, Math.floor(Number(v)));

      let f = r.draftFail;
      if (f == null || f === '' || isNaN(f)) f = 0;
      f = Math.max(0, Math.floor(Number(f)));

      this.savingIds[r.modelId] = true;
      try {
        await XApi.updateConcurrencyLimit(r.modelId, v, f);
        r.maxConcurrency = v;
        r.draft = v;
        r.failCount = f;
        r.draftFail = f;
        const msgs = [];
        msgs.push(v > 0 ? `并发上限 ${v}` : '并发不限制');
        msgs.push(`失败次数 ${f}`);
        this.message(`${r.modelName} 已更新：${msgs.join('，')}`);
        await this.load(true);
      } catch (e) {
        this.toastError(e);
      } finally {
        delete this.savingIds[r.modelId];
      }
    },
  },
};

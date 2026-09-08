/* ============================================================
 * 接入示例页面
 * 自动生成 cURL / Python / Node.js 调用代码
 * ============================================================ */

const UsageApp = {
  mixins: [XUi.mixin],
  data() {
    return {
      baseURL: window.location.origin + '/v1',
      keys: [],
      providers: [],
      selectedKey: '',
      loading: false,
    };
  },

  created() {
    this.initBaseURL();
    this.loadKeys();
  },

  computed: {
    currentKey() {
      return this.keys.find(k => String(k.id) === String(this.selectedKey)) || null;
    },
    keyToken() {
      return this.currentKey ? this.currentKey.api_key : '你的_Key';
    },
    /* 池内启用的渠道提供的去重模型名 */
    poolModels() {
      const seen = {};
      const list = [];
      (this.providers || []).forEach(p => {
        if (p.enabled !== 1 || !p.modelName || seen[p.modelName]) return;
        seen[p.modelName] = true;
        list.push(p.modelName);
      });
      return list;
    },
    /* 该 Key 是否被限定为某个模型 */
    pinnedModel() {
      return (this.currentKey && this.currentKey.model_name) || '';
    },
    /* 示例中使用的 model 值：限定模型取限定值，default 取池内任一模型作演示 */
    sampleModel() {
      if (this.pinnedModel) return this.pinnedModel;
      return this.poolModels.length ? this.poolModels[0] : 'default';
    },
    scopeLabel() {
      const scope = this.pinnedModel ? '限定 ' + this.pinnedModel : 'default · 全池';
      return scope;
    },
    scopeNote() {
      if (this.pinnedModel) {
        return '该 Key 已限定模型，请求 model 必须填：' + this.pinnedModel;
      }
      if (this.poolModels.length) {
        return 'default 客户可调用池内任意模型，示例以「' + this.poolModels[0] + '」演示，可替换为池内其它模型。';
      }
      return 'default 客户可调用池内任意模型，当前池内暂无启用渠道。';
    },
    curlExample() {
      return `# ${this.scopeNote}
curl ${this.baseURL}/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer ${this.keyToken}" \\
  -d '{
    "model": "${this.sampleModel}",
    "messages": [{"role": "user", "content": "你好"}]
  }'`;
    },
    pythonExample() {
      return `from openai import OpenAI

client = OpenAI(
    base_url="${this.baseURL}",
    api_key="${this.keyToken}",
)

# ${this.scopeNote}
resp = client.chat.completions.create(
    model="${this.sampleModel}",
    messages=[{"role": "user", "content": "你好"}],
)
print(resp.choices[0].message.content)`;
    },
    nodeExample() {
      return `const { OpenAI } = require("openai");

const client = new OpenAI({
  baseURL: "${this.baseURL}",
  apiKey: "${this.keyToken}",
});

// ${this.scopeNote}
const resp = await client.chat.completions.create({
  model: "${this.sampleModel}",
  messages: [{ role: "user", content: "你好" }],
});
console.log(resp.choices[0].message.content);`;
    },
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

    async loadKeys() {
      this.loading = true;
      try {
        const [keys, providers] = await Promise.all([XApi.listKeys(), XApi.listProviders()]);
        this.keys = keys || [];
        this.providers = providers || [];
        if (this.keys.length) {
          this.selectedKey = String(this.keys[0].id);
        }
      } catch (e) {
        this.toastError(e);
      } finally {
        this.loading = false;
      }
    },
    async copy(text) {
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
      this.message(done ? '已复制到剪贴板' : '复制失败，请手动复制', done ? 'success' : 'error');
    },
  },
};

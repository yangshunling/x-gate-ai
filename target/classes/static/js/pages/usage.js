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
      selectedKey: '',
      loading: false,
    };
  },

  created() { this.loadKeys(); },

  computed: {
    currentKey() {
      return this.keys.find(k => String(k.id) === String(this.selectedKey)) || null;
    },
    keyToken() {
      return this.currentKey ? this.currentKey.api_key : '你的_Key';
    },
    modelName() {
      return this.currentKey ? this.currentKey.public_model_name : '你的_模型名';
    },
    curlExample() {
      return `curl ${this.baseURL}/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer ${this.keyToken}" \\
  -d '{
    "model": "${this.modelName}",
    "messages": [{"role": "user", "content": "你好"}]
  }'`;
    },
    pythonExample() {
      return `from openai import OpenAI

client = OpenAI(
    base_url="${this.baseURL}",
    api_key="${this.keyToken}",
)

resp = client.chat.completions.create(
    model="${this.modelName}",
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

const resp = await client.chat.completions.create({
  model: "${this.modelName}",
  messages: [{ role: "user", content: "你好" }],
});
console.log(resp.choices[0].message.content);`;
    },
  },

  methods: {
    async loadKeys() {
      this.loading = true;
      try {
        this.keys = await XApi.listKeys() || [];
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

const API_BASE = window.location.protocol === "file:" ? "http://localhost:8080" : "";

const CLIENTE_ID = (() => {
  const CHAVE = "lojasd.clienteId";
  let id = null;
  try { id = localStorage.getItem(CHAVE); } catch (e) {}
  if (!id) {
    id = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2));
    try { localStorage.setItem(CHAVE, id); } catch (e) {}
  }
  return id;
})();

class ErroApi extends Error {
  constructor(status, mensagem) {
    super(mensagem);
    this.status = status;
  }
}

async function requisicao(metodo, caminho, corpo) {
  let resp;
  try {
    resp = await fetch(API_BASE + caminho, {
      method: metodo,
      headers: corpo ? { "Content-Type": "application/json" } : {},
      body: corpo ? JSON.stringify(corpo) : undefined,
    });
  } catch (e) {
    throw new ErroApi(0, "Não foi possível conectar à loja. Tente novamente em instantes.");
  }
  const texto = await resp.text();
  const dados = texto ? JSON.parse(texto) : null;
  if (!resp.ok) throw new ErroApi(resp.status, (dados && dados.erro) || `Erro HTTP ${resp.status}`);
  return dados;
}

const api = {
  listarProdutos: () => requisicao("GET", "/api/produtos"),

  criarPedido: (itens) => requisicao("POST", "/api/pedidos", { clienteId: CLIENTE_ID, itens }),

  listarPedidos: () => requisicao("GET", `/api/pedidos?clienteId=${encodeURIComponent(CLIENTE_ID)}`),

  cancelarPedido: (pedidoId) =>
    requisicao("DELETE", `/api/pedidos/${encodeURIComponent(pedidoId)}?clienteId=${encodeURIComponent(CLIENTE_ID)}`),

  registrarInteresse: (email, categorias) => requisicao("POST", "/api/promocoes/interesse", { email, categorias }),

  cancelarInteresse: (email) => requisicao("DELETE", `/api/promocoes/interesse?email=${encodeURIComponent(email)}`),

  conectarEventos({ aoAbrir, aoErro, aoPedido }) {
    const fonte = new EventSource(`${API_BASE}/api/eventos?clienteId=${encodeURIComponent(CLIENTE_ID)}`);
    fonte.addEventListener("open", aoAbrir);
    fonte.addEventListener("error", aoErro);
    fonte.addEventListener("pedido", (e) => aoPedido(JSON.parse(e.data)));
    return fonte;
  },
};

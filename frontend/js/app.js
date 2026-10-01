// Interface da loja: catálogo, carrinho, pedidos (acompanhados por SSE) e inscrição em promoções.

const CATEGORIAS = {
  A: { nome: "Informática", icone: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>' },
  B: { nome: "Moda", icone: '<path d="M8 3 3 6l2 5 3-1v11h8V10l3 1 2-5-5-3a4 4 0 0 1-8 0z"/>' },
  C: { nome: "Variedades", icone: '<rect x="3" y="8" width="18" height="13" rx="2"/><path d="M3 12h18M12 8v13M12 8s-2-5-4.5-3.5S9 8 12 8zm0 0s2-5 4.5-3.5S15 8 12 8z"/>' },
};

// Etapas da linha do tempo de um pedido, na ordem do fluxo de eventos.
const ETAPAS = [
  { status: "CRIADO", rotulo: "Criado" },
  { status: "ESTOQUE_CONFIRMADO", rotulo: "Estoque confirmado" },
  { status: "AGUARDANDO_PAGAMENTO", rotulo: "Aguardando pagamento" },
  { status: "PAGAMENTO_APROVADO", rotulo: "Pagamento aprovado" },
  { status: "ENVIADO", rotulo: "Enviado" },
];

const SELOS = {
  CRIADO: { classe: "", texto: "Verificando estoque", pulso: true },
  ESTOQUE_CONFIRMADO: { classe: "", texto: "Estoque confirmado", pulso: true },
  AGUARDANDO_PAGAMENTO: { classe: "aviso", texto: "Aguardando pagamento", pulso: true },
  PAGAMENTO_APROVADO: { classe: "ok", texto: "Pagamento aprovado", pulso: true },
  ENVIADO: { classe: "ok", texto: "Enviado" },
  CANCELADO_ESTOQUE: { classe: "erro", texto: "Sem estoque" },
  PAGAMENTO_RECUSADO: { classe: "erro", texto: "Pagamento recusado" },
  CANCELADO_USUARIO: { classe: "erro", texto: "Cancelado" },
};
const FINALIZADOS = ["ENVIADO", "CANCELADO_ESTOQUE", "PAGAMENTO_RECUSADO", "CANCELADO_USUARIO"];
const CANCELAVEIS = ["CRIADO", "ESTOQUE_CONFIRMADO", "AGUARDANDO_PAGAMENTO"];

const brl = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
const hora = new Intl.DateTimeFormat("pt-BR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const dataHora = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short" });
const $ = (id) => document.getElementById(id);

const estado = {
  produtos: [],
  filtro: "todas",
  carrinho: lerArmazenado("lojasd.carrinho", {}), // produtoId -> quantidade
  pedidos: new Map(),
  abasPagamento: new Map(), // pedidoId -> aba aberta no clique de "Finalizar" (aguardando a URL do checkout)
};

// ---------------------------------------------------------------- utilidades

function lerArmazenado(chave, padrao) {
  try {
    const valor = localStorage.getItem(chave);
    return valor ? JSON.parse(valor) : padrao;
  } catch (e) {
    return padrao;
  }
}

function salvarArmazenado(chave, valor) {
  try { localStorage.setItem(chave, JSON.stringify(valor)); } catch (e) { /* ignora */ }
}

function esc(texto) {
  return String(texto ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function iconeCategoria(cat) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${CATEGORIAS[cat]?.icone || ""}</svg>`;
}

const MAXIMO_TOASTS = 3;

function toast({ titulo, texto = "", tipo = "", acao = null, duracao = 4500 }) {
  const pilha = $("toasts");
  while (pilha.children.length >= MAXIMO_TOASTS) pilha.firstElementChild.remove();
  const el = document.createElement("div");
  el.className = `toast ${tipo}`;
  el.innerHTML = `<strong>${esc(titulo)}</strong>${texto ? `<p>${esc(texto)}</p>` : ""}`;
  if (acao) {
    const botao = document.createElement("button");
    botao.className = "botao primario pequeno";
    botao.textContent = acao.rotulo;
    botao.addEventListener("click", () => { acao.fn(); fechar(); });
    el.append(botao);
  }
  pilha.append(el);
  const fechar = () => { el.classList.add("saindo"); setTimeout(() => el.remove(), 250); };
  setTimeout(fechar, acao ? duracao * 2 : duracao);
}

// ---------------------------------------------------------------- navegação

function mostrarAba(nome) {
  if (!["catalogo", "pedidos", "promocoes"].includes(nome)) nome = "catalogo";
  document.querySelectorAll(".aba").forEach((b) => b.classList.toggle("ativa", b.dataset.aba === nome));
  document.querySelectorAll(".secao").forEach((s) => s.classList.toggle("oculto", s.id !== `secao-${nome}`));
  if (location.hash !== `#${nome}`) history.replaceState(null, "", `#${nome}`);
}

document.querySelectorAll("[data-aba]").forEach((el) =>
  el.addEventListener("click", (e) => { e.preventDefault(); mostrarAba(el.dataset.aba); }));
window.addEventListener("hashchange", () => mostrarAba(location.hash.slice(1)));

// ---------------------------------------------------------------- catálogo

function renderFiltros() {
  const opcoes = [["todas", "Todas"], ...Object.entries(CATEGORIAS).map(([c, info]) => [c, `${c} · ${info.nome}`])];
  $("filtros").innerHTML = opcoes.map(([valor, rotulo]) =>
    `<button class="filtro ${estado.filtro === valor ? "ativo" : ""}" data-filtro="${valor}" role="tab" aria-selected="${estado.filtro === valor}">${esc(rotulo)}</button>`).join("");
}

$("filtros").addEventListener("click", (e) => {
  const botao = e.target.closest("[data-filtro]");
  if (!botao) return;
  estado.filtro = botao.dataset.filtro;
  renderFiltros();
  renderProdutos();
});

async function carregarProdutos({ silencioso = false } = {}) {
  if (!silencioso) $("gradeProdutos").innerHTML = '<div class="esqueleto"></div>'.repeat(4);
  try {
    estado.produtos = await api.listarProdutos();
    renderProdutos();
    renderCarrinho();
  } catch (e) {
    if (!silencioso) {
      $("gradeProdutos").innerHTML = `<div class="estado-vazio"><strong>Não foi possível carregar o catálogo</strong>${esc(e.message)}</div>`;
    }
  }
}

function renderProdutos() {
  const lista = estado.produtos.filter((p) => estado.filtro === "todas" || p.categoria === estado.filtro);
  if (lista.length === 0) {
    $("gradeProdutos").innerHTML = '<div class="estado-vazio"><strong>Nenhum produto disponível</strong>Tente outra categoria ou atualize o catálogo.</div>';
    return;
  }
  $("gradeProdutos").innerHTML = lista.map((p) => `
    <article class="cartao produto" data-cat="${esc(p.categoria)}">
      <div class="produto-imagem">${iconeCategoria(p.categoria)}</div>
      <div>
        <span class="etiqueta-cat">Categoria ${esc(p.categoria)} · ${esc(CATEGORIAS[p.categoria]?.nome || "")}</span>
        <h3>${esc(p.nome)}</h3>
      </div>
      <div>
        <div class="produto-preco">${brl.format(p.preco)}</div>
        <div class="produto-estoque ${p.quantidade <= 3 ? "baixo" : ""}">${p.quantidade} em estoque</div>
      </div>
      <div class="produto-acoes">
        <div class="qtd">
          <button type="button" data-passo="-1" aria-label="Diminuir">−</button>
          <input type="number" min="1" max="99" value="1" aria-label="Quantidade de ${esc(p.nome)}">
          <button type="button" data-passo="1" aria-label="Aumentar">+</button>
        </div>
        <button class="botao primario" data-adicionar="${p.id}">Adicionar</button>
      </div>
    </article>`).join("");
}

$("gradeProdutos").addEventListener("click", (e) => {
  const passo = e.target.closest("[data-passo]");
  if (passo) {
    const input = passo.parentElement.querySelector("input");
    input.value = Math.min(99, Math.max(1, (parseInt(input.value, 10) || 1) + Number(passo.dataset.passo)));
    return;
  }
  const adicionar = e.target.closest("[data-adicionar]");
  if (adicionar) {
    const input = adicionar.closest(".produto").querySelector("input");
    const qtd = Math.min(99, Math.max(1, parseInt(input.value, 10) || 1));
    adicionarAoCarrinho(Number(adicionar.dataset.adicionar), qtd);
    input.value = 1;
  }
});

$("recarregarProdutos").addEventListener("click", () => carregarProdutos());

// ---------------------------------------------------------------- carrinho

function produtoPorId(id) {
  return estado.produtos.find((p) => p.id === Number(id));
}

function adicionarAoCarrinho(produtoId, qtd) {
  estado.carrinho[produtoId] = Math.min(99, (estado.carrinho[produtoId] || 0) + qtd);
  salvarArmazenado("lojasd.carrinho", estado.carrinho);
  renderCarrinho();
  const p = produtoPorId(produtoId);
  toast({ titulo: "Adicionado ao carrinho", texto: `${qtd}× ${p ? p.nome : "produto"}`, tipo: "ok", duracao: 2500 });
}

function alterarQuantidade(produtoId, qtd) {
  if (qtd <= 0) delete estado.carrinho[produtoId];
  else estado.carrinho[produtoId] = Math.min(99, qtd);
  salvarArmazenado("lojasd.carrinho", estado.carrinho);
  renderCarrinho();
}

function renderCarrinho() {
  const itens = Object.entries(estado.carrinho);
  const totalItens = itens.reduce((s, [, q]) => s + q, 0);
  $("contadorCarrinho").textContent = totalItens;
  $("contadorCarrinho").classList.toggle("oculto", totalItens === 0);
  $("finalizarPedido").disabled = itens.length === 0;

  if (itens.length === 0) {
    $("itensCarrinho").innerHTML = '<li class="carrinho-vazio">Seu carrinho está vazio.</li>';
    $("totalCarrinho").textContent = brl.format(0);
    return;
  }

  let total = 0;
  $("itensCarrinho").innerHTML = itens.map(([id, qtd]) => {
    const p = produtoPorId(id);
    const preco = p ? p.preco : 0;
    total += preco * qtd;
    const semEstoque = p && qtd > p.quantidade;
    return `
      <li class="item-carrinho">
        <div><div class="nome">${esc(p ? p.nome : `Produto ${id}`)}</div><div class="preco">${p ? brl.format(preco) : "indisponível no catálogo"}</div></div>
        <div class="subtotal">${brl.format(preco * qtd)}</div>
        ${semEstoque ? `<div class="aviso-estoque">Só ${p.quantidade} em estoque: o MS Estoque vai recusar este pedido.</div>` : ""}
        ${!p ? '<div class="aviso-estoque">Produto sem estoque no momento: o pedido será recusado.</div>' : ""}
        <div class="controles">
          <div class="qtd">
            <button type="button" data-item="${id}" data-nova="${qtd - 1}" aria-label="Diminuir">−</button>
            <input type="number" value="${qtd}" readonly aria-label="Quantidade">
            <button type="button" data-item="${id}" data-nova="${qtd + 1}" aria-label="Aumentar">+</button>
          </div>
          <button class="botao perigo-contorno pequeno" data-item="${id}" data-nova="0">Remover</button>
        </div>
      </li>`;
  }).join("");
  $("totalCarrinho").textContent = brl.format(total);
}

$("itensCarrinho").addEventListener("click", (e) => {
  const botao = e.target.closest("[data-item]");
  if (botao) alterarQuantidade(botao.dataset.item, Number(botao.dataset.nova));
});

function abrirCarrinho(abrir) {
  $("gaveta").classList.toggle("aberta", abrir);
  $("gaveta").setAttribute("aria-hidden", String(!abrir));
  $("veu").classList.toggle("oculto", !abrir);
}
$("abrirCarrinho").addEventListener("click", () => abrirCarrinho(true));
$("fecharCarrinho").addEventListener("click", () => abrirCarrinho(false));
$("veu").addEventListener("click", () => abrirCarrinho(false));
document.addEventListener("keydown", (e) => { if (e.key === "Escape") abrirCarrinho(false); });

// ---------------------------------------------------------------- pagamento em nova aba

// A URL do checkout só chega depois (evento pagamento.pendente -> SSE). Para o navegador não
// bloquear o pop-up, a aba é aberta já no clique de "Finalizar" e redirecionada quando a URL chegar.
function abrirAbaDeEspera() {
  const aba = window.open("", "_blank");
  if (!aba) return null;
  aba.document.write(`<!doctype html><meta charset="utf-8"><title>Preparando pagamento…</title>
    <body style="font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:100vh;margin:0;background:#f6f5fb;color:#1d1a2e">
    <div style="text-align:center"><p style="font-size:18px;font-weight:600;margin:0 0 6px">Preparando seu pagamento…</p>
    <p id="msg" style="color:#5f5a7a;margin:0">Aguardando a confirmação do estoque e a cobrança do provedor.</p></div></body>`);
  aba.document.close();
  return aba;
}

function mensagemNaAba(aba, texto) {
  try {
    aba.document.getElementById("msg").textContent = texto;
    aba.document.title = "Pedido não pode ser pago";
  } catch (e) { /* a aba já navegou */ }
}

function abrirCheckout(url) {
  if (!window.open(url, "_blank")) toast({ titulo: "Pop-up bloqueado", texto: "Permita pop-ups para abrir o pagamento.", tipo: "erro" });
}

/** Leva a aba de espera do pedido ao checkout (ou avisa que o pedido falhou). */
function atualizarAbaPagamento(pedido) {
  const aba = estado.abasPagamento.get(pedido.id);
  if (!aba) return false;
  if (aba.closed) { estado.abasPagamento.delete(pedido.id); return false; }
  if (pedido.status === "AGUARDANDO_PAGAMENTO" && pedido.checkoutUrl) {
    aba.location.href = pedido.checkoutUrl;
    estado.abasPagamento.delete(pedido.id);
    return true;
  }
  if (FINALIZADOS.includes(pedido.status)) {
    mensagemNaAba(aba, `Pedido ${pedido.id}: ${pedido.statusDescricao}. Você pode fechar esta aba.`);
    estado.abasPagamento.delete(pedido.id);
  }
  return false;
}

$("finalizarPedido").addEventListener("click", async () => {
  const itens = Object.entries(estado.carrinho).map(([produtoId, quantidade]) => ({ produtoId: Number(produtoId), quantidade }));
  if (itens.length === 0) return;
  const botao = $("finalizarPedido");
  botao.disabled = true;
  botao.textContent = "Enviando pedido…";
  const aba = abrirAbaDeEspera();
  try {
    const pedido = await api.criarPedido(itens);
    estado.carrinho = {};
    salvarArmazenado("lojasd.carrinho", estado.carrinho);
    registrarPedido(pedido, false);
    if (aba) {
      estado.abasPagamento.set(pedido.id, aba);
      atualizarAbaPagamento(estado.pedidos.get(pedido.id)); // o SSE pode ter chegado antes da resposta
    }
    abrirCarrinho(false);
    mostrarAba("pedidos");
    adicionarAoFeed({ tipo: "CRIADO", mensagem: `Pedido ${pedido.id}: criado e publicado em pedido.criado` });
    toast({ titulo: `Pedido ${pedido.id} criado`, texto: "Acompanhe o status em tempo real." });
  } catch (e) {
    if (aba) aba.close();
    toast({ titulo: "Não foi possível criar o pedido", texto: e.message, tipo: "erro" });
  } finally {
    botao.textContent = "Finalizar pedido";
    renderCarrinho();
  }
});

// ---------------------------------------------------------------- pedidos

/** Guarda o pedido e redesenha; não deixa uma resposta antiga sobrescrever um status mais novo. */
function registrarPedido(pedido, destacar = true) {
  const atual = estado.pedidos.get(pedido.id);
  if (atual && atual.historico.length > pedido.historico.length) return;
  estado.pedidos.set(pedido.id, pedido);
  renderPedidos(destacar ? pedido.id : null);
}

function etapasDoPedido(p) {
  const indice = (status) => ETAPAS.findIndex((e) => e.status === status);
  let feitas, falhou = -1;
  if (FINALIZADOS.includes(p.status) && p.status !== "ENVIADO") {
    // última etapa alcançada antes do cancelamento; a seguinte é a que falhou
    const alcancadas = p.historico.map((h) => indice(h.status)).filter((i) => i >= 0);
    feitas = Math.max(...alcancadas);
    if (p.status === "PAGAMENTO_RECUSADO") feitas = Math.max(feitas, indice("AGUARDANDO_PAGAMENTO"));
    falhou = feitas + 1;
  } else {
    feitas = indice(p.status);
  }
  return ETAPAS.map((etapa, i) => {
    let classe = "";
    if (i <= feitas) classe = "feita";
    else if (i === falhou) classe = "falhou";
    else if (falhou < 0 && i === feitas + 1) classe = "atual";
    return `<li class="etapa ${classe}" title="${esc(etapa.rotulo)}"><div class="barra"></div><span>${esc(etapa.rotulo)}</span></li>`;
  }).join("");
}

function renderPedidos(destacarId = null) {
  const lista = [...estado.pedidos.values()].sort((a, b) => b.criadoEm - a.criadoEm);
  const ativos = lista.filter((p) => !FINALIZADOS.includes(p.status)).length;
  $("contadorPedidos").textContent = ativos;
  $("contadorPedidos").classList.toggle("oculto", ativos === 0);

  if (lista.length === 0) {
    $("listaPedidos").innerHTML = '<div class="estado-vazio"><strong>Nenhum pedido ainda</strong>Escolha produtos no catálogo e finalize pelo carrinho.</div>';
    return;
  }
  $("listaPedidos").innerHTML = lista.map((p) => {
    const selo = SELOS[p.status] || { classe: "", texto: p.status };
    const pagar = p.status === "AGUARDANDO_PAGAMENTO" && p.checkoutUrl;
    const cancelar = CANCELAVEIS.includes(p.status);
    const motivo = FINALIZADOS.includes(p.status) && p.status !== "ENVIADO";
    return `
      <article class="cartao pedido ${p.id === destacarId ? "destaque" : ""}" data-pedido="${esc(p.id)}">
        <div class="pedido-topo">
          <div>
            <div class="pedido-id">#${esc(p.id)}</div>
            <div class="pedido-data">${dataHora.format(new Date(p.criadoEm))}</div>
          </div>
          <span class="selo ${selo.classe}">${selo.pulso ? '<span class="pulso"></span>' : ""}${esc(selo.texto)}</span>
        </div>
        <ol class="etapas" aria-label="Andamento do pedido">${etapasDoPedido(p)}</ol>
        <ul class="pedido-itens">
          ${p.itens.map((i) => `<li><span>${i.quantidade}× ${esc(i.nome)}</span><span>${brl.format(i.precoUnitario * i.quantidade)}</span></li>`).join("")}
        </ul>
        ${motivo ? `<div class="pedido-motivo">${esc(p.statusDescricao)}${p.motivo && !p.statusDescricao.includes(p.motivo) ? ` — ${esc(p.motivo)}` : ""}</div>` : ""}
        ${p.notaFiscal ? `<div class="pedido-nota">Nota fiscal ${esc(p.notaFiscal)} emitida. Pedido a caminho!</div>` : ""}
        <div class="pedido-rodape">
          <span class="pedido-total">${brl.format(p.valorTotal)}</span>
          <div class="pedido-acoes">
            ${pagar ? `<button class="botao primario pequeno" data-pagar="${esc(p.id)}">Pagar agora</button>` : ""}
            ${cancelar ? `<button class="botao perigo-contorno pequeno" data-cancelar="${esc(p.id)}">Cancelar pedido</button>` : ""}
          </div>
        </div>
      </article>`;
  }).join("");
}

$("listaPedidos").addEventListener("click", async (e) => {
  const pagar = e.target.closest("[data-pagar]");
  if (pagar) {
    const p = estado.pedidos.get(pagar.dataset.pagar);
    if (p && p.checkoutUrl) abrirCheckout(p.checkoutUrl);
    return;
  }
  const cancelar = e.target.closest("[data-cancelar]");
  if (cancelar) {
    const id = cancelar.dataset.cancelar;
    if (!confirm(`Cancelar o pedido #${id}? Os itens voltam ao estoque.`)) return;
    cancelar.disabled = true;
    try {
      registrarPedido(await api.cancelarPedido(id));
    } catch (err) {
      toast({ titulo: "Não foi possível cancelar", texto: err.message, tipo: "erro" });
      cancelar.disabled = false;
    }
  }
});

async function sincronizarPedidos() {
  try {
    (await api.listarPedidos()).forEach((p) => registrarPedido(p, false));
    renderPedidos();
  } catch (e) { /* o Gateway pode estar fora; o SSE reconecta e tenta de novo */ }
}

// ---------------------------------------------------------------- SSE

function adicionarAoFeed({ tipo, mensagem }) {
  const classe = ["ENVIADO", "PAGAMENTO_APROVADO", "ESTOQUE_CONFIRMADO"].includes(tipo) ? "ok"
    : FINALIZADOS.includes(tipo) ? "erro" : "info";
  const li = document.createElement("li");
  li.className = classe;
  li.innerHTML = `<time>${hora.format(new Date())} · ${esc(tipo)}</time>${esc(mensagem)}`;
  const feed = $("feed");
  feed.querySelector(".vazio")?.remove();
  feed.prepend(li);
  while (feed.children.length > 40) feed.lastElementChild.remove();
}

function aoNotificacaoPedido(notificacao) {
  const pedido = notificacao.pedido;
  registrarPedido(pedido);
  adicionarAoFeed(notificacao);

  const levadoAoCheckout = atualizarAbaPagamento(pedido);
  const tipo = ["ENVIADO", "PAGAMENTO_APROVADO", "ESTOQUE_CONFIRMADO"].includes(notificacao.tipo) ? "ok"
    : FINALIZADOS.includes(notificacao.tipo) ? "erro" : "";
  const titulo = (SELOS[notificacao.tipo] || {}).texto || notificacao.tipo;

  if (notificacao.tipo === "AGUARDANDO_PAGAMENTO" && !levadoAoCheckout) {
    toast({ titulo: `Pedido ${pedido.id}: pague para continuar`, texto: "A cobrança foi gerada pelo provedor de pagamento.",
      acao: { rotulo: "Abrir pagamento", fn: () => abrirCheckout(pedido.checkoutUrl) } });
  } else {
    const detalhe = notificacao.mensagem.replace(/^Pedido \S+: /, "");
    toast({ titulo: `Pedido ${pedido.id}: ${titulo}`, texto: detalhe.toLowerCase() === titulo.toLowerCase() ? "" : detalhe, tipo });
  }

  // reserva e devolução mudam o estoque: atualiza o catálogo
  if (["ESTOQUE_CONFIRMADO", "CANCELADO_ESTOQUE", "PAGAMENTO_RECUSADO", "CANCELADO_USUARIO"].includes(notificacao.tipo)) {
    setTimeout(() => carregarProdutos({ silencioso: true }), 400);
  }
}

function estadoConexao(classe, rotulo) {
  $("conexao").className = `conexao ${classe}`;
  $("conexao").querySelector(".rotulo").textContent = rotulo;
}

api.conectarEventos({
  aoAbrir: () => {
    estadoConexao("online", "Tempo real");
    sincronizarPedidos(); // recupera mudanças que possam ter ocorrido enquanto estava desconectado
  },
  aoErro: () => estadoConexao("offline", "Reconectando…"),
  aoPedido: aoNotificacaoPedido,
});

// ---------------------------------------------------------------- promoções

function renderOpcoesCategoria() {
  const salvas = lerArmazenado("lojasd.categorias", []);
  $("opcoesCategoria").innerHTML = Object.entries(CATEGORIAS).map(([c, info]) => `
    <label class="opcao-categoria" data-cat="${c}">
      <input type="checkbox" name="categoria" value="${c}" ${salvas.includes(c) ? "checked" : ""}>
      <span><strong>Categoria ${c}</strong><small>${esc(info.nome)}</small></span>
    </label>`).join("");
  $("emailInteresse").value = lerArmazenado("lojasd.email", "");
}

function mensagemInteresse(texto, tipo) {
  $("mensagemInteresse").textContent = texto;
  $("mensagemInteresse").className = `mensagem-form ${tipo}`;
}

$("formInteresse").addEventListener("submit", async (e) => {
  e.preventDefault();
  const email = $("emailInteresse").value.trim();
  const categorias = [...document.querySelectorAll('input[name="categoria"]:checked')].map((i) => i.value);
  if (!$("emailInteresse").checkValidity() || !email) return mensagemInteresse("Informe um e-mail válido.", "erro");
  if (categorias.length === 0) return mensagemInteresse("Escolha ao menos uma categoria.", "erro");

  $("btnInscrever").disabled = true;
  try {
    const resp = await api.registrarInteresse(email, categorias);
    salvarArmazenado("lojasd.email", email);
    salvarArmazenado("lojasd.categorias", categorias);
    mensagemInteresse(`${resp.mensagem} Categorias: ${resp.categorias.join(", ")}.`, "ok");
  } catch (err) {
    mensagemInteresse(err.message, "erro");
  } finally {
    $("btnInscrever").disabled = false;
  }
});

$("btnCancelarInteresse").addEventListener("click", async () => {
  const email = $("emailInteresse").value.trim();
  if (!$("emailInteresse").checkValidity() || !email) return mensagemInteresse("Informe o e-mail que deseja descadastrar.", "erro");
  $("btnCancelarInteresse").disabled = true;
  try {
    const resp = await api.cancelarInteresse(email);
    salvarArmazenado("lojasd.categorias", []);
    document.querySelectorAll('input[name="categoria"]').forEach((i) => { i.checked = false; });
    mensagemInteresse(resp.mensagem, "ok");
  } catch (err) {
    mensagemInteresse(err.message, "erro");
  } finally {
    $("btnCancelarInteresse").disabled = false;
  }
});

// ---------------------------------------------------------------- início

$("feed").innerHTML = '<li class="vazio">As mudanças de status dos seus pedidos aparecem aqui.</li>';
mostrarAba(location.hash.slice(1));
renderFiltros();
renderOpcoesCategoria();
renderCarrinho();
renderPedidos();
carregarProdutos();

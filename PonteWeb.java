package web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Ponte HTTP local entre abas do navegador e conexões TCP do chat. */
public final class PonteWeb implements AutoCloseable {
    public static final int PORTA_HTTP = 8080;
    // Mantido igual a Servidor.PORTA; host e porta podem ser sobrescritos nos argumentos.
    public static final int PORTA_CHAT_PADRAO = 5000;
    private static final int LIMITE_CORPO_BYTES = 4096;
    private static final System.Logger LOGGER = System.getLogger(PonteWeb.class.getName());

    private final HttpServer servidorHttp;
    private final GerenciadorSessoes sessoes;
    private final AssistenteWeb assistente;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public PonteWeb(String hostChat, int portaChat) throws IOException {
        this(hostChat, portaChat, carregarAssistente());
    }

    public PonteWeb(String hostChat, int portaChat, AssistenteWeb assistente) throws IOException {
        this.assistente = assistente;
        sessoes = new GerenciadorSessoes(hostChat, portaChat, assistente::criarSessao, assistente::removerSessao);
        servidorHttp = HttpServer.create(new InetSocketAddress("localhost", PORTA_HTTP), 0);
        servidorHttp.setExecutor(executor);
        servidorHttp.createContext("/", this::handleRaiz);
        servidorHttp.createContext("/fundo.png", this::handleBackground);
        servidorHttp.createContext("/entrar", this::handleEntrar);
        servidorHttp.createContext("/enviar", this::handleEnviar);
        servidorHttp.createContext("/eventos", this::handleEventos);
        servidorHttp.createContext("/sair", this::handleSair);
        servidorHttp.createContext("/ia/status", this::handleIaStatus);
        servidorHttp.createContext("/ia/mensagem", this::handleIaMensagem);
        servidorHttp.createContext("/ia/reiniciar", this::handleIaReiniciar);
    }

    private static AssistenteWeb carregarAssistente() {
        return ServiceLoader.load(AssistenteWeb.class).findFirst().orElseGet(() -> new AssistenteWeb() {
            @Override public boolean disponivel() { return false; }
            @Override public void criarSessao(String token) { }
            @Override public String mensagem(String token, String texto) { return "{\"mensagem\":\"Assistente indisponível.\",\"opcoes\":[]}"; }
            @Override public void reiniciar(String token) { }
            @Override public void removerSessao(String token) { }
        });
    }

    public void start() {
        servidorHttp.start();
        LOGGER.log(System.Logger.Level.INFO, "Ponte web escutando em http://localhost:" + PORTA_HTTP);
    }

    private void handleBackground(HttpExchange exchange) throws IOException {
        boolean get = exchange.getRequestMethod().equals("GET");
        boolean head = exchange.getRequestMethod().equals("HEAD");
        if ((!get && !head)
                || !exchange.getRequestURI().getPath().equals("/fundo.png")) {
            responderTexto(exchange, 404, "Arquivo não encontrado.");
            return;
        }
        try (InputStream imagem = PonteWeb.class.getResourceAsStream("/web/fundo.png")) {
            if (imagem == null) {
                responderTexto(exchange, 404, "Imagem de fundo não encontrada.");
                return;
            }
            byte[] bytes = imagem.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            if (head) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream resposta = exchange.getResponseBody()) { resposta.write(bytes); }
        } finally {
            exchange.close();
        }
    }

    private void handleRaiz(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            responderTexto(exchange, 404, "Rota não encontrada.");
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            responderTexto(exchange, 405, "Use GET.");
            return;
        }
        try (InputStream html = PonteWeb.class.getResourceAsStream("/web/index.html")) {
            if (html == null) {
                responderTexto(exchange, 500, "Arquivo da interface web não encontrado.");
                return;
            }
            byte[] conteudo = html.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, conteudo.length);
            try (OutputStream resposta = exchange.getResponseBody()) { resposta.write(conteudo); }
        } finally {
            exchange.close();
        }
    }

    private void handleEntrar(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            responderTexto(exchange, 405, "Use POST.");
            return;
        }
        String nickname;
        try {
            nickname = lerCorpo(exchange, 1024);
            SessaoWeb.validarNickname(nickname);
        } catch (CorpoGrandeException e) {
            responderTexto(exchange, 413, "Nickname excede o tamanho permitido.");
            return;
        } catch (IllegalArgumentException e) {
            responderTexto(exchange, 400, e.getMessage());
            return;
        }
        try {
            String token = sessoes.criar(nickname);
            responder(exchange, 200, "application/json; charset=UTF-8", "{\"token\":\"" + token + "\"}");
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Não foi possível conectar a sessão web ao chat", e);
            responderTexto(exchange, 400, "Servidor de chat indisponível em " + e.getMessage());
        }
    }

    private void handleEnviar(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            responderTexto(exchange, 405, "Use POST.");
            return;
        }
        SessaoWeb sessao = sessoes.buscar(obterToken(exchange));
        if (sessao == null) {
            responderTexto(exchange, 401, "Sessão inexistente ou encerrada.");
            return;
        }
        String mensagem;
        try {
            mensagem = lerCorpo(exchange, LIMITE_CORPO_BYTES);
            SessaoWeb.validarMensagem(mensagem);
        } catch (CorpoGrandeException e) {
            responderTexto(exchange, 413, "Mensagem excede o limite permitido.");
            return;
        } catch (IllegalArgumentException e) {
            responderTexto(exchange, 400, e.getMessage());
            return;
        }
        try {
            sessao.enviar(mensagem);
            responder(exchange, 202, "text/plain; charset=UTF-8", "Mensagem enviada.");
        } catch (IllegalStateException e) {
            sessoes.remover(obterToken(exchange));
            responderTexto(exchange, 409, "A conexão com o servidor de chat foi encerrada.");
        }
    }

    private void handleEventos(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            responderTexto(exchange, 405, "Use GET.");
            return;
        }
        String token = obterToken(exchange);
        SessaoWeb sessao = sessoes.buscar(token);
        if (sessao == null) {
            responderTexto(exchange, 401, "Sessão inexistente ou encerrada.");
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=UTF-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream resposta = exchange.getResponseBody()) {
            while (!sessao.estaFechada() || sessao.temMensagensPendentes()) {
                String linha = sessao.receber(15, TimeUnit.SECONDS);
                if (linha == null) {
                    escreverUtf8(resposta, ": keep-alive\n\n");
                } else {
                    escreverEvento(resposta, linha);
                }
                resposta.flush();
            }
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.INFO, "Stream SSE fechado pelo navegador ou pela rede");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.WARNING, "Thread SSE interrompida", e);
        } finally {
            exchange.close();
            sessoes.remover(token);
        }
    }

    private void handleSair(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            responderTexto(exchange, 405, "Use POST.");
            return;
        }
        String token = obterToken(exchange);
        if (token == null) {
            responderTexto(exchange, 400, "Token de sessão ausente.");
            return;
        }
        sessoes.remover(token);
        responder(exchange, 200, "text/plain; charset=UTF-8", "Sessão encerrada.");
    }

    private void handleIaStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            responderTexto(exchange, 405, "Use GET.");
            return;
        }
        boolean disponivel = assistente.disponivel();
        String resposta = "{\"disponivel\":" + disponivel + ",\"mensagem\":\""
                + (disponivel ? "" : "Configure GROQ_API_KEY no ambiente e reinicie a PonteWeb.") + "\"}";
        responder(exchange, 200, "application/json; charset=UTF-8", resposta);
    }

    private void handleIaMensagem(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            responderTexto(exchange, 405, "Use POST.");
            return;
        }
        String token = obterToken(exchange);
        if (sessoes.buscar(token) == null) {
            responder(exchange, 401, "application/json; charset=UTF-8", "{\"mensagem\":\"Sessão inválida. Entre novamente.\",\"opcoes\":[]}");
            return;
        }
        String texto;
        try {
            texto = lerCorpo(exchange, 2000);
        } catch (CorpoGrandeException e) {
            responder(exchange, 413, "application/json; charset=UTF-8", "{\"mensagem\":\"Limite de 500 caracteres excedido.\",\"opcoes\":[]}");
            return;
        }
        if (texto == null || texto.isBlank() || texto.length() > 500 || texto.indexOf('\n') >= 0 || texto.indexOf('\r') >= 0) {
            responder(exchange, 400, "application/json; charset=UTF-8", "{\"mensagem\":\"Informe uma mensagem de até 500 caracteres em uma linha.\",\"opcoes\":[]}");
            return;
        }
        try {
            responder(exchange, 200, "application/json; charset=UTF-8", assistente.mensagem(token, texto));
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Falha em solicitação ao assistente web", e);
            responder(exchange, 200, "application/json; charset=UTF-8", "{\"mensagem\":\"Não foi possível atender agora. Tente novamente.\",\"opcoes\":[]}");
        }
    }

    private void handleIaReiniciar(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            responderTexto(exchange, 405, "Use POST.");
            return;
        }
        String token = obterToken(exchange);
        if (sessoes.buscar(token) == null) {
            responderTexto(exchange, 401, "Sessão inválida. Entre novamente.");
            return;
        }
        assistente.reiniciar(token);
        responder(exchange, 200, "application/json; charset=UTF-8", "{\"mensagem\":\"Busca reiniciada.\"}");
    }

    private static String lerCorpo(HttpExchange exchange, int limite) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(limite + 1);
        if (bytes.length > limite) throw new CorpoGrandeException();
        return new String(bytes, "UTF-8");
    }

    private static String obterToken(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-Session-Token");
        if (token != null && !token.isBlank()) return token;
        URI uri = exchange.getRequestURI();
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String parametro : query.split("&")) {
            if (parametro.startsWith("token=")) return parametro.substring("token=".length());
        }
        return null;
    }

    private static void escreverEvento(OutputStream saida, String linha) throws IOException {
        String[] linhas = linha.split("\\r?\\n", -1);
        for (String parte : linhas) escreverUtf8(saida, "data: " + parte + "\n");
        escreverUtf8(saida, "\n");
    }

    private static void escreverUtf8(OutputStream saida, String texto) throws IOException {
        saida.write(texto.getBytes("UTF-8"));
    }

    private static void responderTexto(HttpExchange exchange, int status, String texto) throws IOException {
        responder(exchange, status, "text/plain; charset=UTF-8", texto);
    }

    private static void responder(HttpExchange exchange, int status, String contentType, String texto) throws IOException {
        byte[] bytes = texto.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream resposta = exchange.getResponseBody()) { resposta.write(bytes); }
        finally { exchange.close(); }
    }

    @Override
    public void close() {
        servidorHttp.stop(0);
        sessoes.fecharTodas();
        executor.shutdownNow();
    }

    public static void main(String[] args) throws IOException {
        String hostChat = args.length > 0 ? args[0] : "localhost";
        int portaChat = args.length > 1 ? Integer.parseInt(args[1]) : PORTA_CHAT_PADRAO;
        PonteWeb ponte = new PonteWeb(hostChat, portaChat);
        Runtime.getRuntime().addShutdownHook(new Thread(ponte::close, "ponte-web-shutdown"));
        ponte.start();
    }

    private static final class CorpoGrandeException extends IllegalArgumentException { }
}

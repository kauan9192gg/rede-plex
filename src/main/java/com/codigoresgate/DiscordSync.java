package com.codigoresgate;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.SimpleTimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mantem UMA mensagem no Discord (via webhook) com a lista de codigos disponiveis,
 * editando essa mesma mensagem sempre que algo muda.
 */
public class DiscordSync {

    private static final String UA = "DiscordBot (CodigoResgate, 1.0)";
    private static final String TITLE = "\uD83C\uDF81 C\u00F3digos dispon\u00EDveis";
    private static final String FOOTER = "Atualizado automaticamente";
    private static final int COLOR = 15844367;

    private final CodigoResgate plugin;
    private final File stateFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile String messageId;
    private volatile String lastDesc = null;
    private boolean pending = false; // so usado na thread principal
    private int timerTaskId = -1;

    public DiscordSync(CodigoResgate plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "discord.yml");
        String id = null;
        if (stateFile.exists()) {
            id = YamlConfiguration.loadConfiguration(stateFile).getString("message-id");
        }
        if (id == null || id.trim().isEmpty()) {
            id = plugin.getConfig().getString("discord.message-id", "");
        }
        this.messageId = id == null ? "" : id.trim();
    }

    // ------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------

    public void start() {
        // Atualiza logo depois de ligar o servidor
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                push(false);
            }
        }, 100L);

        // Atualizacao periodica (pega codigos que expiraram por tempo)
        int minutes = plugin.getConfig().getInt("discord.atualizar-a-cada-minutos", 5);
        if (minutes > 0) {
            long period = minutes * 1200L;
            timerTaskId = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    push(false);
                }
            }, period, period).getTaskId();
        }
    }

    public void stop() {
        if (timerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(timerTaskId);
        }
        executor.shutdown();
    }

    public boolean isConfigured() {
        return isValidUrl(webhookUrl());
    }

    private String webhookUrl() {
        String u = plugin.getConfig().getString("discord.webhook-url", "");
        return u == null ? "" : u.trim();
    }

    private static boolean isValidUrl(String url) {
        return url.startsWith("https://") && url.contains("/api/webhooks/");
    }

    // ------------------------------------------------------------
    // Pedidos de atualizacao (sempre na thread principal)
    // ------------------------------------------------------------

    /** Agenda uma atualizacao em ~2s (junta varias mudancas seguidas em uma so). */
    public void requestUpdate() {
        if (pending || !isConfigured()) {
            return;
        }
        pending = true;
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                pending = false;
                push(false);
            }
        }, 40L);
    }

    /** Monta o conteudo agora e envia em outra thread. */
    public void push(boolean force) {
        final String url = webhookUrl();
        if (!isValidUrl(url)) {
            return;
        }
        final String desc = buildDescription();
        if (!force && desc.equals(lastDesc)) {
            return; // nada mudou
        }
        final String json = buildJson(desc);
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    send(url, desc, json);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // plugin desligando
        }
    }

    // ------------------------------------------------------------
    // Conteudo da mensagem
    // ------------------------------------------------------------

    private String buildDescription() {
        java.util.List<Code> active = new java.util.ArrayList<Code>();
        for (Code c : plugin.allCodes()) {
            if (!c.isExpired() && !c.isExhausted()) {
                active.add(c);
            }
        }
        if (active.isEmpty()) {
            return "Nenhum c\u00F3digo dispon\u00EDvel no momento.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Use `/codigo NOME` no servidor para resgatar!\n\n");
        int shown = 0;
        for (Code c : active) {
            if (sb.length() > 3600) {
                sb.append("\n... e mais ").append(active.size() - shown).append(" c\u00F3digo(s).");
                break;
            }
            String uses = c.maxUses == -1
                    ? "ilimitado"
                    : (c.maxUses - c.used) + "/" + c.maxUses + " restantes";
            String exp = c.expiresAt == 0L
                    ? "sem prazo"
                    : "expira <t:" + (c.expiresAt / 1000L) + ":R>";
            sb.append("\u2022 **`").append(c.name).append("`** \u2014 ")
                    .append(uses).append(" \u2022 ").append(exp).append("\n");
            shown++;
        }
        return sb.toString();
    }

    private String buildJson(String desc) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(new SimpleTimeZone(0, "UTC"));
        String ts = f.format(new Date());
        return "{\"embeds\":[{\"title\":\"" + esc(TITLE)
                + "\",\"description\":\"" + esc(desc)
                + "\",\"color\":" + COLOR
                + ",\"footer\":{\"text\":\"" + esc(FOOTER)
                + "\"},\"timestamp\":\"" + ts
                + "\"}],\"allowed_mentions\":{\"parse\":[]}}";
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------
    // Envio (roda na thread do executor)
    // ------------------------------------------------------------

    private void send(String webhook, String desc, String json) {
        try {
            String id = messageId;
            if (id != null && id.matches("\\d+")) {
                int code = patch(webhook, id, json);
                if (code >= 200 && code < 300) {
                    lastDesc = desc;
                    return;
                }
                if (code >= 400 && code < 500 && code != 429) {
                    plugin.getLogger().warning("Nao consegui editar a mensagem " + id
                            + " (HTTP " + code + "). Criando uma nova mensagem.");
                } else {
                    plugin.getLogger().warning("Discord: falha ao editar a mensagem (HTTP " + code
                            + "). Vou tentar de novo na proxima atualizacao.");
                    return;
                }
            }
            String newId = post(webhook, json);
            if (newId != null) {
                messageId = newId;
                saveState(newId);
                lastDesc = desc;
                plugin.getLogger().info("Mensagem do Discord criada (id " + newId + ").");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Discord: erro ao enviar atualizacao: " + e.getMessage());
        }
    }

    /**
     * Editar mensagem de webhook exige o metodo PATCH, que o HttpURLConnection do Java
     * nao suporta. Por isso a requisicao e feita direto por um socket HTTPS.
     */
    private int patch(String webhook, String id, String json) throws IOException {
        URL u = new URL(webhook);
        String path = u.getPath() + "/messages/" + id;
        byte[] body = json.getBytes("UTF-8");
        int port = u.getPort() == -1 ? 443 : u.getPort();
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(u.getHost(), port);
        try {
            s.setSoTimeout(15000);
            String head = "PATCH " + path + " HTTP/1.1\r\n"
                    + "Host: " + u.getHost() + "\r\n"
                    + "User-Agent: " + UA + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes("UTF-8"));
            out.write(body);
            out.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String status = r.readLine(); // ex: HTTP/1.1 200 OK
            if (status == null) {
                return -1;
            }
            String[] parts = status.split(" ");
            if (parts.length < 2) {
                return -1;
            }
            return Integer.parseInt(parts[1].trim());
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
        }
    }

    private String post(String webhook, String json) throws IOException {
        URL u = new URL(webhook);
        URL target = new URL(u.getProtocol(), u.getHost(), u.getPort(), u.getPath() + "?wait=true");
        HttpURLConnection c = (HttpURLConnection) target.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", UA);
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                plugin.getLogger().warning("Discord respondeu HTTP " + code + " ao criar a mensagem.");
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"").matcher(sb);
            return m.find() ? m.group(1) : null;
        } finally {
            c.disconnect();
        }
    }

    private void saveState(String id) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("message-id", id);
        try {
            y.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nao consegui salvar discord.yml: " + e.getMessage());
        }
    }
}

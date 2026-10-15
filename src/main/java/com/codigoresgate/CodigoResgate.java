package com.codigoresgate;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CodigoResgate extends JavaPlugin {

    static final String PREFIX = "&8[&6Codigo&8] &r";
    static final String PREFIX_ADM = "&8[&6AdminCod&8] &r";
    static final String PERM_USO = "codigo.usar";
    static final String PERM_ADMIN = "codigo.admin";

    private final Map<String, Code> codes = new LinkedHashMap<String, Code>();
    private File dataFile;
    private Wizard wizard;
    private DiscordSync discord;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "codes.yml");
        load();
        wizard = new Wizard(this);
        getServer().getPluginManager().registerEvents(wizard, this);
        discord = new DiscordSync(this);
        discord.start();
        getLogger().info(codes.size() + " codigo(s) carregado(s).");
    }

    @Override
    public void onDisable() {
        saveFile();
        if (discord != null) {
            discord.stop();
        }
    }

    // ------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------

    static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void msg(CommandSender to, String text) {
        to.sendMessage(c(text));
    }

    void log(String line) {
        File f = new File(getDataFolder(), "log.txt");
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            w.println("[" + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()) + "] " + line);
        } catch (IOException e) {
            getLogger().warning("Nao consegui escrever no log: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------
    // Armazenamento (codes.yml)
    // ------------------------------------------------------------

    java.util.Collection<Code> allCodes() {
        return codes.values();
    }

    Code getCode(String name) {
        return codes.get(name.toLowerCase());
    }

    void addCode(Code code) {
        codes.put(code.name.toLowerCase(), code);
        save();
    }

    private void load() {
        codes.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yml.getConfigurationSection("codes");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            Code code = new Code();
            code.name = s.getString("name", key);
            code.creator = s.getString("creator", "?");
            code.maxUses = s.getInt("max-uses", -1);
            code.used = s.getInt("used", 0);
            code.expiresAt = s.getLong("expires-at", 0L);
            code.commands = new ArrayList<String>(s.getStringList("commands"));
            for (String u : s.getStringList("used-by")) {
                code.usedBy.add(u.toLowerCase());
            }
            codes.put(code.name.toLowerCase(), code);
        }
    }

    /** Salva no arquivo e atualiza a mensagem do Discord. */
    void save() {
        saveFile();
        if (discord != null) {
            discord.requestUpdate();
        }
    }

    private void saveFile() {
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Code code : codes.values()) {
            String base = "codes.c" + i + ".";
            yml.set(base + "name", code.name);
            yml.set(base + "creator", code.creator);
            yml.set(base + "max-uses", code.maxUses);
            yml.set(base + "used", code.used);
            yml.set(base + "expires-at", code.expiresAt);
            yml.set(base + "commands", code.commands);
            yml.set(base + "used-by", new ArrayList<String>(code.usedBy));
            i++;
        }
        try {
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Erro ao salvar codes.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("codigo")) {
            redeem(sender, args);
            return true;
        }
        if (name.equals("admcod")) {
            admin(sender, args);
            return true;
        }
        return false;
    }

    private void redeem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            msg(sender, PREFIX + "&cApenas jogadores podem resgatar codigos.");
            return;
        }
        Player p = (Player) sender;
        if (args.length < 1) {
            msg(p, PREFIX + "&eUso: /codigo <codigo>");
            return;
        }
        if (!p.hasPermission(PERM_USO)) {
            msg(p, PREFIX + "&cSem permissao.");
            return;
        }
        Code code = getCode(args[0]);
        if (code == null) {
            msg(p, PREFIX + "&cCodigo invalido.");
            return;
        }
        if (code.isExpired()) {
            msg(p, PREFIX + "&cCodigo expirado.");
            return;
        }
        String who = p.getName().toLowerCase();
        if (code.usedBy.contains(who)) {
            msg(p, PREFIX + "&cVoce ja usou este codigo.");
            return;
        }
        if (code.isExhausted()) {
            msg(p, PREFIX + "&cLimite de usos atingido.");
            return;
        }

        code.usedBy.add(who);
        code.used++;
        save();

        boolean show = getConfig().getBoolean("mostrar-comandos", false);
        msg(p, "");
        msg(p, "&8&m-----&r &6&lCodigo Resgatado! &8&m-----");
        msg(p, " &7Codigo: &f" + code.name);

        for (String entry : new ArrayList<String>(code.commands)) {
            boolean asConsole;
            String command;
            if (entry.startsWith("console:")) {
                asConsole = true;
                command = entry.substring(8);
            } else if (entry.startsWith("user:")) {
                asConsole = false;
                command = entry.substring(5);
            } else {
                continue;
            }
            command = command.replace("PLAYERNAME", p.getName());
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            try {
                if (asConsole) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } else {
                    p.performCommand(command);
                }
                if (show) {
                    msg(p, " &a+ &f" + command + (asConsole ? "" : " &7(player)"));
                }
            } catch (Exception ex) {
                getLogger().warning("Erro ao executar '" + command + "': " + ex.getMessage());
            }
        }

        if (!show) {
            msg(p, " &aRecompensa recebida!");
        }
        msg(p, "&8&m--------------------------");
        msg(p, "");
        log(p.getName() + " resgatou " + code.name);
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            msg(sender, PREFIX_ADM + "&cSem permissao.");
            return;
        }
        if (args.length == 0) {
            msg(sender, "");
            msg(sender, "&8&m----&r &6&lAdminCod &8&m----");
            msg(sender, " &e/admcod criar");
            msg(sender, " &e/admcod remover &f<nome>");
            msg(sender, " &e/admcod list");
            msg(sender, " &e/admcod list expirados");
            msg(sender, " &e/admcod list usados");
            msg(sender, " &e/admcod info &f<nome>");
            msg(sender, " &e/admcod resetar &f<cod> <player>");
            msg(sender, " &e/admcod atualizar &7(recarrega config e atualiza o Discord)");
            msg(sender, "&8&m--------------------");
            msg(sender, "");
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("criar")) {
            if (!(sender instanceof Player)) {
                msg(sender, PREFIX_ADM + "&cApenas jogadores podem criar codigos (o wizard usa o chat).");
                return;
            }
            wizard.start((Player) sender);
            return;
        }

        if (sub.equals("remover")) {
            if (args.length < 2) {
                msg(sender, PREFIX_ADM + "&cUso: /admcod remover <codigo>");
                return;
            }
            Code code = getCode(args[1]);
            if (code == null) {
                msg(sender, PREFIX_ADM + "&cCodigo nao existe.");
                return;
            }
            codes.remove(code.name.toLowerCase());
            save();
            msg(sender, PREFIX_ADM + "&aCodigo &f" + code.name + " &aremovido.");
            log(sender.getName() + " removeu " + code.name);
            return;
        }

        if (sub.equals("list")) {
            if (codes.isEmpty()) {
                msg(sender, PREFIX_ADM + "&eNenhum codigo cadastrado.");
                return;
            }
            String filter = args.length > 1 ? args[1].toLowerCase() : "";
            msg(sender, "");
            if (filter.equals("expirados")) {
                msg(sender, "&8&m----&r &6&lExpirados &8&m----");
            } else if (filter.equals("usados")) {
                msg(sender, "&8&m----&r &6&lEsgotados &8&m----");
            } else {
                msg(sender, "&8&m----&r &6&lTodos os Codigos &8&m----");
            }
            for (Code code : codes.values()) {
                boolean exp = code.isExpired();
                boolean esg = code.isExhausted();
                String esgText = " &7(Esgotado | " + code.used + "/" + code.maxUses + ")";
                if (filter.equals("expirados")) {
                    if (exp) {
                        msg(sender, " &c* &f" + code.name + " &7(Expirado)");
                    }
                } else if (filter.equals("usados")) {
                    if (esg) {
                        msg(sender, " &c* &f" + code.name + esgText);
                    }
                } else {
                    if (exp) {
                        msg(sender, " &c* &f" + code.name + " &7(Expirado)");
                    } else if (esg) {
                        msg(sender, " &6* &f" + code.name + esgText);
                    } else if (code.maxUses == -1) {
                        msg(sender, " &a* &f" + code.name + " &7(" + code.used + " usados | Ilimitado)");
                    } else {
                        msg(sender, " &a* &f" + code.name + " &7(" + code.used + "/" + code.maxUses + " usos)");
                    }
                }
            }
            msg(sender, "&8&m--------------------");
            msg(sender, "");
            return;
        }

        if (sub.equals("info")) {
            if (args.length < 2) {
                msg(sender, PREFIX_ADM + "&cUso: /admcod info <codigo>");
                return;
            }
            Code code = getCode(args[1]);
            if (code == null) {
                msg(sender, PREFIX_ADM + "&cCodigo nao existe.");
                return;
            }
            msg(sender, "");
            msg(sender, "&8&m----&r &6&lInfo: " + code.name + " &8&m----");
            msg(sender, " &7Criador: &f" + code.creator);
            if (code.maxUses == -1) {
                msg(sender, " &7Usos: &fIlimitado &7(" + code.used + " usados)");
            } else {
                msg(sender, " &7Usos: &f" + code.used + "/" + code.maxUses);
            }
            if (code.expiresAt == 0L) {
                msg(sender, " &7Expira: &fNunca");
            } else if (code.isExpired()) {
                msg(sender, " &7Expira: &cJa expirou");
            } else {
                String date = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(code.expiresAt));
                msg(sender, " &7Expira em: &f" + date);
            }
            msg(sender, " &7Comandos:");
            for (String cmdLine : code.commands) {
                msg(sender, "  &8- &f" + cmdLine);
            }
            msg(sender, "&8&m--------------------");
            msg(sender, "");
            return;
        }

        if (sub.equals("resetar")) {
            if (args.length < 3) {
                msg(sender, PREFIX_ADM + "&cUso: /admcod resetar <codigo> <player>");
                return;
            }
            Code code = getCode(args[1]);
            if (code == null) {
                msg(sender, PREFIX_ADM + "&cCodigo nao existe.");
                return;
            }
            String target = args[2].toLowerCase();
            if (code.usedBy.remove(target)) {
                if (code.used > 0) {
                    code.used--;
                }
                save();
            }
            msg(sender, PREFIX_ADM + "&aUso de &f" + code.name + " &aresetado para &f" + args[2] + ".");
            log(sender.getName() + " resetou " + code.name + " para " + args[2]);
            return;
        }

        if (sub.equals("atualizar")) {
            reloadConfig();
            if (!discord.isConfigured()) {
                msg(sender, PREFIX_ADM + "&cWebhook do Discord nao configurado em config.yml (discord.webhook-url).");
                return;
            }
            discord.push(true);
            msg(sender, PREFIX_ADM + "&aEnviando atualizacao pro Discord... se nao aparecer, veja o console.");
            return;
        }

        msg(sender, PREFIX_ADM + "&cSubcomando desconhecido. Use /admcod para ajuda.");
    }
}

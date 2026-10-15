package com.codigoresgate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Assistente de criacao de codigos via chat. */
public class Wizard implements Listener {

    private static class State {
        int step = 1;
        String name;
        int uses;
        int minutes;
        String type;
        List<String> cmds = new ArrayList<String>();
    }

    private final CodigoResgate plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<UUID, State>();

    public Wizard(CodigoResgate plugin) {
        this.plugin = plugin;
    }

    private void send(Player p, String text) {
        p.sendMessage(CodigoResgate.c(text));
    }

    public void start(Player p) {
        if (states.containsKey(p.getUniqueId())) {
            send(p, CodigoResgate.PREFIX_ADM + "&cVoce ja tem um wizard aberto. Digite &fcancelar &cno chat.");
            return;
        }
        states.put(p.getUniqueId(), new State());
        send(p, "");
        send(p, "&8&m----&r &6&lCriar Codigo &8&m----");
        send(p, " &7Digite &fcancelar &7para cancelar a qualquer momento.");
        send(p, "");
        send(p, " &e[1/4] &fQual o nome do codigo? &7(letras, numeros, _ e -)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!states.containsKey(e.getPlayer().getUniqueId())) {
            return;
        }
        e.setCancelled(true);
        final Player p = e.getPlayer();
        final String msg = e.getMessage().trim();
        // O chat roda em outra thread; processamos na thread principal do servidor
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                handle(p, msg);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    private void handle(Player p, String msg) {
        State s = states.get(p.getUniqueId());
        if (s == null) {
            return;
        }

        if (msg.equalsIgnoreCase("cancelar")) {
            states.remove(p.getUniqueId());
            send(p, CodigoResgate.PREFIX_ADM + "&cCriacao cancelada.");
            return;
        }

        switch (s.step) {
            case 1: { // nome
                if (!msg.matches("[A-Za-z0-9_\\-]+")) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cUse apenas letras, numeros, _ e - (sem espacos). Digite outro:");
                    return;
                }
                if (plugin.getCode(msg) != null) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cJa existe um codigo com esse nome. Digite outro:");
                    return;
                }
                s.name = msg;
                s.step = 2;
                send(p, "");
                send(p, " &e[2/4] &fUsos maximos e tempo &7(minutos) separados por espaco.");
                send(p, " &7Exemplo: &f100 1440 &8(100 usos, 1 dia)");
                send(p, " &7Ilimitado: &f-1 -1");
                return;
            }
            case 2: { // usos e tempo
                String[] parts = msg.split("\\s+");
                if (parts.length < 2) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cDigite dois valores separados por espaco. Ex: -1 -1");
                    return;
                }
                int uses;
                int minutes;
                try {
                    uses = Integer.parseInt(parts[0]);
                } catch (NumberFormatException ex) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cValor de usos invalido.");
                    return;
                }
                try {
                    minutes = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cValor de tempo invalido.");
                    return;
                }
                if (uses != -1 && uses < 1) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cUsos deve ser -1 (ilimitado) ou maior que 0.");
                    return;
                }
                if (minutes != -1 && minutes < 1) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cTempo deve ser -1 (nunca expira) ou maior que 0.");
                    return;
                }
                s.uses = uses;
                s.minutes = minutes;
                s.step = 3;
                askType(p, false);
                return;
            }
            case 3: { // tipo
                if (msg.equals("1")) {
                    s.type = "console";
                    s.step = 4;
                    send(p, "");
                    send(p, " &e[4/4] &fDigite o comando &a(console)&f:");
                    send(p, " &7Para o nome do jogador use: &fPLAYERNAME");
                    send(p, " &7Exemplo: &feco give PLAYERNAME 2000");
                } else if (msg.equals("2")) {
                    s.type = "user";
                    s.step = 4;
                    send(p, "");
                    send(p, " &e[4/4] &fDigite o comando &a(player)&f:");
                    send(p, " &7Para o nome do jogador use: &fPLAYERNAME");
                    send(p, " &7Exemplo: &fspawn");
                } else {
                    send(p, CodigoResgate.PREFIX_ADM + "&cOpcao invalida. Digite 1 ou 2.");
                }
                return;
            }
            case 4: { // comando
                String command = msg;
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (command.isEmpty()) {
                    send(p, CodigoResgate.PREFIX_ADM + "&cComando vazio. Digite o comando:");
                    return;
                }
                s.cmds.add(s.type + ":" + command);
                s.step = 5;
                send(p, "");
                send(p, " &a+ &fComando salvo: &8[" + s.type + "] &f" + command);
                send(p, "");
                send(p, " &eDeseja adicionar mais um comando?");
                send(p, "  &f1 &7- Sim");
                send(p, "  &f2 &7- Nao, finalizar");
                return;
            }
            case 5: { // mais um ou finalizar
                if (msg.equals("1")) {
                    s.step = 3;
                    askType(p, true);
                } else if (msg.equals("2")) {
                    finish(p, s);
                } else {
                    send(p, CodigoResgate.PREFIX_ADM + "&cOpcao invalida. Digite 1 ou 2.");
                }
                return;
            }
            default:
                states.remove(p.getUniqueId());
        }
    }

    private void askType(Player p, boolean extra) {
        send(p, "");
        send(p, extra ? " &e[+] &fTipo do novo comando:" : " &e[3/4] &fTipo do comando:");
        send(p, "  &f1 &7- Console &8(servidor executa)");
        send(p, "  &f2 &7- Player &8(jogador executa)");
    }

    private void finish(Player p, State s) {
        Code code = new Code();
        code.name = s.name.toLowerCase();
        code.creator = p.getName();
        code.maxUses = s.uses;
        code.used = 0;
        code.expiresAt = s.minutes == -1 ? 0L : System.currentTimeMillis() + s.minutes * 60000L;
        code.commands = new ArrayList<String>(s.cmds);
        plugin.addCode(code);
        states.remove(p.getUniqueId());

        send(p, "");
        send(p, "&8&m-----&r &a&lCodigo Criado! &8&m-----");
        send(p, " &7Nome: &f" + code.name);
        send(p, s.uses == -1 ? " &7Usos: &fIlimitado" : " &7Usos: &f" + s.uses);
        send(p, s.minutes == -1 ? " &7Expira: &fNunca" : " &7Expira em: &f" + s.minutes + " min");
        send(p, " &7Comandos:");
        for (String c : code.commands) {
            send(p, "  &8- &f" + c);
        }
        send(p, "&8&m--------------------------");
        send(p, "");
        plugin.log(p.getName() + " criou " + code.name + " | Usos: " + s.uses + " | Tempo: " + s.minutes);
    }
}

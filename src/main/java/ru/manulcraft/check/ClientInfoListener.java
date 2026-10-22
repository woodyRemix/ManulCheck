package ru.manulcraft.check;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ClientInfoListener implements Listener {

    private final ManulCheck plugin;
    /** Что уже показывали персоналу, чтобы не спамить на каждый канал. */
    private final Map<UUID, Set<String>> alerted = new ConcurrentHashMap<>();

    ClientInfoListener(ManulCheck plugin) {
        this.plugin = plugin;
    }

    static String describe(ManulCheck plugin, Player p) {
        String brand = p.getClientBrandName();
        Set<String> channels = new TreeSet<>(p.getListeningPluginChannels());
        return "клиент=" + (brand == null ? "?" : brand)
                + ", версия=" + viaVersion(p)
                + ", каналы=" + (channels.isEmpty() ? "нет" : String.join(" ", channels));
    }

    private static String viaVersion(Player p) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            int proto = (int) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, p.getUniqueId());
            Class<?> pv = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Object ver = pv.getMethod("getProtocol", int.class).invoke(null, proto);
            return String.valueOf(pv.getMethod("getName").invoke(ver));
        } catch (Throwable t) {
            return "?";
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        alerted.remove(p.getUniqueId());
        // Бренд приходит чуть позже входа.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) check(p, p.getClientBrandName(), "клиент");
        }, 60L);
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        check(e.getPlayer(), e.getChannel(), "канал мода");
    }

    private void check(Player p, String value, String what) {
        if (value == null || !plugin.getConfig().getBoolean("suspicious.enabled", true)) return;
        String low = value.toLowerCase(Locale.ROOT);
        List<String> needles = plugin.getConfig().getStringList(what.equals("клиент") ? "suspicious.brands" : "suspicious.channels");
        for (String n : needles) {
            if (n.isEmpty() || !low.contains(n.toLowerCase(Locale.ROOT))) continue;
            if (!alerted.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(low)) return;
            String text = plugin.getConfig().getString("messages.prefix", "") + "&6Подозрительный " + what + " у &e"
                    + p.getName() + "&6: &f" + value + " &7(/check info " + p.getName() + ")";
            var c = ManulCheck.LEGACY.deserialize(text);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("manulcheck.spy")) staff.sendMessage(c);
            }
            plugin.log("ALERT " + p.getName() + " " + what + "=" + value);
            return;
        }
    }
}

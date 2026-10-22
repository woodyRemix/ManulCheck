package ru.manulcraft.check;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class ManulCheck extends JavaPlugin implements TabCompleter {

    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    final Map<UUID, CheckSession> sessions = new ConcurrentHashMap<>();
    final Set<UUID> kicked = ConcurrentHashMap.newKeySet();

    private BukkitTask ticker;
    private int tick;

    static final class CheckSession {
        final UUID target;
        final String targetName;
        UUID checker;
        String checkerName;
        final long startedAt = System.currentTimeMillis();
        final boolean hadAllowFlight;
        final boolean wasFlying;
        final BossBar bar;

        CheckSession(Player target, CommandSender checker, BossBar bar) {
            this.target = target.getUniqueId();
            this.targetName = target.getName();
            this.checker = checker instanceof Player p ? p.getUniqueId() : null;
            this.checkerName = checker.getName();
            this.hadAllowFlight = target.getAllowFlight();
            this.wasFlying = target.isFlying();
            this.bar = bar;
        }

        String elapsed() {
            long s = (System.currentTimeMillis() - startedAt) / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new FreezeListener(this), this);
        getServer().getPluginManager().registerEvents(new ClientInfoListener(this), this);
        getCommand("check").setExecutor(this);
        getCommand("check").setTabCompleter(this);
        getCommand("cc").setExecutor(this);
        getCommand("cc").setTabCompleter(this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        // Выключение сервера — не повод банить: просто отпускаем всех.
        for (CheckSession s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.target);
            if (p != null) restore(p, s);
            log("STOP " + s.targetName + " (server shutdown)");
        }
        sessions.clear();
    }


    Component msg(String key, String... repl) {
        return LEGACY.deserialize(fmt(getConfig().getString("messages." + key, key), repl));
    }

    Component prefixed(String key, String... repl) {
        return LEGACY.deserialize(getConfig().getString("messages.prefix", "") + fmt(getConfig().getString("messages." + key, key), repl));
    }

    static String fmt(String s, String... repl) {
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace("{" + repl[i] + "}", repl[i + 1]);
        return s;
    }

    boolean isFrozen(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    void sendToStaff(CheckSession s, Component c) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(s.checker) || p.hasPermission("manulcheck.spy")) p.sendMessage(c);
        }
        Bukkit.getConsoleSender().sendMessage(c);
    }

    void log(String line) {
        File f = new File(getDataFolder(), "checks.log");
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            w.println("[" + LocalDateTime.now().format(LOG_TIME) + "] " + line);
        } catch (IOException e) {
            getLogger().warning("checks.log: " + e.getMessage());
        }
        getLogger().info(line);
    }

    void runConsole(String command, String player, String checker) {
        String cmd = fmt(command, "player", player, "checker", checker);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        String finalCmd = cmd;
        Runnable r = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
        if (Bukkit.isPrimaryThread()) r.run(); else Bukkit.getScheduler().runTask(this, r);
        log("CMD " + cmd);
    }


    void freeze(Player target, CommandSender checker) {
        BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        CheckSession s = new CheckSession(target, checker, bar);
        sessions.put(target.getUniqueId(), s);

        if (target.isInsideVehicle()) target.leaveVehicle();
        if (target.isGliding()) target.setGliding(false);
        target.closeInventory();
        // Чтобы античит/ванилла не кикали за «полёт», если заморозили в воздухе.
        target.setAllowFlight(true);
        target.setFlying(!target.isOnGround());
        target.setFallDistance(0f);
        target.showBossBar(bar);
        remind(target, s);

        sendToStaff(s, prefixed("frozen-staff", "player", target.getName(), "checker", s.checkerName));
        log("FREEZE " + target.getName() + " by " + s.checkerName + " | " + ClientInfoListener.describe(this, target));
    }

    void release(CheckSession s, String reason) {
        sessions.remove(s.target);
        Player p = Bukkit.getPlayer(s.target);
        if (p != null) {
            restore(p, s);
            p.sendMessage(prefixed("released"));
            p.clearTitle();
        }
        sendToStaff(s, prefixed("released-staff", "player", s.targetName, "checker", s.checkerName));
        log("RELEASE " + s.targetName + " by " + reason + " after " + s.elapsed());
    }

    private void restore(Player p, CheckSession s) {
        p.hideBossBar(s.bar);
        p.setFlying(false);
        p.setAllowFlight(s.hadAllowFlight);
        if (s.hadAllowFlight) p.setFlying(s.wasFlying);
        p.setFallDistance(0f);
    }

    void handleQuit(Player p) {
        boolean wasKicked = kicked.remove(p.getUniqueId());
        CheckSession s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        p.hideBossBar(s.bar);
        if (wasKicked || Bukkit.isStopping()) {
            log("LEFT " + s.targetName + " (kicked/shutdown, no ban) after " + s.elapsed());
            sendToStaff(s, LEGACY.deserialize(getConfig().getString("messages.prefix", "") + "&e" + s.targetName + " кикнут во время проверки, бана нет."));
            return;
        }
        log("QUIT " + s.targetName + " during check by " + s.checkerName + " after " + s.elapsed());
        if (!getConfig().getBoolean("quit-ban.enabled", true)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.isStopping()) return;
            runConsole(getConfig().getString("quit-ban.command", "litebans:ban {player} 30d Выход во время проверки"), s.targetName, s.checkerName);
            sendToStaff(s, prefixed("quit-staff", "player", s.targetName, "checker", s.checkerName));
        }, 100L);
    }

    private void remind(Player p, CheckSession s) {
        p.showTitle(Title.title(msg("title"), msg("subtitle"),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(6), Duration.ofMillis(500))));
        String prefix = getConfig().getString("messages.prefix", "");
        for (String line : getConfig().getStringList("messages.reminder")) {
            p.sendMessage(LEGACY.deserialize(prefix + line));
        }
    }

    private void tick() {
        tick++;
        int every = Math.max(1, getConfig().getInt("reminder-interval", 5));
        for (CheckSession s : sessions.values()) {
            Player p = Bukkit.getPlayer(s.target);
            if (p == null) continue;
            s.bar.name(msg("bossbar", "time", s.elapsed(), "checker", s.checkerName));
            if (tick % every == 0) {
                p.showTitle(Title.title(msg("title"), msg("subtitle"),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(every + 1), Duration.ZERO)));
            }
            if (tick % (every * 6) == 0) remind(p, s);
        }
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("cc")) return chatCommand(sender, args);

        if (args.length == 0) {
            sender.sendMessage(LEGACY.deserialize("&e/check <ник> &7— заморозить на проверку\n"
                    + "&e/check stop <ник> &7— отпустить\n"
                    + "&e/check ban <ник> &7— бан по итогам и снять заморозку\n"
                    + "&e/check info <ник> &7— клиент, версия, каналы модов\n"
                    + "&e/check list &7— кто сейчас на проверке\n"
                    + "&e/cc [ник] <текст> &7— написать игроку на проверке"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                if (sessions.isEmpty()) {
                    sender.sendMessage(LEGACY.deserialize("&7Сейчас никого не проверяют."));
                } else {
                    for (CheckSession s : sessions.values()) {
                        sender.sendMessage(LEGACY.deserialize("&e" + s.targetName + " &7— проверяет &f" + s.checkerName + " &7(" + s.elapsed() + ")"));
                    }
                }
                return true;
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(LEGACY.deserialize("&aКонфиг ManulCheck перезагружен."));
                return true;
            }
            case "stop", "unfreeze", "off", "ban", "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(LEGACY.deserialize("&c/check " + sub + " <ник>"));
                    return true;
                }
                if (sub.equals("info")) {
                    Player t = Bukkit.getPlayerExact(args[1]);
                    if (t == null) { sender.sendMessage(prefixed("not-online")); return true; }
                    sender.sendMessage(LEGACY.deserialize("&e" + t.getName() + "&7: &f" + ClientInfoListener.describe(this, t)));
                    return true;
                }
                CheckSession s = findSession(args[1]);
                if (s == null) { sender.sendMessage(prefixed("not-frozen")); return true; }
                if (sub.equals("ban")) {
                    sessions.remove(s.target);
                    Player p = Bukkit.getPlayer(s.target);
                    if (p != null) restore(p, s);
                    log("BAN " + s.targetName + " by " + sender.getName() + " after " + s.elapsed());
                    runConsole(getConfig().getString("manual-ban.command", "litebans:ban {player} 30d Читы"), s.targetName, sender.getName());
                } else {
                    release(s, sender.getName());
                }
                return true;
            }
            default -> {
                Player t = Bukkit.getPlayerExact(args[0]);
                if (t == null) { sender.sendMessage(prefixed("not-online")); return true; }
                CheckSession existing = sessions.get(t.getUniqueId());
                if (existing != null) {
                    sender.sendMessage(prefixed("already", "checker", existing.checkerName));
                    return true;
                }
                if (t.hasPermission("manulcheck.bypass") || t.equals(sender)) {
                    sender.sendMessage(prefixed("bypass"));
                    return true;
                }
                freeze(t, sender);
                return true;
            }
        }
    }

    private CheckSession findSession(String name) {
        for (CheckSession s : sessions.values()) {
            if (s.targetName.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    private boolean chatCommand(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        CheckSession target = findSession(args[0]);
        int from = 1;
        if (target == null || args.length == 1) {
            List<CheckSession> mine = sessions.values().stream()
                    .filter(s -> sender instanceof ConsoleCommandSender ? s.checker == null
                            : sender instanceof Player p && p.getUniqueId().equals(s.checker))
                    .collect(Collectors.toList());
            if (mine.size() != 1 && sessions.size() == 1) mine = new ArrayList<>(sessions.values());
            if (mine.size() != 1) { sender.sendMessage(prefixed("no-target")); return true; }
            target = mine.get(0);
            from = 0;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
        // Кто пишет — тот и становится проверяющим (если первый проверяющий ушёл, подхватывает другой).
        if (sender instanceof Player p && !p.getUniqueId().equals(target.checker)) {
            target.checker = p.getUniqueId();
            target.checkerName = p.getName();
        }
        Component c = LEGACY.deserialize(fmt(getConfig().getString("messages.chat-from-checker", "{checker} -> {player}: {message}"),
                "checker", sender.getName(), "player", target.targetName)
                .replace("{message}", "")).append(Component.text(text));
        Player t = Bukkit.getPlayer(target.target);
        if (t != null) t.sendMessage(c);
        sendToStaff(target, c);
        log("CHAT " + sender.getName() + " -> " + target.targetName + ": " + text);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("cc")) {
            if (args.length == 1) return names(sessions.values().stream().map(s -> s.targetName).toList(), args[0]);
            return List.of();
        }
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("stop", "ban", "info", "list", "reload"));
            Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            return names(opts, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("stop") || sub.equals("ban")) return names(sessions.values().stream().map(s -> s.targetName).toList(), args[1]);
            if (sub.equals("info")) return names(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> names(Collection<String> all, String start) {
        String low = start.toLowerCase(Locale.ROOT);
        return all.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(low)).sorted().toList();
    }
}

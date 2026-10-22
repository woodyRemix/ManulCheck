package ru.manulcraft.check;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.Event;

import java.util.List;
import java.util.Locale;

final class FreezeListener implements Listener {

    private final ManulCheck plugin;

    FreezeListener(ManulCheck plugin) {
        this.plugin = plugin;
    }

    private boolean frozen(Entity e) {
        return e instanceof Player p && plugin.isFrozen(p);
    }

    private void block(Entity e, Cancellable ev) {
        if (frozen(e)) ev.setCancelled(true);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.isFrozen(e.getPlayer())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            // Крутить головой можно (удобно показывать экран), ходить нельзя.
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!plugin.isFrozen(e.getPlayer())) return;
        switch (e.getCause()) {
            case ENDER_PEARL, CHORUS_FRUIT, NETHER_PORTAL, END_PORTAL, END_GATEWAY -> e.setCancelled(true);
            default -> { } // телепорт персоналом/плагином разрешён
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        // Не даём «выключить» полёт и упасть, если заморозили в воздухе.
        if (plugin.isFrozen(e.getPlayer()) && !e.isFlying() && !e.getPlayer().isOnGround()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicle(VehicleEnterEvent e) {
        block(e.getEntered(), e);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        block(e.getEntity(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) d = shooter;
        block(d, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Entity shooter) block(shooter, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() != null) block(e.getTarget(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent e) {
        block(e.getEntity(), e);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (!plugin.isFrozen(e.getPlayer())) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        block(e.getWhoClicked(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        block(e.getWhoClicked(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        block(e.getEntity(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        block(e.getPlayer(), e);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!plugin.isFrozen(e.getPlayer())) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        List<String> allowed = plugin.getConfig().getStringList("allowed-commands");
        if (allowed.contains(label)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(plugin.prefixed("command-blocked"));
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent e) {
        ManulCheck.CheckSession s = plugin.sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        e.getRecipients().clear();
        String line = ManulCheck.fmt(plugin.getConfig().getString("messages.chat-from-target", "{player}: {message}"),
                "player", e.getPlayer().getName()).replace("{message}", "");
        var c = ManulCheck.LEGACY.deserialize(line).append(net.kyori.adventure.text.Component.text(e.getMessage()));
        e.getPlayer().sendMessage(c);
        plugin.sendToStaff(s, c);
        plugin.log("CHAT " + e.getPlayer().getName() + ": " + e.getMessage());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        if (plugin.isFrozen(e.getPlayer())) {
            e.setCancelled(true);
            e.viewers().clear();
        }
    }


    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent e) {
        // Прощаем только кик ванильной /kick от персонала. Кики античита/таймаут не прощаем:
        // иначе можно «случайно» вылететь со спама или выдернув интернет.
        if (!e.isCancelled() && plugin.isFrozen(e.getPlayer()) && e.getCause() == PlayerKickEvent.Cause.KICK_COMMAND) {
            plugin.kicked.add(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.handleQuit(e.getPlayer());
    }
}

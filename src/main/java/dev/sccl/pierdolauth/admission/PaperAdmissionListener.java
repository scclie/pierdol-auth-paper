package dev.sccl.pierdolauth.admission;

import java.util.Optional;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Collections;
import dev.sccl.pierdolauth.whitelist.WhitelistRegistry;
import dev.sccl.pierdolauth.api.GameAuthModels.ClientType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.vehicle.*;
import io.papermc.paper.event.player.AsyncChatEvent;

public final class PaperAdmissionListener implements Listener, AutoCloseable {
    private static final String UNAVAILABLE = "Authentication service is unavailable. Please try again later.";
    private final AdmissionService admission;
    private final WhitelistRegistry registry;
    // writes belong to the main thread; async chat only reads under this map's monitor
    private final Map<Player, AdmissionService.ConnectionToken> tokens =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile boolean closed;

    public PaperAdmissionListener(AdmissionService admission, WhitelistRegistry registry) {
        this.admission = admission;
        this.registry = registry;
    }

    static Optional<String> parsePassword(String message) {
        return message.startsWith("/login ") && message.length() > 7
                ? Optional.of(message.substring(7)) : Optional.empty();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        var snapshot = registry.snapshot();
        if (closed || !snapshot.ready() || !snapshot.clientTypes().containsKey(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, UNAVAILABLE);
        } else if (snapshot.clientTypes().get(event.getUniqueId()) == ClientType.LICENSED) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Premium authentication is not available yet.");
        }
        // never override Paper's own whitelist or another admission denial.
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (closed) return;
        Player player = event.getPlayer();
        synchronized (tokens) {
            tokens.keySet().removeIf(old -> old.getUniqueId().equals(player.getUniqueId()));
        }
        var address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            player.kick(net.kyori.adventure.text.Component.text(UNAVAILABLE));
            return;
        }
        var token = admission.join(new ConnectionIdentity(player.getUniqueId(), player.getName(), address.getAddress()));
        if (admission.frozen(token)) tokens.put(player, token);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        var token = tokens.remove(event.getPlayer());
        if (!closed && token != null) admission.quit(token);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        var token = tokens.get(event.getPlayer());
        String message = event.getMessage();
        var password = parsePassword(message);
        // pub events cannot hide input from a pre-existing/malicious earlier listener.
        if (message.startsWith("/login ") || message.equals("/login")) {
            event.setMessage("/login");
            event.setCancelled(true);
            if (!closed && token != null && admission.frozen(token)) {
                admission.command(token, password.orElse("").toCharArray());
            }
        } else {
            cancelIfFrozen(token, event);
        }
    }

    void cancelIfFrozen(AdmissionService.ConnectionToken token, Cancellable event) {
        if (closed || token == null || admission.frozen(token)) event.setCancelled(true);
    }

    private void gate(Player player, Cancellable event) { cancelIfFrozen(tokens.get(player), event); }
    private void gateEntity(org.bukkit.entity.Entity entity, Cancellable event) {
        if (entity instanceof Player player) gate(player, event);
    }

    /** main-thread action bridge: a cancelled Paper kick must not retain gameplay authority. */
    public void kick(Player player, String reason) {
        tokens.remove(player);
        player.kick(net.kyori.adventure.text.Component.text(reason));
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onMove(PlayerMoveEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onTeleport(PlayerTeleportEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onSneak(PlayerToggleSneakEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onSprint(PlayerToggleSprintEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onFlight(PlayerToggleFlightEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onGlide(EntityToggleGlideEvent e) { gateEntity(e.getEntity(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBreak(BlockBreakEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPlace(BlockPlaceEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInteract(PlayerInteractEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onEntityInteract(PlayerInteractEntityEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onEntityInteractAt(PlayerInteractAtEntityEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        gateEntity(event.getTarget(), event);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onDamage(EntityDamageEvent e) { gateEntity(e.getEntity(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDamageByEntity(EntityDamageByEntityEvent e) {
        gateEntity(e.getEntity(), e);
        gateEntity(e.getDamager(), e);
        if (e.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) gate(player, e);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onInventoryClick(InventoryClickEvent e) { gateEntity(e.getWhoClicked(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInventoryDrag(InventoryDragEvent e) { gateEntity(e.getWhoClicked(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInventoryOpen(InventoryOpenEvent e) { gateEntity(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPickup(EntityPickupItemEvent e) { gateEntity(e.getEntity(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDrop(PlayerDropItemEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onSwap(PlayerSwapHandItemsEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onHeld(PlayerItemHeldEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onEditBook(PlayerEditBookEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onConsume(PlayerItemConsumeEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBucketEmpty(PlayerBucketEmptyEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBucketFill(PlayerBucketFillEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onFish(PlayerFishEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onVehicleEnter(VehicleEnterEvent e) { gateEntity(e.getEntered(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onVehicleExit(VehicleExitEvent e) { gateEntity(e.getExited(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPortal(PlayerPortalEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onChat(AsyncChatEvent e) { gate(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onLegacyChat(AsyncPlayerChatEvent e) { gate(e.getPlayer(), e); }

    @Override public void close() {
        closed = true;
        tokens.clear();
        HandlerList.unregisterAll(this);
    }
}

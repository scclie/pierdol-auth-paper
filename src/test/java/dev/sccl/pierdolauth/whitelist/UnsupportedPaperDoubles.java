package dev.sccl.pierdolauth.whitelist;

/** Concrete-interface scaffolding: unexpected Paper operations fail, without proxies or a runtime. */
@SuppressWarnings({"deprecation", "removal"})
final class UnsupportedPaperDoubles {
    private UnsupportedPaperDoubles() {}

    abstract static class Server implements org.bukkit.Server {
        @Override public java.io.File getPluginsFolder() { throw new AssertionError(); }
        @Override public String getName() { throw new AssertionError(); }
        @Override public String getVersion() { throw new AssertionError(); }
        @Override public String getBukkitVersion() { throw new AssertionError(); }
        @Override public String getMinecraftVersion() { throw new AssertionError(); }
        @Override public java.util.Collection<? extends org.bukkit.entity.Player> getOnlinePlayers() { throw new AssertionError(); }
        @Override public int getMaxPlayers() { throw new AssertionError(); }
        @Override public void setMaxPlayers(int value) { throw new AssertionError(); }
        @Override public int getPort() { throw new AssertionError(); }
        @Override public int getViewDistance() { throw new AssertionError(); }
        @Override public int getSimulationDistance() { throw new AssertionError(); }
        @Override public String getIp() { throw new AssertionError(); }
        @Override public String getWorldType() { throw new AssertionError(); }
        @Override public boolean getGenerateStructures() { throw new AssertionError(); }
        @Override public int getMaxWorldSize() { throw new AssertionError(); }
        @Override public boolean getAllowEnd() { throw new AssertionError(); }
        @Override public boolean getAllowNether() { throw new AssertionError(); }
        @Override public boolean isLoggingIPs() { throw new AssertionError(); }
        @Override public java.util.List<String> getInitialEnabledPacks() { throw new AssertionError(); }
        @Override public java.util.List<String> getInitialDisabledPacks() { throw new AssertionError(); }
        @Override public org.bukkit.ServerTickManager getServerTickManager() { throw new AssertionError(); }
        @Override public org.bukkit.packs.ResourcePack getServerResourcePack() { throw new AssertionError(); }
        @Override public String getResourcePack() { throw new AssertionError(); }
        @Override public String getResourcePackHash() { throw new AssertionError(); }
        @Override public String getResourcePackPrompt() { throw new AssertionError(); }
        @Override public boolean isResourcePackRequired() { throw new AssertionError(); }
        @Override public boolean hasWhitelist() { throw new AssertionError(); }
        @Override public void setWhitelist(boolean value) { throw new AssertionError(); }
        @Override public boolean isWhitelistEnforced() { throw new AssertionError(); }
        @Override public void setWhitelistEnforced(boolean value) { throw new AssertionError(); }
        @Override public void reloadWhitelist() { throw new AssertionError(); }
        @Override public String getUpdateFolder() { throw new AssertionError(); }
        @Override public java.io.File getUpdateFolderFile() { throw new AssertionError(); }
        @Override public long getConnectionThrottle() { throw new AssertionError(); }
        @Override public int getTicksPerSpawns(org.bukkit.entity.SpawnCategory category) { throw new AssertionError(); }
        @Override public org.bukkit.entity.Player getPlayer(String name) { throw new AssertionError(); }
        @Override public org.bukkit.entity.Player getPlayerExact(String name) { throw new AssertionError(); }
        @Override public java.util.List<org.bukkit.entity.Player> matchPlayer(String name) { throw new AssertionError(); }
        @Override public org.bukkit.entity.Player getPlayer(java.util.UUID uuid) { throw new AssertionError(); }
        @Override public java.util.UUID getPlayerUniqueId(String name) { throw new AssertionError(); }
        @Override public org.bukkit.plugin.PluginManager getPluginManager() { throw new AssertionError(); }
        @Override public org.bukkit.scheduler.BukkitScheduler getScheduler() { throw new AssertionError(); }
        @Override public org.bukkit.plugin.ServicesManager getServicesManager() { throw new AssertionError(); }
        @Override public java.util.List<org.bukkit.World> getWorlds() { throw new AssertionError(); }
        @Override public boolean isTickingWorlds() { throw new AssertionError(); }
        @Override public org.bukkit.World createWorld(org.bukkit.WorldCreator creator) { throw new AssertionError(); }
        @Override public boolean unloadWorld(String name, boolean save) { throw new AssertionError(); }
        @Override public boolean unloadWorld(org.bukkit.World world, boolean save) { throw new AssertionError(); }
        @Override public org.bukkit.World getRespawnWorld() { throw new AssertionError(); }
        @Override public void setRespawnWorld(org.bukkit.World world) { throw new AssertionError(); }
        @Override public org.bukkit.World getWorld(String name) { throw new AssertionError(); }
        @Override public org.bukkit.World getWorld(java.util.UUID uuid) { throw new AssertionError(); }
        @Override public org.bukkit.World getWorld(net.kyori.adventure.key.Key key) { throw new AssertionError(); }
        @Override public org.bukkit.WorldBorder createWorldBorder() { throw new AssertionError(); }
        @Override public org.bukkit.map.MapView getMap(int id) { throw new AssertionError(); }
        @Override public org.bukkit.map.MapView createMap(org.bukkit.World world) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World world, org.bukkit.Location location, org.bukkit.StructureType type, int radius, boolean find) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World world, org.bukkit.Location location, org.bukkit.generator.structure.StructureType type, org.bukkit.map.MapCursor.Type cursor, int radius, boolean find) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World world, org.bukkit.Location location, org.bukkit.generator.structure.Structure type, org.bukkit.map.MapCursor.Type cursor, int radius, boolean find) { throw new AssertionError(); }
        @Override public void reload() { throw new AssertionError(); }
        @Override public void reloadData() { throw new AssertionError(); }
        @Override public void updateResources() { throw new AssertionError(); }
        @Override public void updateRecipes() { throw new AssertionError(); }
        @Override public java.util.logging.Logger getLogger() { throw new AssertionError(); }
        @Override public org.bukkit.command.PluginCommand getPluginCommand(String name) { throw new AssertionError(); }
        @Override public void savePlayers() { throw new AssertionError(); }
        @Override public boolean dispatchCommand(org.bukkit.command.CommandSender sender, String command) { throw new AssertionError(); }
        @Override public boolean addRecipe(org.bukkit.inventory.Recipe recipe) { throw new AssertionError(); }
        @Override public boolean addRecipes(Iterable<org.bukkit.inventory.Recipe> recipes) { throw new AssertionError(); }
        @Override public java.util.List<org.bukkit.inventory.Recipe> getRecipesFor(org.bukkit.inventory.ItemStack item) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Recipe getRecipe(org.bukkit.NamespacedKey key) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Recipe getCraftingRecipe(org.bukkit.inventory.ItemStack[] items, org.bukkit.World world) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.ItemCraftResult craftItemResult(org.bukkit.inventory.ItemStack[] items, org.bukkit.World world, org.bukkit.entity.Player player) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.ItemCraftResult craftItemResult(org.bukkit.inventory.ItemStack[] items, org.bukkit.World world) { throw new AssertionError(); }
        @Override public java.util.Iterator<org.bukkit.inventory.Recipe> recipeIterator() { throw new AssertionError(); }
        @Override public void clearRecipes() { throw new AssertionError(); }
        @Override public void resetRecipes() { throw new AssertionError(); }
        @Override public boolean hasRecipe(org.bukkit.NamespacedKey key) { throw new AssertionError(); }
        @Override public boolean removeRecipe(org.bukkit.NamespacedKey key) { throw new AssertionError(); }
        @Override public boolean removeRecipes(Iterable<org.bukkit.NamespacedKey> keys) { throw new AssertionError(); }
        @Override public java.util.Map<String, String[]> getCommandAliases() { throw new AssertionError(); }
        @Override public int getSpawnRadius() { throw new AssertionError(); }
        @Override public void setSpawnRadius(int radius) { throw new AssertionError(); }
        @Override public boolean isEnforcingSecureProfiles() { throw new AssertionError(); }
        @Override public boolean isAcceptingTransfers() { throw new AssertionError(); }
        @Override public boolean getHideOnlinePlayers() { throw new AssertionError(); }
        @Override public boolean getOnlineMode() { throw new AssertionError(); }
        @Override public io.papermc.paper.configuration.ServerConfiguration getServerConfig() { throw new AssertionError(); }
        @Override public boolean getAllowFlight() { throw new AssertionError(); }
        @Override public boolean isHardcore() { throw new AssertionError(); }
        @Override public void shutdown() { throw new AssertionError(); }
        @Override public int broadcast(net.kyori.adventure.text.Component message, String permission) { throw new AssertionError(); }
        @Override public org.bukkit.OfflinePlayer getOfflinePlayer(String name) { throw new AssertionError("Name lookup is forbidden"); }
        @Override public org.bukkit.OfflinePlayer getOfflinePlayerIfCached(String name) { throw new AssertionError("Name lookup is forbidden"); }
        @Override public org.bukkit.profile.PlayerProfile createPlayerProfile(java.util.UUID uuid, String name) { throw new AssertionError(); }
        @Override public org.bukkit.profile.PlayerProfile createPlayerProfile(java.util.UUID uuid) { throw new AssertionError(); }
        @Override public org.bukkit.profile.PlayerProfile createPlayerProfile(String name) { throw new AssertionError(); }
        @Override public java.util.Set<String> getIPBans() { throw new AssertionError(); }
        @Override public void banIP(String ip) { throw new AssertionError(); }
        @Override public void unbanIP(String ip) { throw new AssertionError(); }
        @Override public void banIP(java.net.InetAddress ip) { throw new AssertionError(); }
        @Override public void unbanIP(java.net.InetAddress ip) { throw new AssertionError(); }
        @Override public java.util.Set<org.bukkit.OfflinePlayer> getBannedPlayers() { throw new AssertionError(); }
        @Override public <T extends org.bukkit.BanList<?>> T getBanList(org.bukkit.BanList.Type type) { throw new AssertionError(); }
        @Override public <B extends org.bukkit.BanList<E>, E> B getBanList(io.papermc.paper.ban.BanListType<B> type) { throw new AssertionError(); }
        @Override public java.util.Set<org.bukkit.OfflinePlayer> getOperators() { throw new AssertionError(); }
        @Override public org.bukkit.GameMode getDefaultGameMode() { throw new AssertionError(); }
        @Override public void setDefaultGameMode(org.bukkit.GameMode mode) { throw new AssertionError(); }
        @Override public boolean forcesDefaultGameMode() { throw new AssertionError(); }
        @Override public org.bukkit.command.ConsoleCommandSender getConsoleSender() { throw new AssertionError(); }
        @Override public org.bukkit.command.CommandSender createCommandSender(java.util.function.Consumer<? super net.kyori.adventure.text.Component> consumer) { throw new AssertionError(); }
        @Override public java.io.File getWorldContainer() { throw new AssertionError(); }
        @Override public java.nio.file.Path getLevelDirectory() { throw new AssertionError(); }
        @Override public org.bukkit.OfflinePlayer[] getOfflinePlayers() { throw new AssertionError(); }
        @Override public org.bukkit.plugin.messaging.Messenger getMessenger() { throw new AssertionError(); }
        @Override public org.bukkit.help.HelpMap getHelpMap() { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder holder, org.bukkit.event.inventory.InventoryType type) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder holder, org.bukkit.event.inventory.InventoryType type, net.kyori.adventure.text.Component title) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder holder, org.bukkit.event.inventory.InventoryType type, String title) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder holder, int size) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder holder, int size, net.kyori.adventure.text.Component title) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder holder, int size, String title) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Merchant createMerchant(net.kyori.adventure.text.Component title) { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Merchant createMerchant(String title) { throw new AssertionError(); }
        @Override public int getMaxChainedNeighborUpdates() { throw new AssertionError(); }
        @Override public org.bukkit.inventory.Merchant createMerchant() { throw new AssertionError(); }
        @Override public int getSpawnLimit(org.bukkit.entity.SpawnCategory category) { throw new AssertionError(); }
        @Override public boolean isPrimaryThread() { throw new AssertionError(); }
        @Override public net.kyori.adventure.text.Component motd() { throw new AssertionError(); }
        @Override public void motd(net.kyori.adventure.text.Component value) { throw new AssertionError(); }
        @Override public net.kyori.adventure.text.Component shutdownMessage() { throw new AssertionError(); }
        @Override public String getMotd() { throw new AssertionError(); }
        @Override public void setMotd(String value) { throw new AssertionError(); }
        @Override public org.bukkit.ServerLinks getServerLinks() { throw new AssertionError(); }
        @Override public String getShutdownMessage() { throw new AssertionError(); }
        @Override public org.bukkit.Warning.WarningState getWarningState() { throw new AssertionError(); }
        @Override public org.bukkit.inventory.ItemFactory getItemFactory() { throw new AssertionError(); }
        @Override public org.bukkit.entity.EntityFactory getEntityFactory() { throw new AssertionError(); }
        @Override public org.bukkit.scoreboard.ScoreboardManager getScoreboardManager() { throw new AssertionError(); }
        @Override public org.bukkit.scoreboard.Criteria getScoreboardCriteria(String name) { throw new AssertionError(); }
        @Override public org.bukkit.util.CachedServerIcon getServerIcon() { throw new AssertionError(); }
        @Override public org.bukkit.util.CachedServerIcon loadServerIcon(java.io.File file) { throw new AssertionError(); }
        @Override public org.bukkit.util.CachedServerIcon loadServerIcon(java.awt.image.BufferedImage image) { throw new AssertionError(); }
        @Override public void setIdleTimeout(int value) { throw new AssertionError(); }
        @Override public int getIdleTimeout() { throw new AssertionError(); }
        @Override public int getPauseWhenEmptyTime() { throw new AssertionError(); }
        @Override public void setPauseWhenEmptyTime(int value) { throw new AssertionError(); }
        @Override public org.bukkit.generator.ChunkGenerator.ChunkData createChunkData(org.bukkit.World world) { throw new AssertionError(); }
        @Override public org.bukkit.boss.BossBar createBossBar(String title, org.bukkit.boss.BarColor color, org.bukkit.boss.BarStyle style, org.bukkit.boss.BarFlag... flags) { throw new AssertionError(); }
        @Override public org.bukkit.boss.KeyedBossBar createBossBar(org.bukkit.NamespacedKey key, String title, org.bukkit.boss.BarColor color, org.bukkit.boss.BarStyle style, org.bukkit.boss.BarFlag... flags) { throw new AssertionError(); }
        @Override public java.util.Iterator<org.bukkit.boss.KeyedBossBar> getBossBars() { throw new AssertionError(); }
        @Override public org.bukkit.boss.KeyedBossBar getBossBar(org.bukkit.NamespacedKey key) { throw new AssertionError(); }
        @Override public boolean removeBossBar(org.bukkit.NamespacedKey key) { throw new AssertionError(); }
        @Override public org.bukkit.entity.Entity getEntity(java.util.UUID uuid) { throw new AssertionError(); }
        @Override public double[] getTPS() { throw new AssertionError(); }
        @Override public long[] getTickTimes() { throw new AssertionError(); }
        @Override public double getAverageTickTime() { throw new AssertionError(); }
        @Override public org.bukkit.command.CommandMap getCommandMap() { throw new AssertionError(); }
        @Override public org.bukkit.advancement.Advancement getAdvancement(org.bukkit.NamespacedKey key) { throw new AssertionError(); }
        @Override public java.util.Iterator<org.bukkit.advancement.Advancement> advancementIterator() { throw new AssertionError(); }
        @Override public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material material) { throw new AssertionError(); }
        @Override public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material material, java.util.function.Consumer<? super org.bukkit.block.data.BlockData> consumer) { throw new AssertionError(); }
        @Override public org.bukkit.block.data.BlockData createBlockData(String value) { throw new AssertionError(); }
        @Override public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material material, String value) { throw new AssertionError(); }
        @Override public <T extends org.bukkit.Keyed> org.bukkit.Tag<T> getTag(String registry, org.bukkit.NamespacedKey key, Class<T> type) { throw new AssertionError(); }
        @Override public <T extends org.bukkit.Keyed> Iterable<org.bukkit.Tag<T>> getTags(String registry, Class<T> type) { throw new AssertionError(); }
        @Override public org.bukkit.loot.LootTable getLootTable(org.bukkit.NamespacedKey key) { throw new AssertionError(); }
        @Override public java.util.List<org.bukkit.entity.Entity> selectEntities(org.bukkit.command.CommandSender sender, String selector) { throw new AssertionError(); }
        @Override public org.bukkit.structure.StructureManager getStructureManager() { throw new AssertionError(); }
        @Override public <T extends org.bukkit.Keyed> org.bukkit.Registry<T> getRegistry(Class<T> type) { throw new AssertionError(); }
        @Override public org.bukkit.UnsafeValues getUnsafe() { throw new AssertionError(); }
        @Override public org.bukkit.Server.Spigot spigot() { throw new AssertionError(); }
        @Override public void restart() { throw new AssertionError(); }
        @Override public void reloadPermissions() { throw new AssertionError(); }
        @Override public boolean reloadCommandAliases() { throw new AssertionError(); }
        @Override public boolean suggestPlayerNamesWhenNullTabCompletions() { throw new AssertionError(); }
        @Override public String getPermissionMessage() { throw new AssertionError(); }
        @Override public net.kyori.adventure.text.Component permissionMessage() { throw new AssertionError(); }
        @Override public com.destroystokyo.paper.profile.PlayerProfile createProfile(java.util.UUID uuid) { throw new AssertionError(); }
        @Override public com.destroystokyo.paper.profile.PlayerProfile createProfile(String name) { throw new AssertionError(); }
        @Override public com.destroystokyo.paper.profile.PlayerProfile createProfile(java.util.UUID uuid, String name) { throw new AssertionError(); }
        @Override public com.destroystokyo.paper.profile.PlayerProfile createProfileExact(java.util.UUID uuid, String name) { throw new AssertionError(); }
        @Override public int getCurrentTick() { throw new AssertionError(); }
        @Override public boolean isStopping() { throw new AssertionError(); }
        @Override public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() { throw new AssertionError(); }
        @Override public io.papermc.paper.datapack.DatapackManager getDatapackManager() { throw new AssertionError(); }
        @Override public org.bukkit.potion.PotionBrewer getPotionBrewer() { throw new AssertionError(); }
        @Override public io.papermc.paper.threadedregions.scheduler.RegionScheduler getRegionScheduler() { throw new AssertionError(); }
        @Override public io.papermc.paper.threadedregions.scheduler.AsyncScheduler getAsyncScheduler() { throw new AssertionError(); }
        @Override public io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler getGlobalRegionScheduler() { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.World world, io.papermc.paper.math.Position position) { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.World world, io.papermc.paper.math.Position position, int radius) { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.Location location) { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.Location location, int radius) { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.World world, int x, int z) { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.World world, int x, int z, int radius) { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.World world, int x, int z, int x2, int z2) { throw new AssertionError(); }
        @Override public boolean isOwnedByCurrentRegion(org.bukkit.entity.Entity entity) { throw new AssertionError(); }
        @Override public boolean isGlobalTickThread() { throw new AssertionError(); }
        @Override public boolean isPaused() { throw new AssertionError(); }
        @Override public void allowPausing(org.bukkit.plugin.Plugin plugin, boolean allow) { throw new AssertionError(); }
        @Override public void sendPluginMessage(org.bukkit.plugin.Plugin plugin, String channel, byte[] message) { throw new AssertionError(); }
        @Override public java.util.Set<String> getListeningPluginChannels() { throw new AssertionError(); }
        @Override public Iterable<? extends net.kyori.adventure.audience.Audience> audiences() { throw new AssertionError(); }
    }

    abstract static class OfflinePlayer implements org.bukkit.OfflinePlayer {
        @Override public boolean isOnline() { throw new AssertionError(); }
        @Override public boolean isConnected() { throw new AssertionError(); }
        @Override public String getName() { throw new AssertionError("Nickname access is forbidden"); }
        @Override public com.destroystokyo.paper.profile.PlayerProfile getPlayerProfile() { throw new AssertionError(); }
        @Override public boolean isBanned() { throw new AssertionError(); }
        @Override public <E extends org.bukkit.BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(String reason, java.util.Date expires, String source) { throw new AssertionError(); }
        @Override public <E extends org.bukkit.BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(String reason, java.time.Instant expires, String source) { throw new AssertionError(); }
        @Override public <E extends org.bukkit.BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(String reason, java.time.Duration expires, String source) { throw new AssertionError(); }
        @Override public org.bukkit.entity.Player getPlayer() { throw new AssertionError(); }
        @Override public long getFirstPlayed() { throw new AssertionError(); }
        @Override public long getLastPlayed() { throw new AssertionError(); }
        @Override public boolean hasPlayedBefore() { throw new AssertionError(); }
        @Override public long getLastLogin() { throw new AssertionError(); }
        @Override public long getLastSeen() { throw new AssertionError(); }
        @Override public org.bukkit.Location getRespawnLocation(boolean load) { throw new AssertionError(); }
        @Override public void incrementStatistic(org.bukkit.Statistic statistic) { throw new AssertionError(); }
        @Override public void decrementStatistic(org.bukkit.Statistic statistic) { throw new AssertionError(); }
        @Override public void incrementStatistic(org.bukkit.Statistic statistic, int amount) { throw new AssertionError(); }
        @Override public void decrementStatistic(org.bukkit.Statistic statistic, int amount) { throw new AssertionError(); }
        @Override public void setStatistic(org.bukkit.Statistic statistic, int value) { throw new AssertionError(); }
        @Override public int getStatistic(org.bukkit.Statistic statistic) { throw new AssertionError(); }
        @Override public void incrementStatistic(org.bukkit.Statistic statistic, org.bukkit.Material material) { throw new AssertionError(); }
        @Override public void decrementStatistic(org.bukkit.Statistic statistic, org.bukkit.Material material) { throw new AssertionError(); }
        @Override public int getStatistic(org.bukkit.Statistic statistic, org.bukkit.Material material) { throw new AssertionError(); }
        @Override public void incrementStatistic(org.bukkit.Statistic statistic, org.bukkit.Material material, int amount) { throw new AssertionError(); }
        @Override public void decrementStatistic(org.bukkit.Statistic statistic, org.bukkit.Material material, int amount) { throw new AssertionError(); }
        @Override public void setStatistic(org.bukkit.Statistic statistic, org.bukkit.Material material, int value) { throw new AssertionError(); }
        @Override public void incrementStatistic(org.bukkit.Statistic statistic, org.bukkit.entity.EntityType type) { throw new AssertionError(); }
        @Override public void decrementStatistic(org.bukkit.Statistic statistic, org.bukkit.entity.EntityType type) { throw new AssertionError(); }
        @Override public int getStatistic(org.bukkit.Statistic statistic, org.bukkit.entity.EntityType type) { throw new AssertionError(); }
        @Override public void incrementStatistic(org.bukkit.Statistic statistic, org.bukkit.entity.EntityType type, int amount) { throw new AssertionError(); }
        @Override public void decrementStatistic(org.bukkit.Statistic statistic, org.bukkit.entity.EntityType type, int amount) { throw new AssertionError(); }
        @Override public void setStatistic(org.bukkit.Statistic statistic, org.bukkit.entity.EntityType type, int value) { throw new AssertionError(); }
        @Override public org.bukkit.Location getLastDeathLocation() { throw new AssertionError(); }
        @Override public org.bukkit.Location getLocation() { throw new AssertionError(); }
        @Override public io.papermc.paper.persistence.PersistentDataContainerView getPersistentDataContainer() { throw new AssertionError(); }
        @Override public boolean isOp() { throw new AssertionError(); }
        @Override public void setOp(boolean value) { throw new AssertionError(); }
        @Override public java.util.Map<String, Object> serialize() { throw new AssertionError(); }
    }
}

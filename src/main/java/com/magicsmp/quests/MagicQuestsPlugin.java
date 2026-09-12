package com.magicsmp.quests;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MagicQuestsPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final List<String> active = new ArrayList<>();
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    private final Map<UUID, Set<String>> completed = new HashMap<>();
    private final Map<UUID, Set<String>> claimable = new HashMap<>();
    private final Map<UUID, String> selectedQuest = new HashMap<>();
    private final ConcurrentHashMap<UUID, Inventory> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    private Economy economy;
    private long intervalMillis;
    private long nextResetAt;
    private boolean dirty;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("No Vault economy provider found. Disabling MagicQuests.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = registration.getProvider();

        Bukkit.getPluginManager().registerEvents(this, this);
        loadPluginData();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new QuestPlaceholders(this).register();
        }

        // Timer/reset + selected quest bossbar refresh.
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        // Save changed player progress every 5 seconds.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (dirty) savePluginData();
        }, 100L, 100L);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) removeBossBar(player);
        if (economy != null) savePluginData();
    }

    private void loadPluginData() {
        reloadConfig();
        loadQuestDefinitions();
        intervalMillis = Math.max(60_000L, parseDuration(getConfig().getString("reset-interval", "15m")));

        File file = new File(getDataFolder(), "data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);

        active.clear();
        for (String id : data.getStringList("active-quests")) {
            if (quests.containsKey(id)) active.add(id);
        }
        nextResetAt = data.getLong("next-reset-at", 0L);
        loadPlayerData(data);

        int count = Math.max(1, getConfig().getInt("active-quest-count", 3));
        if (active.isEmpty() || active.size() > count || nextResetAt <= System.currentTimeMillis()) {
            selectNewQuests(false);
        } else {
            savePluginData();
        }

        for (Player p : Bukkit.getOnlinePlayers()) updateBossBar(p);
    }

    private void loadQuestDefinitions() {
        quests.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("quests");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            String path = "quests." + id + ".";
            QuestType type;
            try { type = QuestType.valueOf(getConfig().getString(path + "type", "BREAK").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { continue; }

            String target = getConfig().getString(path + "target", "ANY").toUpperCase(Locale.ROOT);
            int required = Math.max(1, getConfig().getInt(path + "required", 1));
            double reward = Math.max(0.0, getConfig().getDouble(path + "reward", 0.0));
            String name = getConfig().getString(path + "name", pretty(id));
            Material icon = Material.matchMaterial(getConfig().getString(path + "icon", "PAPER"));
            if (icon == null) icon = Material.PAPER;
            quests.put(id, new Quest(id, type, target, required, reward, name, icon));
        }
    }

    private void loadPlayerData(YamlConfiguration data) {
        progress.clear(); completed.clear(); claimable.clear(); selectedQuest.clear();
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;

        for (String uuidText : players.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidText); } catch (IllegalArgumentException ex) { continue; }
            String base = "players." + uuidText + ".";

            String selected = data.getString(base + "selected", "");
            if (!selected.isBlank() && quests.containsKey(selected)) selectedQuest.put(uuid, selected);

            Set<String> comp = new HashSet<>(data.getStringList(base + "completed"));
            comp.retainAll(quests.keySet());
            if (!comp.isEmpty()) completed.put(uuid, comp);

            Set<String> claims = new HashSet<>(data.getStringList(base + "claimable"));
            claims.retainAll(quests.keySet());
            if (!claims.isEmpty()) claimable.put(uuid, claims);

            ConfigurationSection progSec = data.getConfigurationSection(base + "progress");
            if (progSec != null) {
                Map<String, Integer> values = new HashMap<>();
                for (String questId : progSec.getKeys(false)) {
                    if (quests.containsKey(questId)) values.put(questId, Math.max(0, progSec.getInt(questId)));
                }
                if (!values.isEmpty()) progress.put(uuid, values);
            }
        }
    }

    private void tick() {
        if (System.currentTimeMillis() >= nextResetAt) selectNewQuests(true);
        for (Player player : Bukkit.getOnlinePlayers()) updateBossBar(player);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> updateBossBar(event.getPlayer()), 10L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        removeBossBar(event.getPlayer());
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    /**
     * IMPORTANT: there is deliberately NO idle bossbar here.
     * Players only see a bossbar while they actually have a selected quest.
     */
    private void updateBossBar(Player player) {
        if (!getConfig().getBoolean("bossbar.enabled", true)) {
            removeBossBar(player); return;
        }

        String questId = selectedQuest.get(player.getUniqueId());
        Quest quest = questId == null ? null : quests.get(questId);
        if (quest == null || !active.contains(questId)) {
            removeBossBar(player); return;
        }

        int amount = progress.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(questId, 0);
        amount = Math.min(amount, quest.required);
        boolean done = claimable.getOrDefault(player.getUniqueId(), Set.of()).contains(questId);
        float barProgress = done ? 1.0f : Math.max(0.0f, Math.min(1.0f, amount / (float) quest.required));

        String title = done
                ? "&#ff9900&lQUEST &8• &#7afc00&l" + quest.name + " &8• &fComplete - return to the Quest NPC"
                : "&#ff9900&lQUEST &8• &f" + quest.name + " &8• &f" + amount + "&7/&f" + quest.required;

        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(component(title), barProgress, bossBarColor(), BossBar.Overlay.PROGRESS);
            bossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(component(title));
            bar.progress(barProgress);
            bar.color(done ? BossBar.Color.GREEN : bossBarColor());
        }
    }

    private BossBar.Color bossBarColor() {
        try { return BossBar.Color.valueOf(getConfig().getString("bossbar.color", "YELLOW").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return BossBar.Color.YELLOW; }
    }

    private void removeBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    private void selectNewQuests(boolean announce) {
        List<String> ids = new ArrayList<>(quests.keySet());
        Collections.shuffle(ids);
        int count = Math.min(Math.max(1, getConfig().getInt("active-quest-count", 3)), ids.size());
        active.clear();
        active.addAll(ids.subList(0, count));
        nextResetAt = System.currentTimeMillis() + intervalMillis;

        // A reset is a new quest cycle. This is what lets the same quest be completed again later.
        progress.clear();
        completed.clear();
        claimable.clear();
        selectedQuest.clear();
        for (Player p : Bukkit.getOnlinePlayers()) removeBossBar(p);

        dirty = true;
        savePluginData();
        refreshMenus();
        if (announce) Bukkit.broadcast(component(message("reset")));
    }

    private void savePluginData() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("active-quests", active);
        data.set("next-reset-at", nextResetAt);

        Set<UUID> users = new HashSet<>();
        users.addAll(progress.keySet()); users.addAll(completed.keySet()); users.addAll(claimable.keySet()); users.addAll(selectedQuest.keySet());
        for (UUID uuid : users) {
            String base = "players." + uuid + ".";
            data.set(base + "selected", selectedQuest.get(uuid));
            data.set(base + "completed", new ArrayList<>(completed.getOrDefault(uuid, Set.of())));
            data.set(base + "claimable", new ArrayList<>(claimable.getOrDefault(uuid, Set.of())));
            Map<String, Integer> values = progress.get(uuid);
            if (values != null) for (Map.Entry<String, Integer> e : values.entrySet()) data.set(base + "progress." + e.getKey(), e.getValue());
        }

        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            data.save(new File(getDataFolder(), "data.yml"));
            dirty = false;
        } catch (IOException ex) {
            getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            openMenu(player); return true;
        }

        // Internal clickable chat action used after a quest is completed.
        if (args[0].equalsIgnoreCase("return")) {
            if (!(sender instanceof Player player) || args.length < 2) return true;
            returnToNpc(player, args[1]);
            return true;
        }

        if (!sender.hasPermission("magicquests.admin")) {
            if (sender instanceof Player player) openMenu(player);
            else sender.sendMessage("No permission.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadPluginData();
                sender.sendMessage(component(message("reload")));
            }
            case "reset" -> selectNewQuests(true);
            case "settime" -> {
                if (args.length < 2) { sender.sendMessage("/quests settime <15m|1h|...>"); return true; }
                long parsed = parseDuration(args[1]);
                if (parsed < 1000L) { sender.sendMessage("Invalid time."); return true; }
                intervalMillis = parsed;
                getConfig().set("reset-interval", args[1]);
                saveConfig();
                nextResetAt = System.currentTimeMillis() + intervalMillis;
                dirty = true; savePluginData();
                sender.sendMessage(component(message("time-changed").replace("%time%", args[1])));
            }
            case "setnpc" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                Location l = player.getLocation();
                getConfig().set("npc-location.world", l.getWorld().getName());
                getConfig().set("npc-location.x", l.getX());
                getConfig().set("npc-location.y", l.getY());
                getConfig().set("npc-location.z", l.getZ());
                getConfig().set("npc-location.yaw", l.getYaw());
                getConfig().set("npc-location.pitch", l.getPitch());
                saveConfig();
                player.sendMessage(component(message("npc-set")));
            }
            default -> {
                if (sender instanceof Player player) openMenu(player);
                else sender.sendMessage("/quests [reload|reset|settime|setnpc]");
            }
        }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        addMatchingProgress(event.getPlayer(), QuestType.BREAK, event.getBlock().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        addMatchingProgress(event.getPlayer(), QuestType.PLACE, event.getBlockPlaced().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) addMatchingProgress(killer, QuestType.KILL, event.getEntityType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) addMatchingProgress(event.getPlayer(), QuestType.FISH, "ANY", 1);
    }

    private void addMatchingProgress(Player player, QuestType type, String target, int amount) {
        String selected = selectedQuest.get(player.getUniqueId());
        if (selected == null || !active.contains(selected)) return;
        Quest quest = quests.get(selected);
        if (quest == null || quest.type != type) return;
        if (!quest.target.equals("ANY") && !quest.target.equalsIgnoreCase(target)) return;
        addProgress(player, quest, amount);
    }

    private void addProgress(Player player, Quest quest, int amount) {
        UUID uuid = player.getUniqueId();
        if (claimable.getOrDefault(uuid, Set.of()).contains(quest.id)) return;

        Map<String, Integer> map = progress.computeIfAbsent(uuid, x -> new HashMap<>());
        int old = map.getOrDefault(quest.id, 0);
        int now = Math.min(quest.required, old + amount);
        map.put(quest.id, now);
        dirty = true;

        if (now >= quest.required) {
            completed.computeIfAbsent(uuid, x -> new HashSet<>()).add(quest.id);
            claimable.computeIfAbsent(uuid, x -> new HashSet<>()).add(quest.id);
            player.sendMessage(component(message("completed").replace("%quest%", quest.name)));

            Component click = component(message("click-to-claim"))
                    .clickEvent(ClickEvent.runCommand("/questsreturn " + quest.id))
                    .hoverEvent(HoverEvent.showText(component(message("claim-hover")
                            .replace("%quest%", quest.name)
                            .replace("%reward%", money(quest.reward)))));
            // /questsreturn is intercepted below using preprocess-style command registration is unavailable,
            // so use /quests return <id> instead.
            click = component(message("click-to-claim"))
                    .clickEvent(ClickEvent.runCommand("/quests return " + quest.id))
                    .hoverEvent(HoverEvent.showText(component(message("claim-hover")
                            .replace("%quest%", quest.name)
                            .replace("%reward%", money(quest.reward)))));
            player.sendMessage(click);
            play(player, "complete");
        } else {
            // Progress is shown above the hotbar instead of filling chat.
            player.sendActionBar(component(message("progress")
                    .replace("%quest%", quest.name)
                    .replace("%progress%", String.valueOf(now))
                    .replace("%required%", String.valueOf(quest.required))));
            play(player, "progress");
        }
        updateBossBar(player);
        refreshMenu(player);
    }

    private void returnToNpc(Player player, String questId) {
        Quest quest = quests.get(questId);
        if (quest == null || !claimable.getOrDefault(player.getUniqueId(), Set.of()).contains(questId)) {
            player.sendMessage(component(message("claim-expired"))); return;
        }
        Location npc = npcLocation();
        if (npc == null) { player.sendMessage(component(message("npc-not-set"))); return; }

        player.teleportAsync(npc).thenAccept(success -> Bukkit.getScheduler().runTask(this, () -> {
            if (!success) return;
            player.sendMessage(component(message("returned-to-npc").replace("%quest%", quest.name)));
            openMenu(player);
        }));
    }

    private void claimReward(Player player, String questId) {
        UUID uuid = player.getUniqueId();
        Quest quest = quests.get(questId);
        if (quest == null || !active.contains(questId) || !claimable.getOrDefault(uuid, Set.of()).contains(questId)) {
            player.sendMessage(component(message("claim-expired"))); return;
        }

        Location npc = npcLocation();
        if (npc == null) { player.sendMessage(component(message("npc-not-set"))); return; }
        if (!Objects.equals(player.getWorld(), npc.getWorld()) || player.getLocation().distanceSquared(npc) > Math.pow(getConfig().getDouble("claim-radius", 6), 2)) {
            player.sendMessage(component(message("claim-near-npc"))); play(player, "error"); return;
        }

        var response = economy.depositPlayer(player, quest.reward);
        if (!response.transactionSuccess()) {
            player.sendMessage(component(message("payout-failed"))); play(player, "error"); return;
        }

        claimable.getOrDefault(uuid, new HashSet<>()).remove(questId);
        // Keep this quest in the completed set until the next quest reset.
        // This prevents farming the same quest repeatedly during one reset cycle.
        progress.computeIfAbsent(uuid, x -> new HashMap<>()).remove(questId);
        selectedQuest.remove(uuid); // NO COOLDOWN: player can select a different quest immediately.
        dirty = true;

        player.sendMessage(component(message("claimed")
                .replace("%reward%", money(quest.reward))
                .replace("%quest%", quest.name)));
        play(player, "selected");
        removeBossBar(player);
        refreshMenu(player);
    }

    private Location npcLocation() {
        String worldName = getConfig().getString("npc-location.world", "");
        if (worldName.isBlank()) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                getConfig().getDouble("npc-location.x"), getConfig().getDouble("npc-location.y"), getConfig().getDouble("npc-location.z"),
                (float) getConfig().getDouble("npc-location.yaw"), (float) getConfig().getDouble("npc-location.pitch"));
    }

    private void openMenu(Player player) {
        QuestHolder holder = new QuestHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, component("&#ff9900&lQuests"));
        holder.inventory = inv;
        fillMenu(player, inv);
        openMenus.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    private void fillMenu(Player player, Inventory inv) {
        inv.clear();
        UUID uuid = player.getUniqueId();
        String selected = selectedQuest.get(uuid);
        int[] slots = {11, 13, 15, 10, 12, 14, 16};

        for (int i = 0; i < active.size() && i < slots.length; i++) {
            String id = active.get(i);
            Quest q = quests.get(id);
            if (q == null) continue;
            int amount = progress.getOrDefault(uuid, Map.of()).getOrDefault(id, 0);
            boolean canClaim = claimable.getOrDefault(uuid, Set.of()).contains(id);
            boolean didComplete = completed.getOrDefault(uuid, Set.of()).contains(id);
            boolean isSelected = id.equals(selected);

            List<String> lore = new ArrayList<>();
            lore.add("&7" + description(q));
            lore.add("");
            lore.add("&fProgress: &#7afc00" + Math.min(amount, q.required) + "&7/&#7afc00" + q.required);
            lore.add("&fReward: &#7afc00$" + money(q.reward));
            lore.add("");
            if (canClaim) lore.add("&#7afc00&lCOMPLETED &8- &fClick to claim at the Quest NPC");
            else if (didComplete) lore.add("&c&lDONE &8- &7Available again after the next reset");
            else if (isSelected) lore.add("&#ff9900&lSELECTED");
            else if (selected != null) lore.add("&cFinish your selected quest first.");
            else lore.add("&#7afc00Click to select this quest.");

            ItemStack stack = item(q.icon, ((canClaim || didComplete) ? "&#7afc00&l" : "&#ff9900&l") + q.name, lore);
            ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "quest-id"), org.bukkit.persistence.PersistentDataType.STRING, id);
            stack.setItemMeta(meta);
            inv.setItem(slots[i], stack);
        }

        inv.setItem(22, item(Material.CLOCK, "&#ff9900&lQuest Reset", List.of("&fResets in: &#7afc00" + formatRemaining())));
    }

    private void refreshMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) refreshMenu(p);
    }
    private void refreshMenu(Player p) {
        Inventory inv = openMenus.get(p.getUniqueId());
        if (inv != null && p.getOpenInventory().getTopInventory().equals(inv)) fillMenu(p, inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = openMenus.get(player.getUniqueId());
        if (inv == null || !event.getView().getTopInventory().equals(inv)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(inv)) return;
        ItemStack stack = event.getCurrentItem();
        if (stack == null || !stack.hasItemMeta()) return;
        String id = stack.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this, "quest-id"), org.bukkit.persistence.PersistentDataType.STRING);
        if (id == null) return;
        if (claimable.getOrDefault(player.getUniqueId(), Set.of()).contains(id)) claimReward(player, id);
        else selectQuest(player, id);
    }

    private void selectQuest(Player player, String questId) {
        UUID uuid = player.getUniqueId();
        Quest quest = quests.get(questId);
        if (quest == null || !active.contains(questId)) return;

        if (completed.getOrDefault(uuid, Set.of()).contains(questId)) {
            player.sendMessage(component(message("already-completed-until-reset").replace("%quest%", quest.name)));
            play(player, "error");
            return;
        }

        String current = selectedQuest.get(uuid);
        if (questId.equals(current)) {
            player.sendMessage(component(message("already-selected"))); play(player, "error"); return;
        }
        if (current != null) {
            player.sendMessage(component(message("finish-selected"))); play(player, "error"); return;
        }

        // Deliberately no cooldown check and no cooldown timestamp.
        selectedQuest.put(uuid, questId);
        progress.computeIfAbsent(uuid, x -> new HashMap<>()).putIfAbsent(questId, 0);
        dirty = true;
        player.sendMessage(component(message("selected").replace("%quest%", quest.name)));
        play(player, "selected");
        updateBossBar(player);
        refreshMenu(player);
    }

    @EventHandler public void onMenuClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p) openMenus.remove(p.getUniqueId(), event.getInventory());
    }

    public String formatRemaining() { return formatDuration(Math.max(0L, nextResetAt - System.currentTimeMillis())); }
    public String configuredInterval() { return getConfig().getString("reset-interval", "15m"); }
    public String activeQuestNames() {
        return active.stream().map(quests::get).filter(Objects::nonNull).map(q -> q.name).reduce((a,b) -> a + ", " + b).orElse("None");
    }

    private String description(Quest q) {
        return switch (q.type) {
            case BREAK -> "Break " + q.required + " " + pretty(q.target);
            case PLACE -> "Place " + q.required + " " + pretty(q.target);
            case KILL -> "Kill " + q.required + " " + pretty(q.target);
            case FISH -> "Catch " + q.required + " fish";
        };
    }

    private String money(double value) {
        return new DecimalFormat(value == Math.rint(value) ? "#,##0" : "#,##0.00").format(value);
    }

    private String message(String key) { return getConfig().getString("messages." + key, "&cMissing message: " + key); }

    private void play(Player player, String key) {
        String raw = getConfig().getString("sounds." + key, "");
        if (raw.isBlank()) return;
        try { player.playSound(player.getLocation(), Sound.valueOf(raw.toUpperCase(Locale.ROOT)), 1f, 1f); }
        catch (IllegalArgumentException ignored) {}
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(component(name));
        meta.lore(lore.stream().map(this::component).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private Component component(String text) { return LEGACY.deserialize(color(text)); }

    private String color(String text) {
        Matcher m = HEX.matcher(text == null ? "" : text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String h = m.group(1);
            String replacement = "&x&" + h.charAt(0) + "&" + h.charAt(1) + "&" + h.charAt(2) + "&" + h.charAt(3) + "&" + h.charAt(4) + "&" + h.charAt(5);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String pretty(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).replace('_', ' ').split(" ");
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (!p.isBlank()) b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return b.toString().trim();
    }

    private long parseDuration(String input) {
        if (input == null) return 0L;
        Matcher m = Pattern.compile("(?i)^(\\d+)(s|m|h|d)$").matcher(input.trim());
        if (!m.matches()) return 0L;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> n * 1000L;
            case "m" -> n * 60_000L;
            case "h" -> n * 3_600_000L;
            case "d" -> n * 86_400_000L;
            default -> 0L;
        };
    }

    private String formatDuration(long millis) {
        long total = Math.max(0L, millis / 1000L);
        long d = total / 86400; total %= 86400;
        long h = total / 3600; total %= 3600;
        long m = total / 60; long s = total % 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    enum QuestType { BREAK, PLACE, KILL, FISH }
    record Quest(String id, QuestType type, String target, int required, double reward, String name, Material icon) {}
    static final class QuestHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}

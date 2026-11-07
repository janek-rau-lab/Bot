package botplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * BuilderBots - bis zu 5 Bots (Mannequin-Entities), die an einer Stelle stehen,
 * Bloecke abbauen/platzieren, ein eigenes Inventar haben und ihre Chunks geladen halten.
 * Keine Zusatzplugins noetig. Nur Bukkit/Paper-API.
 */
public class BotPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    enum Mode { NONE, MINE, PLACE }

    static class Bot {
        String name;
        UUID entityId;
        UUID owner;
        String worldName;
        double x, y, z;
        float yaw, pitch;
        Inventory inv;
        Mode mode = Mode.NONE;
        int tx, ty, tz;
        Material placeMat;
        int progress;
        int heldSlot = -1;
        int miss;
        World ticketWorld;
        Set<Long> tickets = new HashSet<>();
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final List<String> SUBS = List.of(
            "create", "remove", "list", "tp", "goto", "look", "lookat", "mine", "place",
            "stop", "inventory", "claim", "unclaim", "help");
    private static final Set<String> NEED_BOT = Set.of(
            "remove", "tp", "goto", "look", "lookat", "mine", "place", "stop", "inventory", "claim", "unclaim");

    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private final Random random = new Random();
    private File dataFile;

    private int maxBots = 5;
    private int chunkRadius = 1;
    private double reach = 5.0;
    private int interval = 2;
    private int placeInterval = 10;
    private boolean repeat = true;
    private float speedMult = 1.0f;

    // ------------------------------------------------------------------ Start / Stop

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        for (Bot b : bots.values()) {
            restoreBot(b);
        }
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, interval);
        getServer().getScheduler().runTaskTimer(this, this::checkEntities, 200L, 200L);
        int autosave = Math.max(200, getConfig().getInt("autosave-ticks", 6000));
        getServer().getScheduler().runTaskTimer(this, this::saveData, autosave, autosave);
        if (getCommand("bot") != null) {
            getCommand("bot").setExecutor(this);
            getCommand("bot").setTabCompleter(this);
        }
        getLogger().info("BuilderBots aktiv, Bots geladen: " + bots.size());
    }

    @Override
    public void onDisable() {
        saveData();
        for (Bot b : bots.values()) {
            releaseTickets(b);
        }
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        maxBots = Math.max(1, Math.min(5, c.getInt("max-bots", 5)));
        chunkRadius = Math.max(0, Math.min(2, c.getInt("chunk-radius", 1)));
        reach = Math.max(1.0, Math.min(8.0, c.getDouble("reach", 5.0)));
        interval = Math.max(1, c.getInt("tick-interval", 2));
        placeInterval = Math.max(interval, c.getInt("place-interval", 10));
        repeat = c.getBoolean("repeat", true);
        speedMult = (float) Math.max(0.1, c.getDouble("mining-speed-multiplier", 1.0));
    }

    // ------------------------------------------------------------------ Daten

    private Inventory newInv(String name) {
        return Bukkit.createInventory(null, 54, Component.text("Bot " + name));
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("bots");
        if (s == null) {
            return;
        }
        for (String name : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(name);
            if (c == null) {
                continue;
            }
            Bot b = new Bot();
            b.name = name;
            String u = c.getString("uuid");
            if (u != null) {
                try {
                    b.entityId = UUID.fromString(u);
                } catch (IllegalArgumentException ignored) {
                    // wird neu gespawnt
                }
            }
            String o = c.getString("owner");
            if (o != null) {
                try {
                    b.owner = UUID.fromString(o);
                } catch (IllegalArgumentException ignored) {
                    b.owner = null;
                }
            }
            b.worldName = c.getString("world", "world");
            b.x = c.getDouble("x");
            b.y = c.getDouble("y");
            b.z = c.getDouble("z");
            b.yaw = (float) c.getDouble("yaw");
            b.pitch = (float) c.getDouble("pitch");
            try {
                b.mode = Mode.valueOf(c.getString("mode", "NONE"));
            } catch (IllegalArgumentException e) {
                b.mode = Mode.NONE;
            }
            b.tx = c.getInt("tx");
            b.ty = c.getInt("ty");
            b.tz = c.getInt("tz");
            String pm = c.getString("place-material");
            if (pm != null) {
                b.placeMat = Material.matchMaterial(pm);
            }
            b.inv = newInv(name);
            List<?> items = c.getList("items");
            if (items != null) {
                for (int i = 0; i < items.size() && i < 54; i++) {
                    if (items.get(i) instanceof ItemStack is) {
                        b.inv.setItem(i, is);
                    }
                }
            }
            bots.put(name.toLowerCase(Locale.ROOT), b);
        }
    }

    private void saveData() {
        if (dataFile == null) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        for (Bot b : bots.values()) {
            Mannequin m = findEntity(b);
            if (m != null) {
                Location l = m.getLocation();
                b.x = l.getX();
                b.y = l.getY();
                b.z = l.getZ();
                b.yaw = l.getYaw();
                b.pitch = l.getPitch();
                b.worldName = l.getWorld().getName();
            }
            String p = "bots." + b.name + ".";
            y.set(p + "uuid", b.entityId == null ? null : b.entityId.toString());
            y.set(p + "owner", b.owner == null ? null : b.owner.toString());
            y.set(p + "world", b.worldName);
            y.set(p + "x", b.x);
            y.set(p + "y", b.y);
            y.set(p + "z", b.z);
            y.set(p + "yaw", (double) b.yaw);
            y.set(p + "pitch", (double) b.pitch);
            y.set(p + "mode", b.mode.name());
            y.set(p + "tx", b.tx);
            y.set(p + "ty", b.ty);
            y.set(p + "tz", b.tz);
            y.set(p + "place-material", b.placeMat == null ? null : b.placeMat.name());
            y.set(p + "items", new ArrayList<>(Arrays.asList(b.inv.getContents())));
        }
        try {
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("data.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Entity / Chunks

    private Mannequin findEntity(Bot b) {
        if (b.entityId == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(b.entityId);
        if (e instanceof Mannequin m && m.isValid()) {
            return m;
        }
        return null;
    }

    private Mannequin spawnEntity(Bot b, World w) {
        Location loc = new Location(w, b.x, b.y, b.z, b.yaw, b.pitch);
        Mannequin m = w.spawn(loc, Mannequin.class, mm -> {
            mm.customName(Component.text(b.name));
            mm.setCustomNameVisible(true);
            mm.setImmovable(true);
            mm.setInvulnerable(true);
            mm.setPersistent(true);
            mm.setRemoveWhenFarAway(false);
        });
        b.entityId = m.getUniqueId();
        b.heldSlot = -1;
        return m;
    }

    private void restoreBot(Bot b) {
        World w = Bukkit.getWorld(b.worldName);
        if (w == null) {
            getLogger().warning("Welt '" + b.worldName + "' fuer Bot " + b.name + " nicht gefunden.");
            return;
        }
        int cx = ((int) Math.floor(b.x)) >> 4;
        int cz = ((int) Math.floor(b.z)) >> 4;
        setTickets(b, w, cx, cz);
        w.getChunkAtAsync(cx, cz).thenAccept(ch ->
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (findEntity(b) == null) {
                        spawnEntity(b, w);
                    }
                }, 40L));
    }

    private void checkEntities() {
        for (Bot b : bots.values()) {
            World w = Bukkit.getWorld(b.worldName);
            if (w == null) {
                continue;
            }
            if (findEntity(b) == null) {
                b.miss++;
                if (b.miss >= 2) {
                    spawnEntity(b, w);
                    b.miss = 0;
                }
            } else {
                b.miss = 0;
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private void releaseTickets(Bot b) {
        World w = b.ticketWorld;
        if (w != null) {
            for (long k : b.tickets) {
                w.removePluginChunkTicket((int) (k >> 32), (int) k, this);
            }
        }
        b.tickets.clear();
        b.ticketWorld = null;
    }

    private void setTickets(Bot b, World w, int cx, int cz) {
        Set<Long> want = new HashSet<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                want.add(key(cx + dx, cz + dz));
            }
        }
        if (b.ticketWorld != null && !b.ticketWorld.equals(w)) {
            releaseTickets(b);
        }
        for (long k : want) {
            if (b.tickets.add(k)) {
                w.addPluginChunkTicket((int) (k >> 32), (int) k, this);
            }
        }
        Iterator<Long> it = b.tickets.iterator();
        while (it.hasNext()) {
            long k = it.next();
            if (!want.contains(k)) {
                w.removePluginChunkTicket((int) (k >> 32), (int) k, this);
                it.remove();
            }
        }
        b.ticketWorld = w;
    }

    private void moveBot(Bot b, Location loc) {
        b.worldName = loc.getWorld().getName();
        b.x = loc.getX();
        b.y = loc.getY();
        b.z = loc.getZ();
        b.yaw = loc.getYaw();
        b.pitch = loc.getPitch();
        b.progress = 0;
        setTickets(b, loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        Mannequin m = findEntity(b);
        if (m == null) {
            spawnEntity(b, loc.getWorld());
        } else {
            m.teleport(loc);
        }
    }

    // ------------------------------------------------------------------ Arbeits-Schleife

    private void tick() {
        for (Bot b : bots.values()) {
            if (b.mode == Mode.NONE) {
                continue;
            }
            Mannequin m = findEntity(b);
            if (m == null) {
                continue;
            }
            try {
                if (b.mode == Mode.MINE) {
                    tickMine(b, m);
                } else {
                    tickPlace(b, m);
                }
            } catch (Exception ex) {
                getLogger().warning("Fehler bei Bot " + b.name + ": " + ex);
                b.mode = Mode.NONE;
            }
        }
    }

    private boolean inReach(Mannequin m, int x, int y, int z) {
        Location eye = m.getEyeLocation();
        double dx = x + 0.5 - eye.getX();
        double dy = y + 0.5 - eye.getY();
        double dz = z + 0.5 - eye.getZ();
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private void stopBot(Bot b, String reason) {
        b.mode = Mode.NONE;
        b.progress = 0;
        if (b.owner != null) {
            Player p = Bukkit.getPlayer(b.owner);
            if (p != null) {
                p.sendMessage(Component.text("[Bot " + b.name + "] " + reason, NamedTextColor.YELLOW));
                return;
            }
        }
        getLogger().info("[Bot " + b.name + "] " + reason);
    }

    private void setHand(Mannequin m, ItemStack item) {
        EntityEquipment eq = m.getEquipment();
        if (eq != null) {
            eq.setItemInMainHand(item == null ? new ItemStack(Material.AIR) : item.clone());
        }
    }

    private void lookAt(Mannequin m, Location target) {
        Location eye = m.getEyeLocation();
        Location l = m.getLocation();
        Vector d = target.toVector().subtract(eye.toVector());
        l.setDirection(d);
        m.setRotation(l.getYaw(), l.getPitch());
    }

    private int bestToolSlot(Bot b, Block blk) {
        int best = -1;
        float bestSpeed = blk.getDestroySpeed(new ItemStack(Material.AIR), true);
        for (int i = 0; i < b.inv.getSize(); i++) {
            ItemStack it = b.inv.getItem(i);
            if (it == null || it.getType().getMaxDurability() <= 0) {
                continue;
            }
            float s = blk.getDestroySpeed(it, true);
            if (s > bestSpeed) {
                bestSpeed = s;
                best = i;
            }
        }
        return best;
    }

    private void tickMine(Bot b, Mannequin m) {
        World w = m.getWorld();
        Block blk = w.getBlockAt(b.tx, b.ty, b.tz);
        if (blk.isEmpty() || blk.isLiquid()) {
            b.progress = 0;
            return;
        }
        float hardness = blk.getType().getHardness();
        if (hardness < 0) {
            stopBot(b, "Dieser Block ist unzerstoerbar.");
            return;
        }
        if (!inReach(m, b.tx, b.ty, b.tz)) {
            stopBot(b, "Block ausser Reichweite. Hol den Bot mit /bot tp naeher heran.");
            return;
        }
        Location center = blk.getLocation().add(0.5, 0.5, 0.5);
        int slot = bestToolSlot(b, blk);
        ItemStack tool = slot >= 0 ? b.inv.getItem(slot) : null;
        if (b.heldSlot != slot) {
            setHand(m, tool);
            b.heldSlot = slot;
        }
        if (b.progress == 0) {
            lookAt(m, center);
        }
        ItemStack calc = tool != null ? tool : new ItemStack(Material.AIR);
        float speed = blk.getDestroySpeed(calc, true) * speedMult;
        boolean harvest = !blk.getBlockData().requiresCorrectToolForDrops() || blk.isPreferredTool(calc);
        double need = Math.max(1.0, Math.ceil(hardness * (harvest ? 30.0 : 100.0) / Math.max(speed, 0.01f)));
        b.progress += interval;
        if (b.progress % 6 < interval) {
            m.swingMainHand();
            w.spawnParticle(Particle.BLOCK, center, 6, 0.25, 0.25, 0.25, blk.getBlockData());
            w.playSound(center, blk.getBlockData().getSoundGroup().getHitSound(), SoundCategory.BLOCKS, 0.5f, 0.8f);
        }
        if (b.progress < need) {
            return;
        }
        b.progress = 0;
        finishMine(b, m, blk, tool, slot, center);
    }

    private void finishMine(Bot b, Mannequin m, Block blk, ItemStack tool, int slot, Location center) {
        World w = m.getWorld();
        Collection<ItemStack> drops = tool != null ? blk.getDrops(tool) : blk.getDrops();
        w.spawnParticle(Particle.BLOCK, center, 30, 0.3, 0.3, 0.3, blk.getBlockData());
        w.playSound(center, blk.getBlockData().getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1f, 1f);
        blk.setType(Material.AIR);
        boolean full = false;
        for (ItemStack d : drops) {
            Map<Integer, ItemStack> left = b.inv.addItem(d);
            for (ItemStack r : left.values()) {
                w.dropItemNaturally(center, r);
                full = true;
            }
        }
        damageTool(b, m, tool, slot);
        if (full) {
            stopBot(b, "Inventar voll - Items wurden fallen gelassen. Bot gestoppt.");
        } else if (!repeat) {
            stopBot(b, "Fertig abgebaut.");
        }
    }

    private void damageTool(Bot b, Mannequin m, ItemStack tool, int slot) {
        if (tool == null || slot < 0) {
            return;
        }
        int max = tool.getType().getMaxDurability();
        if (max <= 0) {
            return;
        }
        ItemMeta meta = tool.getItemMeta();
        if (meta instanceof Damageable d) {
            if (d.isUnbreakable()) {
                return;
            }
            int lvl = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
            if (lvl > 0 && random.nextInt(lvl + 1) != 0) {
                return;
            }
            d.setDamage(d.getDamage() + 1);
            tool.setItemMeta(d);
            if (d.getDamage() >= max) {
                b.inv.setItem(slot, null);
                setHand(m, null);
                b.heldSlot = -1;
            } else {
                b.inv.setItem(slot, tool);
                setHand(m, tool);
            }
        }
    }

    private void tickPlace(Bot b, Mannequin m) {
        Block blk = m.getWorld().getBlockAt(b.tx, b.ty, b.tz);
        if (!(blk.isEmpty() || blk.isReplaceable())) {
            b.progress = 0;
            return;
        }
        if (!inReach(m, b.tx, b.ty, b.tz)) {
            stopBot(b, "Ziel ausser Reichweite. Hol den Bot mit /bot tp naeher heran.");
            return;
        }
        b.progress += interval;
        if (b.progress < placeInterval) {
            return;
        }
        int slot = -1;
        for (int i = 0; i < b.inv.getSize(); i++) {
            ItemStack it = b.inv.getItem(i);
            if (it == null || it.getType().isAir() || !it.getType().isBlock()) {
                continue;
            }
            if (b.placeMat != null && it.getType() != b.placeMat) {
                continue;
            }
            slot = i;
            break;
        }
        if (slot < 0) {
            stopBot(b, "Keine passenden Bloecke im Inventar. Bot gestoppt.");
            return;
        }
        b.progress = 0;
        ItemStack st = b.inv.getItem(slot);
        if (b.heldSlot != slot) {
            setHand(m, st);
            b.heldSlot = slot;
        }
        Location center = blk.getLocation().add(0.5, 0.5, 0.5);
        lookAt(m, center);
        blk.setBlockData(st.getType().createBlockData());
        m.swingMainHand();
        m.getWorld().playSound(center, blk.getBlockData().getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1f, 1f);
        if (st.getAmount() <= 1) {
            b.inv.setItem(slot, null);
            b.heldSlot = -1;
        } else {
            st.setAmount(st.getAmount() - 1);
        }
        if (!repeat) {
            stopBot(b, "Fertig platziert.");
        }
    }

    // ------------------------------------------------------------------ Nachrichten

    private void ok(CommandSender s, String t) {
        s.sendMessage(Component.text(t, NamedTextColor.GREEN));
    }

    private void err(CommandSender s, String t) {
        s.sendMessage(Component.text(t, NamedTextColor.RED));
    }

    private void info(CommandSender s, String t) {
        s.sendMessage(Component.text(t, NamedTextColor.GRAY));
    }

    private void help(CommandSender s) {
        info(s, "BuilderBots - Befehle:");
        info(s, "/bot create <Name> | remove <Name> | list");
        info(s, "/bot tp <Name>  (Bot zu dir)");
        info(s, "/bot goto <Name> <X> <Y> <Z>  (Bot dorthin teleportieren)");
        info(s, "/bot look <Name> <Yaw> <Pitch> | lookat <Name> <X> <Y> <Z>");
        info(s, "/bot mine <Name> <X> <Y> <Z>");
        info(s, "/bot place <Name> <X> <Y> <Z> [Material]");
        info(s, "/bot stop <Name> | inventory <Name>");
        info(s, "/bot claim <Name> | unclaim <Name>");
        info(s, "Koordinaten duerfen mit ~ relativ zu dir angegeben werden.");
    }

    // ------------------------------------------------------------------ Befehle

    private Bot access(CommandSender s, String name) {
        Bot b = bots.get(name.toLowerCase(Locale.ROOT));
        if (b == null) {
            err(s, "Bot '" + name + "' gibt es nicht. /bot list zeigt alle Bots.");
            return null;
        }
        if (b.owner != null) {
            boolean isOwner = s instanceof Player p && p.getUniqueId().equals(b.owner);
            if (!isOwner && !s.hasPermission("botplugin.admin")) {
                err(s, "Dieser Bot ist von einem anderen Spieler geclaimt.");
                return null;
            }
        }
        return b;
    }

    private double coord(String s, double base) {
        if (s.startsWith("~")) {
            return base + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0.0);
        }
        return Double.parseDouble(s);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            if (sub.equals("list")) {
                cmdList(sender);
                return true;
            }
            if (sub.equals("create")) {
                cmdCreate(sender, args);
                return true;
            }
            if (!NEED_BOT.contains(sub)) {
                help(sender);
                return true;
            }
            if (args.length < 2) {
                err(sender, "Bot-Name fehlt. Nutzung: /bot " + sub + " <Name> ...");
                return true;
            }
            Bot b = access(sender, args[1]);
            if (b == null) {
                return true;
            }
            switch (sub) {
                case "remove" -> cmdRemove(sender, b);
                case "tp" -> cmdTp(sender, b);
                case "goto" -> cmdGoto(sender, b, args);
                case "look" -> cmdLook(sender, b, args);
                case "lookat" -> cmdLookAt(sender, b, args);
                case "mine" -> cmdMine(sender, b, args);
                case "place" -> cmdPlace(sender, b, args);
                case "stop" -> cmdStop(sender, b);
                case "inventory" -> cmdInventory(sender, b);
                case "claim" -> cmdClaim(sender, b);
                case "unclaim" -> cmdUnclaim(sender, b);
                default -> help(sender);
            }
        } catch (NumberFormatException e) {
            err(sender, "Ungueltige Zahl in den Koordinaten/Winkeln.");
        } catch (Exception e) {
            err(sender, "Fehler: " + e.getMessage());
            getLogger().warning("Befehlsfehler: " + e);
        }
        return true;
    }

    private Player needPlayer(CommandSender s) {
        if (s instanceof Player p) {
            return p;
        }
        err(s, "Dieser Befehl geht nur als Spieler.");
        return null;
    }

    private void cmdList(CommandSender s) {
        if (bots.isEmpty()) {
            info(s, "Es gibt noch keine Bots. Mit /bot create <Name> erstellen.");
            return;
        }
        ok(s, "Bots (" + bots.size() + "/" + maxBots + "):");
        for (Bot b : bots.values()) {
            String owner = b.owner == null ? "frei" : String.valueOf(Bukkit.getOfflinePlayer(b.owner).getName());
            info(s, "- " + b.name + " | " + b.worldName + " " + (int) b.x + " " + (int) b.y + " " + (int) b.z
                    + " | " + b.mode.name() + " | Besitzer: " + owner);
        }
    }

    private void cmdCreate(CommandSender s, String[] args) {
        Player p = needPlayer(s);
        if (p == null) {
            return;
        }
        if (args.length < 2) {
            err(s, "Nutzung: /bot create <Name>");
            return;
        }
        String name = args[1];
        if (!NAME.matcher(name).matches()) {
            err(s, "Name: 1-16 Zeichen, nur Buchstaben, Zahlen und _.");
            return;
        }
        if (bots.containsKey(name.toLowerCase(Locale.ROOT))) {
            err(s, "Einen Bot mit diesem Namen gibt es schon.");
            return;
        }
        if (bots.size() >= maxBots) {
            err(s, "Maximal " + maxBots + " Bots gleichzeitig erlaubt.");
            return;
        }
        Bot b = new Bot();
        b.name = name;
        b.inv = newInv(name);
        Location l = p.getLocation();
        bots.put(name.toLowerCase(Locale.ROOT), b);
        moveBot(b, l);
        saveData();
        ok(s, "Bot " + name + " erstellt. Mit /bot claim " + name + " kannst du ihn fuer dich sperren.");
    }

    private void cmdRemove(CommandSender s, Bot b) {
        Mannequin m = findEntity(b);
        Location drop = m != null ? m.getLocation() : new Location(Bukkit.getWorld(b.worldName), b.x, b.y, b.z);
        if (drop.getWorld() != null) {
            for (ItemStack it : b.inv.getContents()) {
                if (it != null && !it.getType().isAir()) {
                    drop.getWorld().dropItemNaturally(drop, it);
                }
            }
        }
        b.inv.clear();
        if (m != null) {
            m.remove();
        }
        releaseTickets(b);
        bots.remove(b.name.toLowerCase(Locale.ROOT));
        saveData();
        ok(s, "Bot " + b.name + " entfernt (Inventar wurde fallen gelassen).");
    }

    private void cmdTp(CommandSender s, Bot b) {
        Player p = needPlayer(s);
        if (p == null) {
            return;
        }
        moveBot(b, p.getLocation());
        ok(s, "Bot " + b.name + " zu dir teleportiert.");
    }

    private void cmdGoto(CommandSender s, Bot b, String[] args) {
        if (args.length < 5) {
            err(s, "Nutzung: /bot goto <Name> <X> <Y> <Z>");
            return;
        }
        Location base = s instanceof Player p ? p.getLocation() : new Location(Bukkit.getWorld(b.worldName), 0, 0, 0);
        World w = Bukkit.getWorld(b.worldName);
        if (w == null) {
            err(s, "Welt des Bots nicht gefunden.");
            return;
        }
        double x = coord(args[2], base.getX());
        double y = coord(args[3], base.getY());
        double z = coord(args[4], base.getZ());
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
            err(s, "Y liegt ausserhalb der Welt.");
            return;
        }
        Location target = new Location(w, x, y, z, b.yaw, b.pitch);
        if (!w.getWorldBorder().isInside(target)) {
            err(s, "Ziel liegt ausserhalb der Weltgrenze.");
            return;
        }
        moveBot(b, target);
        ok(s, "Bot " + b.name + " teleportiert.");
    }

    private void cmdLook(CommandSender s, Bot b, String[] args) {
        if (args.length < 4) {
            err(s, "Nutzung: /bot look <Name> <Yaw> <Pitch>");
            return;
        }
        float yaw = Float.parseFloat(args[2]);
        float pitch = Math.max(-90f, Math.min(90f, Float.parseFloat(args[3])));
        Mannequin m = findEntity(b);
        if (m == null) {
            err(s, "Bot-Entity ist nicht geladen. Versuch /bot tp " + b.name);
            return;
        }
        m.setRotation(yaw, pitch);
        b.yaw = yaw;
        b.pitch = pitch;
        ok(s, "Blickrichtung gesetzt.");
    }

    private void cmdLookAt(CommandSender s, Bot b, String[] args) {
        if (args.length < 5) {
            err(s, "Nutzung: /bot lookat <Name> <X> <Y> <Z>");
            return;
        }
        Mannequin m = findEntity(b);
        if (m == null) {
            err(s, "Bot-Entity ist nicht geladen. Versuch /bot tp " + b.name);
            return;
        }
        Location base = s instanceof Player p ? p.getLocation() : m.getLocation();
        double x = Math.floor(coord(args[2], base.getX())) + 0.5;
        double y = Math.floor(coord(args[3], base.getY())) + 0.5;
        double z = Math.floor(coord(args[4], base.getZ())) + 0.5;
        lookAt(m, new Location(m.getWorld(), x, y, z));
        Location l = m.getLocation();
        b.yaw = l.getYaw();
        b.pitch = l.getPitch();
        ok(s, "Bot schaut jetzt zu den Koordinaten.");
    }

    private int[] blockArgs(CommandSender s, Bot b, String[] args, Mannequin m) {
        Location base = s instanceof Player p ? p.getLocation() : m.getLocation();
        int x = (int) Math.floor(coord(args[2], base.getX()));
        int y = (int) Math.floor(coord(args[3], base.getY()));
        int z = (int) Math.floor(coord(args[4], base.getZ()));
        return new int[] {x, y, z};
    }

    private void cmdMine(CommandSender s, Bot b, String[] args) {
        if (args.length < 5) {
            err(s, "Nutzung: /bot mine <Name> <X> <Y> <Z>");
            return;
        }
        Mannequin m = findEntity(b);
        if (m == null) {
            err(s, "Bot-Entity ist nicht geladen. Versuch /bot tp " + b.name);
            return;
        }
        int[] c = blockArgs(s, b, args, m);
        World w = m.getWorld();
        if (c[1] < w.getMinHeight() || c[1] >= w.getMaxHeight()) {
            err(s, "Y liegt ausserhalb der Welt.");
            return;
        }
        if (!inReach(m, c[0], c[1], c[2])) {
            err(s, "Ausser Reichweite (" + reach + " Bloecke). Hol den Bot mit /bot tp naeher heran.");
            return;
        }
        Block blk = w.getBlockAt(c[0], c[1], c[2]);
        if (!blk.isEmpty() && blk.getType().getHardness() < 0) {
            err(s, "Dieser Block ist unzerstoerbar.");
            return;
        }
        if (blk.getState() instanceof Container) {
            err(s, "Behaelter (Kisten, Ofen ...) werden aus Sicherheitsgruenden nicht abgebaut.");
            return;
        }
        b.tx = c[0];
        b.ty = c[1];
        b.tz = c[2];
        b.mode = Mode.MINE;
        b.progress = 0;
        saveData();
        ok(s, "Bot " + b.name + " baut jetzt " + c[0] + " " + c[1] + " " + c[2] + " ab"
                + (repeat ? " (wiederholt, bis /bot stop)." : "."));
    }

    private void cmdPlace(CommandSender s, Bot b, String[] args) {
        if (args.length < 5) {
            err(s, "Nutzung: /bot place <Name> <X> <Y> <Z> [Material]");
            return;
        }
        Mannequin m = findEntity(b);
        if (m == null) {
            err(s, "Bot-Entity ist nicht geladen. Versuch /bot tp " + b.name);
            return;
        }
        int[] c = blockArgs(s, b, args, m);
        World w = m.getWorld();
        if (c[1] < w.getMinHeight() || c[1] >= w.getMaxHeight()) {
            err(s, "Y liegt ausserhalb der Welt.");
            return;
        }
        if (!inReach(m, c[0], c[1], c[2])) {
            err(s, "Ausser Reichweite (" + reach + " Bloecke). Hol den Bot mit /bot tp naeher heran.");
            return;
        }
        Location ml = m.getLocation();
        int bx = ml.getBlockX();
        int by = ml.getBlockY();
        int bz = ml.getBlockZ();
        if (c[0] == bx && c[2] == bz && (c[1] == by || c[1] == by + 1)) {
            err(s, "Der Bot kann nicht in seinen eigenen Koerper platzieren.");
            return;
        }
        Material mat = null;
        if (args.length >= 6) {
            mat = Material.matchMaterial(args[5]);
            if (mat == null || !mat.isBlock() || mat.isAir()) {
                err(s, "'" + args[5] + "' ist kein platzierbarer Block.");
                return;
            }
        }
        b.placeMat = mat;
        b.tx = c[0];
        b.ty = c[1];
        b.tz = c[2];
        b.mode = Mode.PLACE;
        b.progress = 0;
        saveData();
        ok(s, "Bot " + b.name + " platziert jetzt auf " + c[0] + " " + c[1] + " " + c[2]
                + (repeat ? " (wiederholt, bis /bot stop)." : ".")
                + " Die Bloecke muessen im Bot-Inventar liegen (/bot inventory " + b.name + ").");
    }

    private void cmdStop(CommandSender s, Bot b) {
        b.mode = Mode.NONE;
        b.progress = 0;
        Mannequin m = findEntity(b);
        if (m != null) {
            setHand(m, null);
        }
        b.heldSlot = -1;
        saveData();
        ok(s, "Bot " + b.name + " gestoppt.");
    }

    private void cmdInventory(CommandSender s, Bot b) {
        Player p = needPlayer(s);
        if (p == null) {
            return;
        }
        p.openInventory(b.inv);
    }

    private void cmdClaim(CommandSender s, Bot b) {
        Player p = needPlayer(s);
        if (p == null) {
            return;
        }
        if (b.owner != null && b.owner.equals(p.getUniqueId())) {
            info(s, "Der Bot gehoert dir schon.");
            return;
        }
        if (b.owner != null) {
            err(s, "Der Bot ist schon geclaimt.");
            return;
        }
        b.owner = p.getUniqueId();
        saveData();
        ok(s, "Bot " + b.name + " gehoert jetzt dir. Niemand sonst kann ihn steuern.");
    }

    private void cmdUnclaim(CommandSender s, Bot b) {
        b.owner = null;
        saveData();
        ok(s, "Bot " + b.name + " ist wieder frei.");
    }

    // ------------------------------------------------------------------ Tab-Completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && NEED_BOT.contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Bot b : bots.values()) {
                if (b.name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(b.name);
                }
            }
        }
        return out;
    }
}

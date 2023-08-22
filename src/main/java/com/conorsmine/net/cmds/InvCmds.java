package com.conorsmine.net.cmds;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import com.conorsmine.net.InventoryPath;
import com.conorsmine.net.ItemStore;
import com.conorsmine.net.PlayerDataManipulator;
import com.conorsmine.net.inventory.EditorInventory;
import com.conorsmine.net.inventory.NBTItemTags;
import com.conorsmine.net.messages.MsgFormatter;
import com.conorsmine.net.messages.PluginMsgs;
import com.conorsmine.net.mojangson.NBTQueryResult;
import com.conorsmine.net.mojangson.StringUtils;
import com.conorsmine.net.mojangson.data.ICompoundData;
import com.conorsmine.net.mojangson.data.NBTCompoundData;
import com.conorsmine.net.mojangson.data.NBTCompoundListData;
import de.tr7zw.nbtapi.NBTCompound;
import de.tr7zw.nbtapi.NBTCompoundList;
import de.tr7zw.nbtapi.NBTContainer;
import de.tr7zw.nbtapi.data.NBTData;
import de.tr7zw.nbtapi.data.PlayerData;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@CommandAlias("pdm")
public final class InvCmds extends BaseCommand {

    private final AtomicInteger currentExecutions = new AtomicInteger(0);
    private final List<SearchResult> foundPlayers = Collections.synchronizedList(new LinkedList<>());

    private final PlayerDataManipulator pl;
    private final int EXECUTIONS_MAX;

    public InvCmds(PlayerDataManipulator pl) {
        this.pl = pl;
        this.EXECUTIONS_MAX = pl.CONF.getMaxSearchExecutors();
    }



    @Subcommand("open")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS + " false|true")
    @Description("Opens a players inventory")
    @CommandPermission("pdm.inv.open")
    private void openInventory(final Player player, final @NotNull OfflinePlayer target, final InventoryPath inventoryPath) {
        if (inventoryPath == null) { pl.sendMsg(player, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }
        if (target.isOnline()) ((Player) target).saveData();

        new EditorInventory.Builder(pl).createInventory(target, inventoryPath).openInventory(player);
        pl.sendMsg(player, "§7Opening " + String.format(PluginMsgs.INV_FORMAT.getMsg(), target.getName(), inventoryPath.getSectionName()));
    }

    @Subcommand("list")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS)
    @Description("Lists the contents of the players inv")
    @CommandPermission("pdm.inv.list")
    private void listInventory(final CommandSender sender, final @NotNull OfflinePlayer target, final InventoryPath inventoryPath) {
        if (inventoryPath == null) { pl.sendMsg(sender, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }
        if (target.isOnline()) ((Player) target).saveData();

        pl.INV_UTILS.getItemNBTsFromPathAsync(NBTData.getOfflinePlayerData(target.getUniqueId()).getCompound(), inventoryPath.getPath())
                .whenComplete((itemNBTsFromPath, e) -> {
                    if (e != null) return;
                    pl.sendCmdHeader(sender, "List");
                    MsgFormatter.sendFormattedListMsg(pl, sender, itemNBTsFromPath);
                });
    }

    @Subcommand("info")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS)
    @Description("Provides the nbt of the item")
    @CommandPermission("pdm.inv.info")
    private void printInfo(final CommandSender sender, final OfflinePlayer target, final InventoryPath inventoryPath, final int slot) {
        if (inventoryPath == null) { pl.sendMsg(sender, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }

        pl.INV_UTILS.getItemNBTsMapFromPathAsync(NBTData.getOfflinePlayerData(target.getUniqueId()).getCompound(), inventoryPath.getPath())
                .whenComplete((map, e) -> {
                    final NBTCompound compound = map.get(slot);
                    if (compound == null) {
                        pl.sendMsg(sender, String.format(PluginMsgs.ITEM_NOT_FOUND.getMsg(), slot));
                        return;
                    }

                    pl.sendCmdHeader(sender, "Info");
                    pl.sendMsg(
                            sender,
                            "§7Basic info:",
                            String.format(
                                    "  §7>> id: §9%s §7data: §6%d §7count: §b%d",
                                    compound.getString(NBTItemTags.ID.getTagName()),
                                    compound.getInteger(NBTItemTags.DAMAGE.getTagName()),
                                    compound.getInteger(NBTItemTags.COUNT.getTagName())
                            ),
                            "§7Item NBT:"
                    );

                    sendColoredNBT(sender, compound);
                });
    }

    @Subcommand("clear")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS)
    @Description("Clears the players inventory")
    @CommandPermission("pdm.inv.clear")
    private void clearInventory(final CommandSender sender, final @NotNull OfflinePlayer target, final InventoryPath inventoryPath) {
        if (inventoryPath == null) { pl.sendMsg(sender, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }

        final PlayerData playerData = NBTData.getOfflinePlayerData(target.getUniqueId());

        final NBTQueryResult result = pl.MOJANGSON.getDataFromPath(new NBTCompoundData(playerData.getCompound()), inventoryPath.getPath());
        final ICompoundData<?> invData = (ICompoundData<?>) result.getData();

        switch (invData.getType()) {
            case COMPOUND: ((NBTCompoundData) invData).getData().clearNBT(); break;
            case COMPOUND_LIST: ((NBTCompoundListData) invData).getData().clear(); break;
        }

        reloadPlayerData(target, playerData);
        pl.sendMsg(sender, "§7Cleared " + String.format(PluginMsgs.INV_FORMAT.getMsg(), target.getName(), inventoryPath.getSectionName()));
    }

    @Subcommand("remove")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS)
    @Description("Removes the content of the players inventory at that slots")
    @CommandPermission("pdm.inv.remove")
    private void removeFromInventory(final CommandSender sender, final OfflinePlayer target, final InventoryPath inventoryPath, final int slot) {
        if (inventoryPath == null) { pl.sendMsg(sender, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }

        final PlayerData playerData = NBTData.getOfflinePlayerData(target.getUniqueId());
        pl.INV_UTILS.removeNBTAsync(playerData.getCompound(), inventoryPath.getPath(),
                (nbt) -> (nbt.getInteger(NBTItemTags.SLOT.getTagName()) == slot)
        ).whenComplete((nbt, e) -> {
            if (nbt == null) {
                pl.sendMsg(sender, String.format(PluginMsgs.ITEM_NOT_FOUND.getMsg(), slot));
                return;
            }

            pl.sendMsg(sender,
                    String.format(
                            "§7Removed §b%d§7x §9%s §7from §6%s§7's §b%s§7.",
                            nbt.getInteger(NBTItemTags.COUNT.getTagName()),
                            nbt.getString(NBTItemTags.ID.getTagName()),
                            target.getName(),
                            inventoryPath.getSectionName()
                    ));

            reloadPlayerData(target, playerData);
        });
    }

    @Subcommand("add")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS + CmdCompletions.ITEM_IDS + "@range:255 @range:64")
    @Description("Adds the item to the players inventory")
    @CommandPermission("pdm.inv.add")
    private void addToInventory(final CommandSender sender, final OfflinePlayer target, final InventoryPath inventoryPath,
                                final String itemId, @Default("0") final short data, @Default("1") final int count) {
        if (inventoryPath == null) { pl.sendMsg(sender, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }
        if (StringUtils.isNothingString(itemId)) { pl.sendMsg(sender, PluginMsgs.MISSING_ITEM_ID.getMsg()); return; }
        if (target.isOnline()) ((Player) target).saveData();

        addNBTToInventory(sender, target, inventoryPath,
                String.format("{\"%s\": %s, \"%s\": %d, \"%s\": %d}",
                        NBTItemTags.ID.getTagName(), itemId,
                        NBTItemTags.DAMAGE.getTagName(), data,
                        NBTItemTags.COUNT.getTagName(), count
                )
        );
    }

    @Subcommand("addnbt")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS)
    @Description("Adds the item nbt to the players inventory")
    @CommandPermission("pdm.inv.addnbt")
    private void addNBTToInventory(final CommandSender sender, final OfflinePlayer target, final InventoryPath inventoryPath, final String nbt) {
        if (inventoryPath == null) { pl.sendMsg(sender, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }
        if (StringUtils.isNothingString(nbt)) { pl.sendMsg(sender, "§cItem NBT cannot be null!"); return; }

        try {
            final NBTContainer nbtItem = new NBTContainer(nbt);
            if (!isItem(sender, nbtItem)) return;

            final PlayerData playerData = NBTData.getOfflinePlayerData(target.getUniqueId());
            final NBTQueryResult result = pl.MOJANGSON.getDataFromPath(new NBTCompoundData(playerData.getCompound()), inventoryPath.getPath());
            final ICompoundData<?> invNBT = ((ICompoundData<?>) result.getData());
            if (invNBT == null) { pl.sendMsg(sender, "§7There was an error getting the players inventory!"); return; }

            NBTCompound itemComp = null;
            int slot = 0;
            switch (invNBT.getType()) {
                case COMPOUND:
                    itemComp = ((NBTCompoundData) invNBT).getData();
                    itemComp.clearNBT();
                    break;

                case COMPOUND_LIST:
                    final NBTCompoundList invList = ((NBTCompoundListData) invNBT).getData();
                    final int freeInvSlot = getFreeInvSlot(invList, inventoryPath);
                    if (freeInvSlot == -Integer.MAX_VALUE) { pl.sendMsg(sender, "§cCould not find a free inv slot."); return; }

                    itemComp = invList.addCompound();
                    slot = freeInvSlot;
                    break;
            }

            if (itemComp == null) { pl.sendMsg(sender, "§7There was an error creating a new item!"); return; }

            //
            itemComp.setString(NBTItemTags.ID.getTagName(), nbtItem.getString(NBTItemTags.ID.getTagName()));
            itemComp.setShort(NBTItemTags.DAMAGE.getTagName(), nbtItem.getShort(NBTItemTags.DAMAGE.getTagName()));
            itemComp.setInteger(NBTItemTags.COUNT.getTagName(), nbtItem.getInteger(NBTItemTags.COUNT.getTagName()));
            itemComp.setInteger(NBTItemTags.SLOT.getTagName(), slot);

            reloadPlayerData(target, playerData);
            pl.sendMsg(sender, String.format("§7Added to §6%s§7's §b%s§7:", target.getName(), inventoryPath.getSectionName()));
            sendColoredNBT(sender, nbtItem);
        }
        catch (Exception e) { pl.sendMsg(sender, "§7Unable to parse NBT. Something must be malformed."); }
    }

    @Subcommand("addstore")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS + CmdCompletions.ITEM_STORES)
    @Description("Adds the item from a stored to the players inventory")
    @CommandPermission("pdm.inv.addstore")
    private void addItemFromStore(final CommandSender sender, final OfflinePlayer target, final InventoryPath inventoryPath, final ItemStore itemStore) {
        if (itemStore == null) { pl.sendMsg(sender, "§7Couldn't find store with that name."); return; }
        addNBTToInventory(sender, target, inventoryPath, itemStore.getStoredNBT());
    }

    @Subcommand("search")
    @CommandCompletion(CmdCompletions.ITEM_IDS + "@range:255")
    @Description("Returns players which have the specified item")
    @CommandPermission("pdm.inv.search")
    private void searchInventories(final CommandSender sender, final String itemId, @Default("0") final short data) {
        if (itemId == null) { pl.sendMsg(sender, PluginMsgs.MISSING_ITEM_ID.getMsg()); return; }
        if (currentExecutions.get() > 0) {
            pl.sendMsg(sender, "§cAlready searching for an item, please wait until the search is complete to begin a new one.");
            return;
        }

        pl.sendCmdHeader(sender, "Search");
        pl.sendMsg(sender, "§7This might take a while...");
        final Queue<OfflinePlayer> offlinePlayers = new ConcurrentLinkedQueue<>(Arrays.asList(pl.getServer().getOfflinePlayers()));

        foundPlayers.clear();
        runSearchQueryAsync(offlinePlayers, itemId, data)
                .whenComplete((v, e) -> {
                    pl.sendMsg(sender,
                            String.format("§7The following players contain §9%s §6%s§7:", itemId, (data < 0) ? "" : data));

                    foundPlayers.forEach((foundPlayer) ->
                            pl.sendMsg(sender,
                            String.format("  §7>> §6%s §7in §b%s",
                            foundPlayer.getPlayer().getName(),
                            foundPlayer.getInventoryPath().getSectionName())));
                });
    }

    @Subcommand("store")
    @CommandCompletion(CmdCompletions.OFFLINE_PLAYERS + CmdCompletions.INVENTORY_PATHS)
    @Description("Stores the items NBT in a file for later use")
    @CommandPermission("pdm.inv.store")
    private void storeItem(final CommandSender sender, final OfflinePlayer target, final InventoryPath inventoryPath, final int slot) {
        if (inventoryPath == null) { pl.sendMsg(sender, PluginMsgs.MISSING_PATH_WRAPPER.getMsg()); return; }

        pl.INV_UTILS.getItemNBTsMapFromPathAsync(NBTData.getOfflinePlayerData(target.getUniqueId()).getCompound(), inventoryPath.getPath())
                .whenComplete((map, e) -> {
                    if (map.isEmpty()) return;
                    pl.sendCmdHeader(sender, "Store");

                    final NBTCompound compound = map.get(slot);
                    if (compound == null) { pl.sendMsg(sender, String.format(PluginMsgs.ITEM_NOT_FOUND.getMsg(), slot)); return; }

                    final String fileName = pl.ITEM_STORES.storeNBT(sender, target, compound);
                    if (fileName == null) { pl.sendMsg(sender, "§cCouldn't save players item nbt to file."); }
                    else {
                        sendColoredNBT(sender, compound);
                        pl.sendMsg(sender, String.format("§7Saved players item nbt to file §6%s§7.", fileName));
                    }
                });
    }




    private void reloadPlayerData(final OfflinePlayer player, final PlayerData playerData) {
        playerData.saveChanges();
        if (player.isOnline()) ((Player) player).loadData();
    }

    private void sendColoredNBT(final CommandSender sender, final NBTCompound nbt) {
        pl.sendMsg(sender,
                TextComponent.toLegacyText(pl.MOJANGSON.getInteractiveMojangson(nbt, null))
        );
    }

    private boolean isItem(final CommandSender sender, final NBTCompound nbt) {
        boolean isItem = true;
        if (!nbt.hasKey(NBTItemTags.ID.getTagName())) { pl.sendMsg(sender, "§7NBT is missing §6ID §7tag!"); isItem = false; }
        if (!nbt.hasKey(NBTItemTags.DAMAGE.getTagName())) { pl.sendMsg(sender, "§7NBT is missing §6DAMAGE §7tag!"); isItem = false; }
        if (!nbt.hasKey(NBTItemTags.COUNT.getTagName())) { pl.sendMsg(sender, "§7NBT is missing §6COUNT §7tag!"); isItem = false; }

        return isItem;
    }

    private int getFreeInvSlot(final NBTCompoundList inv, final InventoryPath inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            int finalI = i;
            if (inv.stream().anyMatch((nbt) -> nbt.getInteger(NBTItemTags.SLOT.getTagName()) == finalI)) {
                continue;
            }

            return i;
        }

        return -Integer.MAX_VALUE;  // Indicating none was found
    }

    private CompletableFuture<Void> runSearchQueryAsync(final Queue<OfflinePlayer> queue, final String itemID, final short itemData) {
        final CompletableFuture<Void> future = new CompletableFuture<>();

        recursiveSearchQuery(future, queue, itemID, itemData);
        return future;
    }

    private void recursiveSearchQuery(final CompletableFuture<Void> future, final Queue<OfflinePlayer> queue, final String itemID, final short itemData) {
        if (queue.isEmpty()) { currentExecutions.set(0); future.complete(null); return; }
        if (queue.size() <= EXECUTIONS_MAX && currentExecutions.get() > 0) return;
        if (currentExecutions.get() >= EXECUTIONS_MAX) return;
        currentExecutions.incrementAndGet();

        if (currentExecutions.get() < EXECUTIONS_MAX) recursiveSearchQuery(future, queue, itemID, itemData);

        pl.getServer().getScheduler().runTaskAsynchronously(pl, () -> {
            final OfflinePlayer player = queue.poll();
            if (player == null) return;

            final PlayerData playerData = NBTData.getOfflinePlayerData(player.getUniqueId());

            for (InventoryPath path : pl.CONF.getInventoryPaths()) {
                final List<NBTCompound> inventoryNBT = pl.INV_UTILS.getItemNBTsFromPath(
                        playerData.getCompound(),
                        path.getPath()
                );

                for (NBTCompound nbtItem : inventoryNBT) {

                    if (!nbtItem.getString(NBTItemTags.ID.getTagName()).equals(itemID)) continue;
                    if (itemData >= 0 && nbtItem.getShort(NBTItemTags.DAMAGE.getTagName()) != itemData) continue;

                    foundPlayers.add(new SearchResult(player, path));
                    break;
                }
            }


            currentExecutions.decrementAndGet();
            recursiveSearchQuery(future, queue, itemID, itemData);
        });
    }



    private static class SearchResult implements Comparator<String> {

        private final OfflinePlayer player;
        private final InventoryPath inventoryPath;

        public SearchResult(OfflinePlayer player, InventoryPath inventoryPath) {
            this.player = player;
            this.inventoryPath = inventoryPath;
        }

        public OfflinePlayer getPlayer() {
            return player;
        }

        public InventoryPath getInventoryPath() {
            return inventoryPath;
        }

        @Override
        public int compare(String o1, String o2) {
            return String.CASE_INSENSITIVE_ORDER.compare(o1, o2);
        }
    }
}

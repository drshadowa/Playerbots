package com.example.macesmash;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Kits {

    public record Kit(String name, Item icon, ItemStack head, ItemStack chest, ItemStack legs,
                      ItemStack feet, ItemStack main, ItemStack off, List<ItemStack> items) {}

    public static final Map<String, Kit> KITS = new LinkedHashMap<>();

    static ItemStack st(Item i) {
        return i == null ? ItemStack.EMPTY : new ItemStack(i);
    }

    static void add(String name, Item icon, Item h, Item c, Item l, Item f,
                    Item main, Item off, ItemStack... extras) {
        KITS.put(name.toLowerCase(Locale.ROOT),
                new Kit(name, icon, st(h), st(c), st(l), st(f), st(main), st(off),
                        new ArrayList<>(List.of(extras))));
    }

    static {
        add("Sigma", Items.DIAMOND_SWORD,
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
                Items.DIAMOND_SWORD, Items.SHIELD,
                new ItemStack(Items.GOLDEN_APPLE, 8), new ItemStack(Items.COOKED_BEEF, 32),
                new ItemStack(Items.ENDER_PEARL, 4));

        add("Knight", Items.IRON_SWORD,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
                Items.IRON_SWORD, Items.SHIELD,
                new ItemStack(Items.BREAD, 32));

        add("Archer", Items.BOW,
                Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS,
                Items.BOW, null,
                new ItemStack(Items.ARROW, 64), new ItemStack(Items.STONE_SWORD),
                new ItemStack(Items.COOKED_PORKCHOP, 32));

        add("Mace", Items.MACE,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
                Items.MACE, Items.SHIELD,
                new ItemStack(Items.WIND_CHARGE, 16), new ItemStack(Items.GOLDEN_APPLE, 4),
                new ItemStack(Items.COOKED_BEEF, 32));

        add("Tank", Items.NETHERITE_CHESTPLATE,
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
                Items.NETHERITE_SWORD, Items.SHIELD,
                new ItemStack(Items.GOLDEN_APPLE, 16));
    }

    // Gives a kit to a real player: armor is worn, everything else goes in the inventory.
    public static void give(ServerPlayer p, Kit k) {
        if (!k.head().isEmpty()) p.setItemSlot(EquipmentSlot.HEAD, k.head().copy());
        if (!k.chest().isEmpty()) p.setItemSlot(EquipmentSlot.CHEST, k.chest().copy());
        if (!k.legs().isEmpty()) p.setItemSlot(EquipmentSlot.LEGS, k.legs().copy());
        if (!k.feet().isEmpty()) p.setItemSlot(EquipmentSlot.FEET, k.feet().copy());
        Inventory inv = p.getInventory();
        if (!k.main().isEmpty()) inv.add(k.main().copy());
        if (!k.off().isEmpty()) inv.add(k.off().copy());
        for (ItemStack s : k.items()) {
            inv.add(s.copy());
        }
    }

    // Equips a kit on a bot: armor, a weapon in the main hand, and the off hand.
    public static void giveBot(LivingEntity b, Kit k) {
        b.setItemSlot(EquipmentSlot.HEAD, k.head().copy());
        b.setItemSlot(EquipmentSlot.CHEST, k.chest().copy());
        b.setItemSlot(EquipmentSlot.LEGS, k.legs().copy());
        b.setItemSlot(EquipmentSlot.FEET, k.feet().copy());
        ItemStack main = k.main();
        if (main.isEmpty() && !k.items().isEmpty()) main = k.items().get(0);
        b.setItemSlot(EquipmentSlot.MAINHAND, main.copy());
        b.setItemSlot(EquipmentSlot.OFFHAND, k.off().copy());
    }

    // Saves what the player is wearing and carrying as a new kit (lost when the game closes).
    public static void save(ServerPlayer p, String name) {
        List<ItemStack> items = new ArrayList<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) items.add(s.copy());
        }
        Item icon = items.isEmpty() ? Items.CHEST : items.get(0).getItem();
        KITS.put(name.toLowerCase(Locale.ROOT),
                new Kit(name, icon,
                        p.getItemBySlot(EquipmentSlot.HEAD).copy(),
                        p.getItemBySlot(EquipmentSlot.CHEST).copy(),
                        p.getItemBySlot(EquipmentSlot.LEGS).copy(),
                        p.getItemBySlot(EquipmentSlot.FEET).copy(),
                        ItemStack.EMPTY,
                        p.getOffhandItem().copy(),
                        items));
    }

    public static void open(ServerPlayer p) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> KitMenu.create(id, inv),
                Component.literal("Kits")));
    }

    // A chest-style menu where each item is a button for one kit.
    public static class KitMenu extends ChestMenu {
        private final List<String> keys;

        private KitMenu(int id, Inventory inv, Container box, List<String> keys) {
            super(MenuType.GENERIC_9x3, id, inv, box, 3);
            this.keys = keys;
        }

        public static KitMenu create(int id, Inventory inv) {
            SimpleContainer box = new SimpleContainer(27);
            List<String> keys = new ArrayList<>(KITS.keySet());
            for (int i = 0; i < keys.size() && i < 27; i++) {
                Kit k = KITS.get(keys.get(i));
                ItemStack icon = new ItemStack(k.icon());
                icon.set(DataComponents.CUSTOM_NAME,
                        Component.literal(k.name()).withStyle(ChatFormatting.GOLD));
                box.setItem(i, icon);
            }
            return new KitMenu(id, inv, box, keys);
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (slotId >= 0 && slotId < 27) {
                if (slotId < keys.size() && player instanceof ServerPlayer sp) {
                    Kit k = KITS.get(keys.get(slotId));
                    if (k != null) {
                        give(sp, k);
                        sp.sendSystemMessage(Component.literal("Got kit " + k.name()));
                    }
                }
                return;
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }
}

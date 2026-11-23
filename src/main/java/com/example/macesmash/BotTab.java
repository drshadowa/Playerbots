package com.example.macesmash;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class BotTab {

    // Builds a "player joined the tab list" packet for a bot. Returns null if it fails.
    public static ClientboundPlayerInfoUpdatePacket addPacket(UUID id, String name) {
        try {
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);

            GameProfile profile = new GameProfile(id, name);
            ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                    id, profile, true, 0, GameType.SURVIVAL, Component.literal(name), true, 0, null);

            ClientboundPlayerInfoUpdatePacket pkt = new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class),
                    new ArrayList<ServerPlayer>());

            for (Field f : ClientboundPlayerInfoUpdatePacket.class.getDeclaredFields()) {
                if (f.getType() == EnumSet.class) {
                    f.setAccessible(true);
                    f.set(pkt, actions);
                } else if (f.getType() == List.class) {
                    f.setAccessible(true);
                    f.set(pkt, List.of(entry));
                }
            }
            return pkt;
        } catch (Exception e) {
            System.out.println("[PlayerBots] Could not build tab list packet: " + e);
            return null;
        }
    }

    public static void send(MinecraftServer server, Packet<?> pkt) {
        if (pkt == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(pkt);
        }
    }

    public static void announceJoin(MinecraftServer server, UUID id, String name) {
        send(server, addPacket(id, name));
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("multiplayer.player.joined", Component.literal(name))
                        .withStyle(ChatFormatting.YELLOW), false);
    }

    public static void announceLeave(MinecraftServer server, UUID id, String name) {
        send(server, new ClientboundPlayerInfoRemovePacket(List.of(id)));
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("multiplayer.player.left", Component.literal(name))
                        .withStyle(ChatFormatting.YELLOW), false);
    }
}

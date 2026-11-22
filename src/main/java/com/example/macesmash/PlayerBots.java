package com.example.macesmash;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

public class PlayerBots implements ModInitializer {
    static final Random RANDOM = new Random();
    static final List<Bot> BOTS = new ArrayList<>();
    static final List<Pending> PENDING = new ArrayList<>();
    static MinecraftServer SERVER;
    static long tick = 0;

    static final String[] OK = {"sure", "ok", "yep, on it", "alright", "sounds good"};
    static final String[] NO = {"nah", "nope", "don't feel like it", "ask me later", "lol no"};
    static final String[] HURT = {"ow!", "hey!", "what was that for?!", "ok you asked for it", "bro??"};
    static final String[] HI = {"hey", "yo", "hi!", "sup"};
    static final String[] CHAT = {"hm?", "lol", "idk", "what's up", "ok", "haha"};

    static class Bot {
        final String name;
        final LivingEntity body;
        String mode = "idle";
        String mood = "normal";
        UUID owner;
        LivingEntity target;
        LivingEntity threat;
        Vec3 guardPos;
        double vy = 0;
        int atkCd = 0;
        int fleeTicks = 0;
        int strafeDir = 1;
        int strafeT = 0;

        Bot(String name, LivingEntity body) {
            this.name = name;
            this.body = body;
        }
    }

    record Pending(long due, Runnable run) {}

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("bot")
                .then(Commands.literal("spawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            if (find(name) != null) {
                                ctx.getSource().sendFailure(Component.literal("A bot named " + name + " already exists."));
                                return 0;
                            }
                            ServerLevel level = ctx.getSource().getLevel();
                            LivingEntity body = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
                            if (body == null) return 0;
                            body.setPos(p.getX(), p.getY(), p.getZ());
                            body.setCustomName(Component.literal(name));
                            body.setCustomNameVisible(true);
                            body.setInvulnerable(false);
                            body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                            level.addFreshEntity(body);
                            Bot bot = new Bot(name, body);
                            bot.owner = p.getUUID();
                            BOTS.add(bot);
                            ctx.getSource().sendSuccess(() -> Component.literal("Spawned " + name), false);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            Bot bot = find(StringArgumentType.getString(ctx, "name"));
                            if (bot == null) {
                                ctx.getSource().sendFailure(Component.literal("No such bot."));
                                return 0;
                            }
                            bot.body.discard();
                            BOTS.remove(bot);
                            return 1;
                        })))
                .then(Commands.literal("mood")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("mood", StringArgumentType.word())
                            .executes(ctx -> {
                                Bot bot = find(StringArgumentType.getString(ctx, "name"));
                                String mood = StringArgumentType.getString(ctx, "mood").toLowerCase(Locale.ROOT);
                                if (bot == null || !List.of("obedient", "normal", "stubborn", "rebel").contains(mood)) {
                                    ctx.getSource().sendFailure(Component.literal("Use: /bot mood <name> obedient|normal|stubborn|rebel"));
                                    return 0;
                                }
                                bot.mood = mood;
                                ctx.getSource().sendSuccess(() -> Component.literal(bot.name + " is now " + mood), false);
                                return 1;
                            }))))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        for (Bot b : BOTS) {
                            String line = b.name + " - " + b.mode + ", " + b.m

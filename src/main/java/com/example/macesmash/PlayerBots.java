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
                            String line = b.name + " - " + b.mode + ", " + b.mood;
                            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                        }
                        return BOTS.size();
                    }))));

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
            onChat(sender, message.signedContent()));

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            for (Bot bot : new ArrayList<>(BOTS)) {
                if (bot.body == entity) onHurt(bot, source);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SERVER = server;
            tick++;
            PENDING.removeIf(p -> {
                if (p.due() <= tick) {
                    p.run().run();
                    return true;
                }
                return false;
            });
            for (Bot bot : new ArrayList<>(BOTS)) tickBot(server, bot);
        });
    }

    static Bot find(String name) {
        for (Bot b : BOTS) if (b.name.equalsIgnoreCase(name)) return b;
        return null;
    }

    static double chance(String mood) {
        return switch (mood) {
            case "obedient" -> 1.0;
            case "stubborn" -> 0.35;
            case "rebel" -> 0.05;
            default -> 0.75;
        };
    }

    static String pick(String[] arr) {
        return arr[RANDOM.nextInt(arr.length)];
    }

    static boolean has(String text, String... words) {
        return Pattern.compile("\\b(" + String.join("|", words) + ")\\b").matcher(text).find();
    }

    static void say(Bot bot, String text, int delay) {
        PENDING.add(new Pending(tick + delay, () -> {
            if (SERVER != null) {
                SERVER.getPlayerList().broadcastSystemMessage(
                        Component.literal("<" + bot.name + "> " + text), false);
            }
        }));
    }
        static void onChat(ServerPlayer sender, String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        for (Bot bot : new ArrayList<>(BOTS)) {
            if (!text.contains(bot.name.toLowerCase(Locale.ROOT))) continue;
            boolean obeys = RANDOM.nextDouble() < chance(bot.mood);
            int delay = 15 + RANDOM.nextInt(30);

            if (has(text, "follow", "come")) {
                if (obeys) {
                    bot.mode = "follow";
                    bot.owner = sender.getUUID();
                    bot.target = null;
                    say(bot, pick(OK), delay);
                } else say(bot, pick(NO), delay);
            } else if (has(text, "stay", "stop", "wait")) {
                if (obeys) {
                    bot.mode = "idle";
                    bot.target = null;
                    say(bot, pick(OK), delay);
                } else say(bot, pick(NO), delay);
            } else if (has(text, "guard")) {
                if (obeys) {
                    bot.mode = "guard";
                    bot.guardPos = sender.position();
                    bot.target = null;
                    say(bot, pick(OK), delay);
                } else say(bot, pick(NO), delay);
            } else if (has(text, "fight", "duel", "attack")) {
                if (obeys) {
                    bot.mode = "fight";
                    bot.target = sender;
                    say(bot, "ok let's go", delay);
                } else say(bot, pick(NO), delay);
            } else if (has(text, "hi", "hello", "hey", "yo")) {
                say(bot, pick(HI), delay);
            } else {
                say(bot, pick(CHAT), delay);
            }
        }
    }

    static void onHurt(Bot bot, DamageSource source) {
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker == bot.body) return;
        bot.threat = attacker;
        if (bot.body.getHealth() < 6.0f && bot.fleeTicks == 0 && RANDOM.nextDouble() < 0.6) {
            bot.fleeTicks = 100;
            say(bot, "ok ok I give up!", 5);
        } else if (!"fight".equals(bot.mode)) {
            bot.mode = "fight";
            bot.target = attacker;
            say(bot, pick(HURT), 8);
        }
    }

    static void face(LivingEntity b, double dx, double dz) {
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        b.setYRot(yaw);
        b.setYHeadRot(yaw);
        b.setYBodyRot(yaw);
    }

    static double[] toward(LivingEntity b, double tx, double tz, double speed) {
        double dx = tx - b.getX();
        double dz = tz - b.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) return new double[]{0, 0};
        face(b, dx, dz);
        return new double[]{dx / dist * speed, dz / dist * speed};
    }
        static double[] combat(Bot bot, ServerLevel level, LivingEntity foe) {
        LivingEntity b = bot.body;
        double dx = foe.getX() - b.getX();
        double dz = foe.getZ() - b.getZ();
        double dist = Math.max(Math.sqrt(dx * dx + dz * dz), 0.001);
        face(b, dx, dz);
        if (dist > 2.7) {
            return new double[]{dx / dist * 0.2, dz / dist * 0.2};
        }
        if (--bot.strafeT <= 0) {
            bot.strafeDir = RANDOM.nextBoolean() ? 1 : -1;
            bot.strafeT = 10 + RANDOM.nextInt(20);
        }
        if (bot.atkCd <= 0) {
            bot.atkCd = 14 + RANDOM.nextInt(12);
            b.swing(InteractionHand.MAIN_HAND);
            if (RANDOM.nextFloat() < 0.55f) {
                foe.hurtServer(level, level.damageSources().mobAttack(b), 4.0f);
                foe.knockback(0.4, b.getX() - foe.getX(), b.getZ() - foe.getZ());
            }
        }
        return new double[]{-dz / dist * 0.12 * bot.strafeDir, dx / dist * 0.12 * bot.strafeDir};
    }

    static void tickBot(MinecraftServer server, Bot bot) {
        LivingEntity b = bot.body;
        if (b.isRemoved() || !b.isAlive()) {
            BOTS.remove(bot);
            return;
        }
        if (!(b.level() instanceof ServerLevel level)) return;
        if (bot.atkCd > 0) bot.atkCd--;

        double[] mv = {0, 0};

        if (bot.fleeTicks > 0) {
            bot.fleeTicks--;
            if (bot.threat != null && bot.threat.isAlive()) {
                mv = toward(b, b.getX() * 2 - bot.threat.getX(), b.getZ() * 2 - bot.threat.getZ(), 0.25);
            }
            if (bot.fleeTicks == 0) {
                bot.mode = "idle";
                bot.target = null;
            }
        } else {
            LivingEntity foe = null;
            if ("fight".equals(bot.mode)) {
                foe = bot.target;
            } else if ("guard".equals(bot.mode)) {
                double best = Double.MAX_VALUE;
                for (Monster m : level.getEntitiesOfClass(Monster.class, b.getBoundingBox().inflate(8.0))) {
                    double d = b.distanceToSqr(m);
                    if (d < best) {
                        best = d;
                        foe = m;
                    }
                }
            }
            if (foe != null) {
                boolean bad = !foe.isAlive() || foe.isRemoved() || b.distanceTo(foe) > 30.0f
                        || (foe instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator()));
                if (bad) {
                    foe = null;
                    if ("fight".equals(bot.mode)) {
                        bot.mode = "idle";
                        bot.target = null;
                        say(bot, "gg", 10);
                    }
                }
            }
            if (foe != null) {
                mv = combat(bot, level, foe);
            } else if ("follow".equals(bot.mode) && bot.owner != null) {
                ServerPlayer owner = server.getPlayerList().getPlayer(bot.owner);
                if (owner != null && b.distanceTo(owner) > 4.0f) {
                    mv = toward(b, owner.getX(), owner.getZ(), 0.21);
                }
            } else if ("guard".equals(bot.mode) && bot.guardPos != null) {
                double gx = bot.guardPos.x - b.getX();
                double gz = bot.guardPos.z - b.getZ();
                if (gx * gx + gz * gz > 25.0) {
                    mv = toward(b, bot.guardPos.x, bot.guardPos.z, 0.2);
                }
            }
        }

        boolean moving = mv[0] != 0 || mv[1] != 0;
        if (b.onGround() && b.horizontalCollision && moving) {
            bot.vy = 0.42;
        } else {
            bot.vy = (bot.vy - 0.08) * 0.98;
        }
        b.move(MoverType.SELF, new Vec3(mv[0], bot.vy, mv[1]));
        if (b.onGround()) bot.vy = 0;
    }
}

                    

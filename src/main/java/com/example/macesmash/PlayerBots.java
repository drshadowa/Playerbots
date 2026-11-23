package com.example.macesmash;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
        boolean fightBack = false;
        UUID owner;
        LivingEntity target;
        LivingEntity threat;
        Vec3 guardPos;
        double vy = 0;
        double lastX = 0;
        double lastZ = 0;
        int atkCd = 0;
        int fleeTicks = 0;
        int strafeDir = 1;
        int strafeT = 0;
        int stuckT = 0;
        int detourT = 0;
        int detourSign = 1;
        long lastHurtSay = 0;

        Bot(String name, LivingEntity body) {
            this.name = name;
            this.body = body;
        }
    }

    record Pending(long due, Runnable run) {}

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("pvpbot")
                    .then(Commands.literal("spawn")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> spawn(ctx, null))
                                    .then(Commands.argument("kit", StringArgumentType.word())
                                            .suggests((c, b) -> SharedSuggestionProvider.suggest(Kits.KITS.keySet(), b))
                                            .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "kit"))))))
                    .then(Commands.literal("remove")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> remove(ctx))))
                    .then(Commands.literal("mood")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("mood", StringArgumentType.word())
                                            .executes(ctx -> mood(ctx)))))
                    .then(Commands.literal("fightback")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("mode", StringArgumentType.word())
                                            .executes(ctx -> fightback(ctx)))))
                    .then(Commands.literal("list")
                            .executes(ctx -> list(ctx))));

            dispatcher.register(Commands.literal("kits")
                    .executes(ctx -> {
                        Kits.open(ctx.getSource().getPlayerOrException());
                        return 1;
                    }));

            dispatcher.register(Commands.literal("kit")
                    .then(Commands.argument("kit", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(Kits.KITS.keySet(), b))
                            .executes(ctx -> giveKit(ctx, null))
                            .then(Commands.argument("target", StringArgumentType.word())
                                    .executes(ctx -> giveKit(ctx, StringArgumentType.getString(ctx, "target"))))));

            dispatcher.register(Commands.literal("kitsave")
                    .then(Commands.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                String n = StringArgumentType.getString(ctx, "name");
                                Kits.save(p, n);
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("Saved kit " + n + " (it resets when the game closes)"), false);
                                return 1;
                            })));
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                onChat(sender, message.signedContent()));

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            for (Bot bot : new ArrayList<>(BOTS)) {
                if (bot.body == entity) onHurt(bot, source);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            for (Bot bot : new ArrayList<>(BOTS)) {
                ClientboundPlayerInfoUpdatePacket pkt = BotTab.addPacket(bot.body.getUUID(), bot.name);
                if (pkt != null) handler.send(pkt);
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

    // ---------- commands ----------

    static int spawn(CommandContext<CommandSourceStack> ctx, String kitName) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer p = src.getPlayerOrException();
        if (name.length() > 16) {
            src.sendFailure(Component.literal("Name must be 16 letters or less."));
            return 0;
        }
        if (find(name) != null) {
            src.sendFailure(Component.literal("A bot named " + name + " already exists."));
            return 0;
        }
        Kits.Kit kit = null;
        if (kitName != null) {
            kit = Kits.KITS.get(kitName.toLowerCase(Locale.ROOT));
            if (kit == null) {
                src.sendFailure(Component.literal("No kit named " + kitName));
                return 0;
            }
        }
        ServerLevel level = src.getLevel();
        LivingEntity body = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
        if (body == null) return 0;
        body.setPos(p.getX(), p.getY(), p.getZ());
        body.setCustomName(Component.literal(name));
        body.setCustomNameVisible(true);
        body.setInvulnerable(false);
        if (kit != null) Kits.giveBot(body, kit);
        level.addFreshEntity(body);
        Bot bot = new Bot(name, body);
        bot.owner = p.getUUID();
        bot.lastX = body.getX();
        bot.lastZ = body.getZ();
        BOTS.add(bot);
        BotTab.announceJoin(src.getServer(), body.getUUID(), name);
        return 1;
    }

    static int remove(CommandContext<CommandSourceStack> ctx) {
        Bot bot = find(StringArgumentType.getString(ctx, "name"));
        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No such bot."));
            return 0;
        }
        removeBot(ctx.getSource().getServer(), bot, true);
        return 1;
    }

    static int mood(CommandContext<CommandSourceStack> ctx) {
        Bot bot = find(StringArgumentType.getString(ctx, "name"));
        String mood = StringArgumentType.getString(ctx, "mood").toLowerCase(Locale.ROOT);
        if (bot == null || !List.of("obedient", "normal", "stubborn", "rebel").contains(mood)) {
            ctx.getSource().sendFailure(Component.literal("Use: /pvpbot mood <name> obedient|normal|stubborn|rebel"));
            return 0;
        }
        bot.mood = mood;
        ctx.getSource().sendSuccess(() -> Component.literal(bot.name + " is now " + mood), false);
        return 1;
    }

    static int fightback(CommandContext<CommandSourceStack> ctx) {
        Bot bot = find(StringArgumentType.getString(ctx, "name"));
        String m = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
        if (bot == null || !(m.equals("on") || m.equals("off"))) {
            ctx.getSource().sendFailure(Component.literal("Use: /pvpbot fightback <name> on|off"));
            return 0;
        }
        bot.fightBack = m.equals("on");
        ctx.getSource().sendSuccess(
                () -> Component.literal(bot.name + (bot.fightBack ? " will fight back" : " will not fight back")), false);
        return 1;
    }

    static int list(CommandContext<CommandSourceStack> ctx) {
        for (Bot b : BOTS) {
            String line = b.name + " - " + b.mode + ", " + b.mood + (b.fightBack ? ", fights back" : "");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return BOTS.size();
    }

    static int giveKit(CommandContext<CommandSourceStack> ctx, String target) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        String kitName = StringArgumentType.getString(ctx, "kit").toLowerCase(Locale.ROOT);
        Kits.Kit kit = Kits.KITS.get(kitName);
        if (kit == null) {
            src.sendFailure(Component.literal("No kit named " + kitName + ". Try /kits"));
            return 0;
        }
        if (target == null) {
            Kits.give(src.getPlayerOrException(), kit);
            src.sendSuccess(() -> Component.literal("Gave you kit " + kit.name()), false);
            return 1;
        }
        Bot bot = find(target);
        if (bot != null) {
            Kits.giveBot(bot.body, kit);
            src.sendSuccess(() -> Component.literal("Gave kit " + kit.name() + " to " + bot.name), false);
            return 1;
        }
        ServerPlayer sp = src.getServer().getPlayerList().getPlayerByName(target);
        if (sp == null) {
            src.sendFailure(Component.literal("No player or bot named " + target));
            return 0;
        }
        Kits.give(sp, kit);
        src.sendSuccess(() -> Component.literal("Gave kit " + kit.name() + " to " + sp.getName().getString()), false);
        return 1;
    }

    // ---------- helpers ----------

    static Bot find(String name) {
        for (Bot b : BOTS) if (b.name.equalsIgnoreCase(name)) return b;
        return null;
    }

    static void removeBot(MinecraftServer server, Bot bot, boolean discard) {
        if (discard) bot.body.discard();
        BOTS.remove(bot);
        BotTab.announceLeave(server, bot.body.getUUID(), bot.name);
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

    static float weaponDamage(ItemStack s) {
        if (s.is(Items.NETHERITE_SWORD)) return 8.0f;
        if (s.is(Items.DIAMOND_SWORD)) return 7.0f;
        if (s.is(Items.MACE)) return 7.0f;
        if (s.is(Items.IRON_SWORD)) return 6.0f;
        if (s.is(Items.STONE_SWORD)) return 5.0f;
        if (s.is(Items.WOODEN_SWORD) || s.is(Items.GOLDEN_SWORD)) return 4.0f;
        return 1.0f;
    }

    // ---------- chat and reactions ----------

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
        } else if (bot.fightBack && !"fight".equals(bot.mode)) {
            bot.mode = "fight";
            bot.target = attacker;
            say(bot, pick(HURT), 8);
        } else if (!bot.fightBack && tick - bot.lastHurtSay > 60) {
            bot.lastHurtSay = tick;
            say(bot, pick(HURT), 8);
        }
    }

    // ---------- movement ----------

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

    static boolean solid(ServerLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    static boolean hazard(ServerLevel level, double x, double y, double z) {
        BlockState s = level.getBlockState(BlockPos.containing(x, y, z));
        return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
                || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.CAMPFIRE);
    }

    // Looks one step ahead. Returns null if blocked, {0} to walk, {1} to jump.
    static double[] probe(ServerLevel level, LivingEntity b, double dirx, double dirz, boolean swimming) {
        double ax = b.getX() + dirx * 0.75;
        double az = b.getZ() + dirz * 0.75;
        double y = b.getY();
        if (hazard(level, ax, y + 0.1, az) || hazard(level, ax, y - 0.9, az)) return null;
        boolean feet = solid(level, ax, y + 0.1, az);
        boolean head = solid(level, ax, y + 1.1, az);
        if (!feet && !head) {
            if (swimming) return new double[]{0};
            for (int d = 1; d <= 3; d++) {
                if (solid(level, ax, y + 0.1 - d, az)) return new double[]{0};
            }
            return null;
        }
        boolean room1 = !solid(level, ax, y + 1.1, az);
        boolean room2 = !solid(level, ax, y + 2.1, az);
        boolean above = !solid(level, b.getX(), y + 2.1, b.getZ());
        if (feet && room1 && room2 && above) return new double[]{1};
        return null;
    }

    // Picks the best direction near the wanted one and says whether to jump.
    static double[] steer(ServerLevel level, LivingEntity b, double dx, double dz, boolean swimming) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.0001) return new double[]{0, 0, 0};
        double ux = dx / len;
        double uz = dz / len;
        double[] angles = {0, 35, -35, 70, -70, 110, -110};
        for (double a : angles) {
            double r = Math.toRadians(a);
            double cx = ux * Math.cos(r) - uz * Math.sin(r);
            double cz = ux * Math.sin(r) + uz * Math.cos(r);
            double[] p = probe(level, b, cx, cz, swimming);
            if (p != null) return new double[]{cx * len, cz * len, p[0]};
        }
        return new double[]{0, 0, 0};
    }

    static void move(ServerLevel level, Bot bot, double dx, double dz) {
        LivingEntity b = bot.body;
        boolean swimming = b.isInWater();
        boolean wants = dx != 0 || dz != 0;

        double moved = Math.abs(b.getX() - bot.lastX) + Math.abs(b.getZ() - bot.lastZ);
        bot.lastX = b.getX();
        bot.lastZ = b.getZ();
        if (wants && moved < 0.02) {
            bot.stuckT++;
            if (bot.stuckT % 40 == 0) {
                bot.detourT = 20;
                bot.detourSign = RANDOM.nextBoolean() ? 1 : -1;
            }
        } else {
            bot.stuckT = 0;
        }

        double mx = dx;

package io.github.maxlananas.hauteur.command;

import io.github.maxlananas.hauteur.HauteurCore;
import io.github.maxlananas.hauteur.HauteurExtension;
import io.github.maxlananas.hauteur.pipeline.HauteurHandler;
import io.github.maxlananas.hauteur.session.HauteurSession;
import io.github.maxlananas.hauteur.window.WindowRules;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.session.GeyserSession;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /hauteur} (aliases {@code /sw}, {@code /bho}) - per-player state inspection and development
 * diagnostics. Everything it prints comes straight from the live session state used by the packet
 * pipeline, so there is no second source of truth that could drift from what the player actually
 * experiences.
 */
public final class HauteurCommand {
    private HauteurCommand() {
    }

    public static Command build(HauteurExtension extension) {
        return Command.<GeyserSession>builder(extension)
            .source(GeyserSession.class)
            .name("hauteur")
            .description("Hauteur state and diagnostics")
            // "skywindow", "sw" and "bho" are the names of the 1.0.x line; kept as aliases so existing muscle memory works.
            .aliases(List.of("skywindow", "sw", "bho"))
            .playerOnly(true)
            .bedrockOnly(true)
            .executor((session, command, args) -> execute(extension.core(), session, args))
            .build();
    }

    private static void execute(HauteurCore core, GeyserSession session, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        HauteurSession state = core.state(session);
        switch (sub) {
            case "info", "inspect" -> inspect(session, state);
            case "watch" -> watch(session, state, args);
            case "recent" -> recent(session, state);
            case "doctor" -> doctor(core, session, state);
            case "stats" -> stats(session, state, args);
            case "window" -> window(core, session, state, args);
            default -> send(session,
                "§7Usage: §f/hauteur info §7| §f/hauteur watch <on|off> §7| §f/hauteur recent",
                "§7       §f/hauteur doctor §7| §f/hauteur stats [reset] §7| §f/hauteur window <realY>");
        }
    }

    private static void inspect(GeyserSession session, HauteurSession state) {
        if (state == null) {
            send(session, "§cHauteur is not tracking this session (extension disabled, or it joined before load).");
            return;
        }
        HauteurSession.WindowConfig w = state.window();
        send(session,
            "§bHauteur §7offset: §f" + state.offset() + (state.offset() == 0 ? " blocks §7(real space, no window)" : " blocks"),
            "§bHauteur §7attached: §f" + state.attached() + " §7| switching now: §f" + state.frozen()
                + (state.degraded() ? " §c| DEGRADED: pipeline never attached" : ""),
            "§bHauteur §7client Y range: §f" + (w == null ? "?" : w.clientMinY() + ".." + (w.clientMinY() + w.clientHeight())),
            "§bHauteur §7real Y range: §f" + (w == null ? "?" : w.javaMinY() + ".." + w.javaMaxY()),
            "§bHauteur §7your real Y: §f" + currentRealY(session, state),
            "§bHauteur §7cached chunks: §f" + state.chunkCache.size()
                + (w != null && w.needed()
                    ? " §7| window height " + w.clientHeight() + " blocks, re-home ~1 per "
                      + Math.max(1, w.clientHeight() / 2 - 64) + " climbed blocks"
                    : ""),
            "§7You are always fully playable and buildable here - only the §fy§7 label differs from the server's.");
    }

    /**
     * Builder tooling: re-home the window so {@code realY} sits mid-window right now, without waiting
     * for the margin logic. Same machinery, same guarantees (chunk replay + snap); it simply supplies
     * the anchor Y manually.
     */
    private static void window(HauteurCore core, GeyserSession session, HauteurSession state, String[] args) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        if (args.length < 2) {
            send(session, "§7Usage: §f/hauteur window <realY> §7(e.g. §f/hauteur window " + (state.window() != null ? state.window().javaMaxY() - 400 : 1500) + "§7)");
            return;
        }
        double realY;
        try {
            realY = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            send(session, "§cNot a number: " + args[1]);
            return;
        }
        HauteurSession.WindowConfig w = state.window();
        if (w == null) {
            send(session, "§cDimension bounds not read yet; rejoin to refresh.");
            return;
        }
        if (!w.needed()) {
            send(session, "§7Windowing is not needed for this world; there is nothing to move.");
            return;
        }
        // NaN and infinities are not positions; out-of-dimension values would snap the client outside the world.
        if (!Double.isFinite(realY) || realY < w.javaMinY() || realY >= w.javaMaxY()) {
            send(session, "§cReal Y must be a number from " + w.javaMinY() + " to " + (w.javaMaxY() - 1) + ".");
            return;
        }
        if (!core.forceWindow(session, realY)) {
            send(session, "§cHauteur is not attached to this session; nothing to move.");
            return;
        }
        send(session, "§aRe-homing the height window around real Y " + (int) realY + "§7... (offset will change; you will snap a little)");
    }

    private static void watch(GeyserSession session, HauteurSession state, String[] args) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        String mode = args.length < 2 ? "on" : args[1].toLowerCase(Locale.ROOT);
        if (!mode.equals("on") && !mode.equals("off")) {
            send(session, "§7Usage: §f/hauteur watch <on|off>");
            return;
        }
        boolean on = mode.equals("on");
        state.setWatch(on);
        send(session, on
            ? "§aWatch on: §7every translated packet and switch is recorded; view with §f/hauteur recent§7."
            : "§aWatch off.");
    }

    private static void recent(GeyserSession session, HauteurSession state) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        List<String> lines = state.watchSnapshot();
        if (lines.isEmpty()) {
            send(session, "§7Nothing recorded yet (enable with §f/hauteur watch on§7).");
            return;
        }
        int from = Math.max(0, lines.size() - 30);
        send(session, "§bHauteur recent §7(last " + (lines.size() - from) + "):");
        for (int i = from; i < lines.size(); i++) {
            send(session, "§7" + lines.get(i));
        }
    }

    /** Preflight: every invariant that must hold for windowed play to be correct at altitude. */
    private static void doctor(HauteurCore core, GeyserSession session, HauteurSession state) {
        send(session, "§bHauteur doctor §7(" + HauteurCore.TESTED_AGAINST + ")");
        send(session, ok(core.config().enabled) + " §7config enabled: §f" + core.config().enabled);
        send(session, ok(core.worldManagerShifted())
            + " §7world manager wrapped (direct collision/container reads follow the window): §f"
            + core.worldManagerShifted());
        if (state == null) {
            send(session, "§c✗ no session state (joined before the extension loaded? rejoin)");
            return;
        }
        var quarantined = state.quarantine();
        send(session, ok(quarantined.isEmpty())
            + " §7packet-type quarantines (this session): §f" + (quarantined.isEmpty() ? "none"
                : quarantined.size() + " -> " + String.join(", ", quarantined.keySet())));
        for (Map.Entry<String, String> e : quarantined.entrySet()) {
            send(session, "§c✗ §7" + e.getKey() + ": §f" + e.getValue());
        }
        boolean pipelineLive = state.channel() != null && state.channel().isActive()
            && state.channel().pipeline().get(HauteurHandler.HANDLER_NAME) != null;
        send(session, ok(state.attached() && pipelineLive)
            + " §7pipeline handler: §f" + (pipelineLive ? "attached"
                : state.attached() ? "flagged attached but not in pipeline" : "not attached (degraded)"));
        HauteurSession.WindowConfig w = state.window();
        if (w == null) {
            send(session, "§c✗ dimension bounds not read yet; rejoin to refresh");
            return;
        }
        send(session, "§7client window: §f" + w.clientMinY() + ".." + (w.clientMinY() + w.clientHeight())
            + " §7(height " + w.clientHeight() + ")");
        send(session, "§7real dimension: §f" + w.javaMinY() + ".." + w.javaMaxY()
            + " §7(" + ((w.javaMaxY() - w.javaMinY()) / 16) + " sections)");
        if (!w.needed()) {
            send(session, "§a✔ §7every real coordinate already fits the client range; Hauteur stays fully passive");
        } else {
            send(session, "§7windowing: §aengaged §7(world top " + (w.javaMaxY() - 1) + " > client top "
                + (w.clientMinY() + w.clientHeight() - 1) + ")");
            int top = WindowRules.reachableBlockTop(w.javaMaxY(), w.clientMinY(), w.clientHeight(), w.maxOffset());
            send(session, "§7highest placeable real Y with current cap: §f" + top);
            int autoCap = WindowRules.maxUsefulOffset(w.javaMaxY(), w.clientMinY(), w.clientHeight());
            if (w.maxOffset() < autoCap) {
                send(session, "§e! §7max-offset-blocks caps you below the automatic value: real Y "
                    + (top + 1) + ".." + (w.javaMaxY() - 1) + " remain unreachable. Set max-offset-blocks=0 for full reach.");
            }
            if (w.javaMinY() < w.clientMinY()) {
                send(session, "§e! §7the world's floor is below the client window floor; below real Y "
                    + w.clientMinY() + " content is clipped by the client itself (vanilla Geyser behaviour).");
            }
        }
        send(session, ok(state.offset() % 16 == 0) + " §7invariants: offset §f" + state.offset() + " (section-aligned: " + (state.offset() % 16 == 0) + ")"
            + " §7(world gen §f" + state.worldGeneration() + "§7, backoff §f" + state.switchBackoff() + "§7)");
        send(session, "§7anomaly-normalized chunk payloads: §f"
            + state.sinceReset(HauteurSession.Stat.CHUNK_ANOMALIES)
            + " §7(resets are yours, not the server's)");
    }

    private static void stats(GeyserSession session, HauteurSession state, String[] args) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            state.resetStats();
            send(session, "§aStats baseline reset for this session.");
            return;
        }
        long freezes = state.sinceReset(HauteurSession.Stat.FREEZE_COUNT);
        long freezeNanos = state.sinceReset(HauteurSession.Stat.FREEZE_NANOS);
        String freezeAvg = freezes > 0
            ? (freezeNanos / freezes) / 1_000L + " µs avg"
            : "no completed switch yet";
        send(session,
            "§bHauteur stats §7(this player, since " + (state.statsReset() ? "last reset" : "join") + "):",
            "§7inbound packets translated: §f" + state.sinceReset(HauteurSession.Stat.IN_TRANSLATED),
            "§7outbound packets translated: §f" + state.sinceReset(HauteurSession.Stat.OUT_TRANSLATED),
            "§7chunks windowed on arrival: §f" + state.sinceReset(HauteurSession.Stat.CHUNK_WINDOWED),
            "§7chunk replays during window switches: §f" + state.sinceReset(HauteurSession.Stat.CHUNK_REPLAYS),
            "§7window switches: §f" + state.sinceReset(HauteurSession.Stat.WINDOW_SWITCHES),
            "§7outbound packets dropped while frozen: §f" + state.sinceReset(HauteurSession.Stat.DROPPED_WHILE_FROZEN),
            "§7anomaly-normalized chunk payloads: §f" + state.sinceReset(HauteurSession.Stat.CHUNK_ANOMALIES),
            "§7actions/movement held across switches: §f" + state.sinceReset(HauteurSession.Stat.ACTIONS_HELD)
                + " §7(overflow drops: §f" + state.sinceReset(HauteurSession.Stat.HELD_OVERFLOW) + "§7)",
            "§7chunks not replayable (over cache budget): §f" + state.sinceReset(HauteurSession.Stat.CHUNK_UNCACHEABLE)
                + " §7| journal overflows (chunk evicted): §f" + state.sinceReset(HauteurSession.Stat.JOURNAL_OVERFLOWS),
            "§7packets dropped on transform failure (fail closed): §f" + state.sinceReset(HauteurSession.Stat.TRANSFORM_FAILURES),
            "§7freeze duration: §f" + freezeAvg + " §7| last: §f" + (state.lastFreezeNanos() / 1_000L) + " µs",
            "§7last frame used while frozen: §f" + state.frozenFrameOffset() + " §7| now: §f" + state.offset());
    }

    private static int currentRealY(GeyserSession session, HauteurSession state) {
        if (session.getPlayerEntity() == null) {
            return 0;
        }
        return Math.round(session.getPlayerEntity().position().getY() + state.offset());
    }

    private static String ok(boolean value) {
        return value ? "§a✔" : "§c✗";
    }

    private static void send(GeyserSession session, String... lines) {
        for (String line : lines) {
            session.sendMessage(line);
        }
    }
}

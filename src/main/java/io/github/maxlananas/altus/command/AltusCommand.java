package io.github.maxlananas.altus.command;

import io.github.maxlananas.altus.AltusCore;
import io.github.maxlananas.altus.AltusExtension;
import io.github.maxlananas.altus.session.AltusSession;
import io.github.maxlananas.altus.window.WindowRules;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.session.GeyserSession;

import java.util.List;
import java.util.Map;

/**
 * {@code /altus} (aliases {@code /sw}, {@code /bho}) - per-player state inspection and development
 * diagnostics. Everything it prints comes straight from the live session state used by the packet
 * pipeline, so there is no second source of truth that could drift from what the player actually
 * experiences.
 */
public final class AltusCommand {
    private AltusCommand() {
    }

    public static Command build(AltusExtension extension) {
        return Command.<GeyserSession>builder(extension)
            .source(GeyserSession.class)
            .name("altus")
            .description("Altus state and diagnostics")
            // "skywindow", "sw" and "bho" are the names of the 1.0.x line; kept as aliases so existing muscle memory works.
            .aliases(List.of("skywindow", "sw", "bho"))
            .playerOnly(true)
            .bedrockOnly(true)
            .executor((session, command, args) -> execute(extension.core(), session, args))
            .build();
    }

    private static void execute(AltusCore core, GeyserSession session, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        AltusSession state = core.state(session);
        switch (sub) {
            case "info", "inspect" -> inspect(session, state);
            case "watch" -> watch(session, state, args);
            case "recent" -> recent(session, state);
            case "doctor" -> doctor(core, session, state);
            case "stats" -> stats(session, state, args);
            case "window" -> window(core, session, state, args);
            default -> send(session,
                "§7Usage: §f/altus info §7| §f/altus watch <on|off> §7| §f/altus recent",
                "§7       §f/altus doctor §7| §f/altus stats [reset] §7| §f/altus window <realY>");
        }
    }

    private static void inspect(GeyserSession session, AltusSession state) {
        if (state == null) {
            send(session, "§cAltus is not tracking this session (extension disabled, or it joined before load).");
            return;
        }
        AltusSession.WindowConfig w = state.window;
        send(session,
            "§bAltus §7offset: §f" + state.offset + (state.offset == 0 ? " blocks §7(real space, no window)" : " blocks"),
            "§bAltus §7attached: §f" + state.attached + " §7| switching now: §f" + state.frozen
                + (state.degraded ? " §c| DEGRADED: pipeline never attached" : ""),
            "§bAltus §7client Y range: §f" + (w == null ? "?" : w.clientMinY() + ".." + (w.clientMinY() + w.clientHeight())),
            "§bAltus §7real Y range: §f" + (w == null ? "?" : w.javaMinY() + ".." + w.javaMaxY()),
            "§bAltus §7your real Y: §f" + currentRealY(session, state),
            "§bAltus §7cached chunks: §f" + state.chunkCache.size()
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
    private static void window(AltusCore core, GeyserSession session, AltusSession state, String[] args) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        if (args.length < 2) {
            send(session, "§7Usage: §f/altus window <realY> §7(e.g. §f/altus window " + (state.window != null ? state.window.javaMaxY() - 400 : 1500) + "§7)");
            return;
        }
        double realY;
        try {
            realY = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            send(session, "§cNot a number: " + args[1]);
            return;
        }
        if (!core.forceWindow(session, realY)) {
            send(session, "§cAltus is not attached to this session; nothing to move.");
            return;
        }
        send(session, "§aRe-homing the height window around real Y " + (int) realY + "§7... (offset will change; you will snap a little)");
    }

    private static void watch(GeyserSession session, AltusSession state, String[] args) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        boolean on = args.length < 2 || !args[1].equalsIgnoreCase("off");
        state.watch = on;
        send(session, on
            ? "§aWatch on: §7every translated packet and switch is recorded; view with §f/altus recent§7."
            : "§aWatch off.");
    }

    private static void recent(GeyserSession session, AltusSession state) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        List<String> lines = state.watchSnapshot();
        if (lines.isEmpty()) {
            send(session, "§7Nothing recorded yet (enable with §f/altus watch on§7).");
            return;
        }
        int from = Math.max(0, lines.size() - 30);
        send(session, "§bAltus recent §7(last " + (lines.size() - from) + "):");
        for (int i = from; i < lines.size(); i++) {
            send(session, "§7" + lines.get(i));
        }
    }

    /** Preflight: every invariant that must hold for windowed play to be correct at altitude. */
    private static void doctor(AltusCore core, GeyserSession session, AltusSession state) {
        send(session, "§bAltus doctor §7(" + AltusCore.TESTED_AGAINST + ")");
        send(session, ok(core.config().enabled) + " §7config enabled: §f" + core.config().enabled);
        send(session, ok(core.worldManagerShifted())
            + " §7world manager wrapped (direct collision/container reads follow the window): §f"
            + core.worldManagerShifted());
        var quarantined = core.quarantinedTypes();
        send(session, ok(quarantined.isEmpty())
            + " §7packet-type quarantines: §f" + (quarantined.isEmpty() ? "none"
                : quarantined.size() + " -> " + String.join(", ", quarantined.keySet())));
        if (!quarantined.isEmpty()) {
            for (Map.Entry<String, String> e : quarantined.entrySet()) {
                send(session, "§c✗ §7" + e.getKey() + ": §f" + e.getValue());
            }
        }
        if (state == null) {
            send(session, "§c✗ no session state (joined before the extension loaded? rejoin)");
            return;
        }
        boolean pipelineLive = state.channel != null && state.channel.isActive()
            && state.channel.pipeline().get(io.github.maxlananas.altus.pipeline.AltusHandler.HANDLER_NAME) != null;
        send(session, ok(state.attached && pipelineLive)
            + " §7pipeline handler: §f" + (pipelineLive ? "attached"
                : state.attached ? "flagged attached but not in pipeline" : "not attached (degraded)"));
        AltusSession.WindowConfig w = state.window;
        if (w == null) {
            send(session, "§c✗ dimension bounds not read yet; rejoin to refresh");
            return;
        }
        send(session, "§7client window: §f" + w.clientMinY() + ".." + (w.clientMinY() + w.clientHeight())
            + " §7(height " + w.clientHeight() + ")");
        send(session, "§7real dimension: §f" + w.javaMinY() + ".." + w.javaMaxY()
            + " §7(" + ((w.javaMaxY() - w.javaMinY()) / 16) + " sections)");
        if (!w.needed()) {
            send(session, "§a✔ §7every real coordinate already fits the client range; Altus stays fully passive");
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
        send(session, ok(state.offset % 16 == 0) + " §7invariants: offset §f" + state.offset + " (section-aligned: " + (state.offset % 16 == 0) + ")"
            + " §7(world gen §f" + state.worldGeneration + "§7, backoff §f" + state.switchBackoff + "§7)");
        send(session, ok(true) + " §7anomaly-normalized chunk payloads: §f"
            + (state.chunkAnomalies.sum() - state.statsBaseline[6]) + " §7(resets are yours, not the server's)");
    }

    private static void stats(GeyserSession session, AltusSession state, String[] args) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            long[] b = state.statsBaseline;
            b[0] = state.inTranslated.sum();
            b[1] = state.outTranslated.sum();
            b[2] = state.chunkWindowed.sum();
            b[3] = state.chunkReplays.sum();
            b[4] = state.windowSwitches.sum();
            b[5] = state.droppedWhileFrozen.sum();
            b[6] = state.chunkAnomalies.sum();
            b[7] = state.actionsHeld.sum();
            b[8] = state.heldOverflow.sum();
            b[9] = state.freezeCount.sum();
            send(session, "§aStats baseline reset for this session.");
            return;
        }
        long[] b = state.statsBaseline;
        long freezes = state.freezeCount.sum() - b[9];
        String freezeAvg = freezes > 0
            ? (state.freezeTotalNanos.sum() / freezes) / 1_000L + " µs avg"
            : "no completed switch yet";
        send(session,
            "§bAltus stats §7(this player, since " + (b[4] == 0 ? "join" : "last reset") + "):",
            "§7inbound packets translated: §f" + (state.inTranslated.sum() - b[0]),
            "§7outbound packets translated: §f" + (state.outTranslated.sum() - b[1]),
            "§7chunks windowed on arrival: §f" + (state.chunkWindowed.sum() - b[2]),
            "§7chunk replays during window switches: §f" + (state.chunkReplays.sum() - b[3]),
            "§7window switches: §f" + (state.windowSwitches.sum() - b[4]),
            "§7outbound packets dropped while frozen: §f" + (state.droppedWhileFrozen.sum() - b[5]),
            "§7anomaly-normalized chunk payloads: §f" + (state.chunkAnomalies.sum() - b[6]),
            "§7actions/movement held across switches: §f" + (state.actionsHeld.sum() - b[7])
                + " §7(overflow drops: §f" + (state.heldOverflow.sum() - b[8]) + "§7)",
            "§7chunks not replayable (over cache budget): §f" + state.chunkUncacheable.sum()
                + " §7| journal overflows (chunk evicted): §f" + state.journalOverflows.sum(),
            "§7packets dropped on transform failure (fail closed): §f" + state.transformFailures.sum(),
            "§7freeze duration: §f" + freezeAvg + " §7| last: §f" + (state.lastFreezeNanos / 1_000L) + " µs",
            "§7last frame used while frozen: §f" + state.frozenFrameOffset + " §7| now: §f" + state.offset);
    }

    private static int currentRealY(GeyserSession session, AltusSession state) {
        if (session.getPlayerEntity() == null) {
            return 0;
        }
        return Math.round(session.getPlayerEntity().position().getY() + state.offset);
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

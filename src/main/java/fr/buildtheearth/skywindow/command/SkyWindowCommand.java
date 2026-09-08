package fr.buildtheearth.skywindow.command;

import fr.buildtheearth.skywindow.SkyWindowCore;
import fr.buildtheearth.skywindow.SkyWindowExtension;
import fr.buildtheearth.skywindow.session.SkyWindowSession;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.session.GeyserSession;

import java.util.List;

/**
 * {@code /skywindow} (aliases {@code /sw}, {@code /bho}) - per-player state inspection and development diagnostics. Everything it prints comes
 * straight from the live session state used by the packet pipeline, so there is no second source of
 * truth that could drift from what the player actually experiences.
 */
public final class SkyWindowCommand {
    private SkyWindowCommand() {
    }

    public static Command build(SkyWindowExtension extension) {
        return Command.<GeyserSession>builder(extension)
            .source(GeyserSession.class)
            .name("skywindow")
            .description("SkyWindow state and diagnostics")
            .aliases(List.of("sw", "bho"))
            .playerOnly(true)
            .bedrockOnly(true)
            .executor((session, command, args) -> execute(extension.core(), session, args))
            .build();
    }

    private static void execute(SkyWindowCore core, GeyserSession session, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        SkyWindowSession state = core.state(session);
        switch (sub) {
            case "info", "inspect" -> inspect(session, state);
            case "watch" -> watch(session, state, args);
            case "recent" -> recent(session, state);
            case "doctor" -> doctor(core, session, state);
            case "stats" -> stats(session, state);
            default -> send(session,
                "§7Usage: §f/skywindow info §7| §f/skywindow watch <on|off> §7| §f/skywindow recent",
                "§7       §f/skywindow doctor §7| §f/skywindow stats");
        }
    }

    private static void inspect(GeyserSession session, SkyWindowSession state) {
        if (state == null) {
            send(session, "§cSkyWindow is not tracking this session (extension disabled, or it joined before load).");
            return;
        }
        SkyWindowSession.WindowConfig w = state.window;
        send(session,
            "§bSkyWindow §7offset: §f" + state.offset + (state.offset == 0 ? " blocks §7(real space, no window)" : " blocks"),
            "§bSkyWindow §7attached: §f" + state.attached + " §7| switching now: §f" + state.frozen,
            "§bSkyWindow §7client Y range: §f" + (w == null ? "?" : w.clientMinY() + ".." + (w.clientMinY() + w.clientHeight())),
            "§bSkyWindow §7real Y range: §f" + (w == null ? "?" : w.javaMinY() + ".." + w.javaMaxY()),
            "§bSkyWindow §7your real Y: §f" + currentRealY(session, state),
            "§bSkyWindow §7cached chunks: §f" + state.chunkCache.size());
    }

    private static void watch(GeyserSession session, SkyWindowSession state, String[] args) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        boolean on = args.length < 2 || !args[1].equalsIgnoreCase("off");
        state.watch = on;
        send(session, on
            ? "§aWatch on: §7every translated packet and switch is recorded; view with §f/skywindow recent§7."
            : "§aWatch off.");
    }

    private static void recent(GeyserSession session, SkyWindowSession state) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        List<String> lines = state.watchSnapshot();
        if (lines.isEmpty()) {
            send(session, "§7Nothing recorded yet (enable with §f/skywindow watch on§7).");
            return;
        }
        int from = Math.max(0, lines.size() - 30);
        send(session, "§bSkyWindow recent §7(last " + (lines.size() - from) + "):");
        for (int i = from; i < lines.size(); i++) {
            send(session, "§7" + lines.get(i));
        }
    }

    /** Preflight: the four things that must be true for windowed play to be correct at altitude. */
    private static void doctor(SkyWindowCore core, GeyserSession session, SkyWindowSession state) {
        send(session, "§bSkyWindow doctor");
        send(session, "§7config enabled: §f" + core.config().enabled);
        send(session, "§7world manager wrapped (direct collision/container reads follow the window): §f"
            + (core.worldManagerShifted() ? "§ayes" : "§cno - check the extension log after startup"));
        if (state == null) {
            send(session, "§cno session state (did you join before the extension loaded? rejoin)");
            return;
        }
        send(session, "§7pipeline handler: " + (state.attached ? "§aattached" : "§cnot attached - still retrying or failed"));
        SkyWindowSession.WindowConfig w = state.window;
        if (w == null) {
            send(session, "§7dimension bounds not read yet; rejoin to refresh");
            return;
        }
        send(session, "§7client window: §f" + w.clientMinY() + ".." + (w.clientMinY() + w.clientHeight())
            + " §7(height " + w.clientHeight() + ")");
        send(session, "§7real dimension: §f" + w.javaMinY() + ".." + w.javaMaxY()
            + " §7(" + ((w.javaMaxY() - w.javaMinY()) / 16) + " sections)");
        if (!w.needed()) {
            send(session, "§a✔ §7every real coordinate already fits the client range; SkyWindow stays fully passive");
        } else {
            send(session, "§7windowing: §aengaged §7(world top " + (w.javaMaxY() - 1) + " > client top "
                + (w.clientMinY() + w.clientHeight() - 1) + ")");
            send(session, "§7highest placeable real Y with current cap: §f"
                + fr.buildtheearth.skywindow.window.WindowRules.reachableBlockTop(
                    w.javaMaxY(), w.clientMinY(), w.clientHeight(), w.maxOffset()));
        }
        send(session, "§7anomaly-normalized chunk payloads: §f" + state.chunkAnomalies.sum());
    }

    private static void stats(GeyserSession session, SkyWindowSession state) {
        if (state == null) {
            send(session, "§cNo session state.");
            return;
        }
        send(session,
            "§bSkyWindow stats §7(this player):",
            "§7inbound packets translated: §f" + state.inTranslated.sum(),
            "§7outbound packets translated: §f" + state.outTranslated.sum(),
            "§7chunks windowed on arrival: §f" + state.chunkWindowed.sum(),
            "§7chunk replays during window switches: §f" + state.chunkReplays.sum(),
            "§7window switches: §f" + state.windowSwitches.sum(),
            "§7outbound packets dropped while frozen: §f" + state.droppedWhileFrozen.sum(),
            "§7anomaly-normalized chunk payloads: §f" + state.chunkAnomalies.sum(),
            "§7actions held across switches: §f" + state.actionsHeld.sum());
    }

    private static int currentRealY(GeyserSession session, SkyWindowSession state) {
        if (session.getPlayerEntity() == null) {
            return 0;
        }
        return (int) Math.round(session.getPlayerEntity().position().getY() + state.offset);
    }

    private static void send(GeyserSession session, String... lines) {
        for (String line : lines) {
            session.sendMessage(line);
        }
    }
}

package fr.buildtheearth.bedrockheightoffset.command;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserHook;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserSessionReflection;
import fr.buildtheearth.bedrockheightoffset.netty.BedrockPacketInterceptor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class BHOCommand implements CommandExecutor, TabCompleter {

    private static final String P = "§6[BHO] §r";

    private final BedrockHeightOffset plugin;
    private final OffsetRegistry      registry;

    public BHOCommand(BedrockHeightOffset plugin, OffsetRegistry registry) {
        this.plugin   = plugin;
        this.registry = registry;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c,
                             @NotNull String l, @NotNull String[] args) {
        if (!s.hasPermission("bho.admin")) { s.sendMessage(P + "§cPermission denied."); return true; }
        if (args.length == 0) { sendHelp(s); return true; }
        switch (args[0].toLowerCase()) {
            case "info"     -> info(s);
            case "offset"   -> offset(s, args);
            case "list"     -> list(s);
            case "reload"   -> reload(s);
            case "debug"    -> debug(s);
            case "pipeline" -> pipeline(s, args);
            case "repatch"  -> repatch(s, args);
            default         -> sendHelp(s);
        }
        return true;
    }

    private void info(CommandSender s) {
        s.sendMessage(P + "§6══ BedrockHeightOffset v3.7.0 ══");
        s.sendMessage("  §7Players: §f"       + registry.size());
        s.sendMessage("  §7Java world: §f"    + plugin.getPluginConfig().getJavaMinY()
            + " → " + plugin.getPluginConfig().getJavaMaxY());
        s.sendMessage("  §7Bedrock window: §f-64 -> 320");
        s.sendMessage("  §7Triggers: §fupper=" + plugin.getPluginConfig().getUpperTrigger()
            + " lower=" + plugin.getPluginConfig().getLowerTrigger());
        s.sendMessage("  §7Floodgate: §f"     + GeyserHook.isFloodgateAvailable());
        s.sendMessage("  §7Reflection: §f"    + GeyserSessionReflection.isReady());
        s.sendMessage("  §7Debug: §f"         + plugin.getPluginConfig().isDebug());
    }

    private void offset(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(P + "§cUsage: /bho offset <player>"); return; }
        Player t = Bukkit.getPlayer(args[1]);
        if (t == null) { s.sendMessage(P + "§cPlayer not found."); return; }
        PlayerOffsetData d = registry.get(t.getUniqueId());
        if (d == null) { s.sendMessage(P + "§eNot registered."); return; }
        s.sendMessage(P + "§6══ " + t.getName() + " ══");
        s.sendMessage("  §7Type: "        + (d.isBedrockPlayer() ? "§aBEDROCK" : "§9JAVA"));
        s.sendMessage("  §7Offset: §f"    + d.getOffset() + " blocks (" + d.offsetSections() + " sections)");
        s.sendMessage("  §7Java Y: §f"    + String.format("%.2f", d.getLastJavaY()));
        s.sendMessage("  §7Bedrock Y: §f" + String.format("%.2f", d.toBedrockY(d.getLastJavaY())));
        s.sendMessage("  §7Changes: §f"   + d.getOffsetChangeCount());
    }

    private void list(CommandSender s) {
        Collection<PlayerOffsetData> all = registry.all();
        if (all.isEmpty()) { s.sendMessage(P + "§eNone."); return; }
        s.sendMessage(P + "§6══ Players (" + all.size() + ") ══");
        for (PlayerOffsetData d : all)
            s.sendMessage(String.format("  %s §f%s §7off=%d jY=%.0f bY=%.0f",
                d.isBedrockPlayer() ? "§a[BE]" : "§9[JE]",
                d.getName(), d.getOffset(), d.getLastJavaY(), d.toBedrockY(d.getLastJavaY())));
    }

    private void reload(CommandSender s) {
        plugin.getPluginConfig().reload();
        s.sendMessage(P + "§aReloaded.");
    }

    private void debug(CommandSender s) {
        boolean cur = plugin.getPluginConfig().isDebug();
        plugin.getConfig().set("debug", !cur);
        plugin.saveConfig();
        plugin.getPluginConfig().reload();
        s.sendMessage(P + "Debug: " + (!cur ? "§aON" : "§cOFF"));
    }

    private void pipeline(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(P + "§cUsage: /bho pipeline <player>"); return; }
        Player t = Bukkit.getPlayer(args[1]);
        if (t == null) { s.sendMessage(P + "§cPlayer not found."); return; }

        Channel ch = GeyserSessionReflection.getBedrockChannel(t.getUniqueId());
        if (ch == null) { s.sendMessage(P + "§cCannot get channel (not Bedrock?)."); return; }

        ChannelPipeline pipeline = ch.pipeline();
        s.sendMessage(P + "§6Pipeline for " + t.getName() + ":");
        int i = 0;
        for (String name : pipeline.names()) {
            boolean isBHO = name.equals(BedrockPacketInterceptor.HANDLER_NAME);
            s.sendMessage("  §7" + (++i) + ". " + (isBHO ? "§a" : "§f") + name);
        }
    }

    private void repatch(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(P + "§cUsage: /bho repatch <player>"); return; }
        Player t = Bukkit.getPlayer(args[1]);
        if (t == null) { s.sendMessage(P + "§cPlayer not found."); return; }

        GeyserSessionReflection.patchSessionDimension(t.getUniqueId());
        s.sendMessage(P + "§aRepatch applied for " + t.getName()
            + ". Check console for details.");
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(P + "§6/bho §7info | offset <p> | list | reload | debug | pipeline <p> | repatch <p>");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c,
                                      @NotNull String l, @NotNull String[] args) {
        if (args.length == 1)
            return filter(Arrays.asList("info","offset","list","reload","debug","pipeline","repatch"), args[0]);
        if (args.length == 2 && List.of("offset","pipeline","repatch").contains(args[0].toLowerCase())) {
            List<String> n = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> n.add(p.getName()));
            return filter(n, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> l, String p) {
        return l.stream().filter(x -> x.toLowerCase().startsWith(p.toLowerCase())).toList();
    }
}
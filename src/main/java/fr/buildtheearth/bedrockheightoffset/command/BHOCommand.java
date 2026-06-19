package fr.buildtheearth.bedrockheightoffset.command;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserHook;
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

    private static final String PREFIX = "§6[BHO] §r";

    private final BedrockHeightOffset plugin;
    private final OffsetRegistry registry;

    public BHOCommand(BedrockHeightOffset plugin, OffsetRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.hasPermission("bho.admin")) {
            sender.sendMessage(PREFIX + "§cPermission denied.");
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "info"   -> handleInfo(sender);
            case "offset" -> handleOffset(sender, args);
            case "list"   -> handleList(sender);
            case "reload" -> handleReload(sender);
            case "debug"  -> handleDebug(sender);
            default       -> sendHelp(sender);
        }
        return true;
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage(PREFIX + "§6══ BedrockHeightOffset Info ══");
        sender.sendMessage("  §7Version: §f" + plugin.getPluginMeta().getVersion());
        sender.sendMessage("  §7Registered players: §f" + registry.size());
        sender.sendMessage("  §7Java world: §f" + plugin.getPluginConfig().getJavaMinY()
            + " → " + plugin.getPluginConfig().getJavaMaxY());
        sender.sendMessage("  §7Bedrock window: §f-64 → 320 (height=384)");
        sender.sendMessage("  §7Upper trigger: §f" + plugin.getPluginConfig().getUpperTrigger());
        sender.sendMessage("  §7Lower trigger: §f" + plugin.getPluginConfig().getLowerTrigger());
        sender.sendMessage("  §7Floodgate: §f" + GeyserHook.isFloodgateAvailable());
        sender.sendMessage("  §7Geyser: §f" + GeyserHook.isGeyserAvailable());
        sender.sendMessage("  §7Debug: §f" + plugin.getPluginConfig().isDebug());
        sender.sendMessage("  §7Bedrock-only: §f" + plugin.getPluginConfig().isBedrockOnly());
    }

    private void handleOffset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "§cUsage: /bho offset <player>");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cPlayer '" + args[1] + "' not found.");
            return;
        }

        PlayerOffsetData data = registry.get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(PREFIX + "§e" + target.getName()
                + " is not registered (Java player or plugin inactive).");
            return;
        }

        sender.sendMessage(PREFIX + "§6══ Offset: " + target.getName() + " ══");
        sender.sendMessage("  §7Type: " + (data.isBedrockPlayer() ? "§aBEDROCK" : "§9JAVA"));
        sender.sendMessage("  §7Offset: §f" + data.getOffset() + " blocks");
        sender.sendMessage("  §7Java Y: §f" + String.format("%.2f", data.getLastJavaY()));
        sender.sendMessage("  §7Bedrock Y: §f" + String.format("%.2f",
            data.toBedrockY(data.getLastJavaY())));
        sender.sendMessage("  §7Offset changes: §f" + data.getOffsetChangeCount());
        sender.sendMessage("  §7Last change: §f"
            + ((System.currentTimeMillis() - data.getLastOffsetChange()) / 1000) + "s ago");
    }

    private void handleList(CommandSender sender) {
        Collection<PlayerOffsetData> all = registry.all();
        if (all.isEmpty()) {
            sender.sendMessage(PREFIX + "§eNo players registered.");
            return;
        }
        sender.sendMessage(PREFIX + "§6══ Registered players (" + all.size() + ") ══");
        for (PlayerOffsetData data : all) {
            sender.sendMessage(String.format(
                "  %s §f%s §7| off=%d | jY=%.0f | bY=%.0f",
                data.isBedrockPlayer() ? "§a[BE]" : "§9[JE]",
                data.getName(), data.getOffset(),
                data.getLastJavaY(), data.toBedrockY(data.getLastJavaY())
            ));
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.getPluginConfig().reload();
        sender.sendMessage(PREFIX + "§aConfiguration reloaded.");
    }

    private void handleDebug(CommandSender sender) {
        boolean current = plugin.getPluginConfig().isDebug();
        plugin.getConfig().set("debug", !current);
        plugin.saveConfig();
        plugin.getPluginConfig().reload();
        sender.sendMessage(PREFIX + "Debug mode: " + (!current ? "§aENABLED" : "§cDISABLED"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "§6══ Commands ══");
        sender.sendMessage("  §6/bho info §7— general info");
        sender.sendMessage("  §6/bho offset <player> §7— player offset details");
        sender.sendMessage("  §6/bho list §7— list all registered players");
        sender.sendMessage("  §6/bho reload §7— reload config");
        sender.sendMessage("  §6/bho debug §7— toggle debug mode");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String label,
                                      @NotNull String[] args) {
        if (args.length == 1)
            return filter(Arrays.asList("info", "offset", "list", "reload", "debug"), args[0]);

        if (args.length == 2 && args[0].equalsIgnoreCase("offset")) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return filter(names, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> list, String prefix) {
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) result.add(s);
        }
        return result;
    }
}
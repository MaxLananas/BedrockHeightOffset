package fr.buildtheearth.bedrockheightoffset.command;

import fr.buildtheearth.bedrockheightoffset.BedrockHeightOffset;
import fr.buildtheearth.bedrockheightoffset.core.OffsetRegistry;
import fr.buildtheearth.bedrockheightoffset.core.PlayerOffsetData;
import fr.buildtheearth.bedrockheightoffset.geyser.GeyserHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

/**
 * Commande d'administration /bho
 *
 * Sous-commandes :
 *   /bho info          — informations générales
 *   /bho offset <joueur> — affiche l'offset d'un joueur
 *   /bho list          — liste tous les joueurs enregistrés
 *   /bho reload        — recharge la configuration
 *   /bho debug         — toggle du debug
 */
public class BHOCommand implements CommandExecutor, TabCompleter {

    private final BedrockHeightOffset plugin;
    private final OffsetRegistry registry;

    private static final Component PREFIX = Component.text("[BHO] ", NamedTextColor.GOLD, TextDecoration.BOLD);

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
            sender.sendMessage(PREFIX.append(
                Component.text("Permission refusée.", NamedTextColor.RED)
            ));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

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
        sender.sendMessage(PREFIX.append(
            Component.text("═══ BedrockHeightOffset Info ═══", NamedTextColor.GOLD)
        ));
        sender.sendMessage(info("Version", plugin.getPluginMeta().getVersion()));
        sender.sendMessage(info("Joueurs enregistrés", String.valueOf(registry.size())));
        sender.sendMessage(info("Bedrock window", "-64 → 320 (height=384)"));
        sender.sendMessage(info("Java world", plugin.getPluginConfig().getJavaMinY()
            + " → " + plugin.getPluginConfig().getJavaMaxY()));
        sender.sendMessage(info("Triggers", "upper=" + plugin.getPluginConfig().getUpperTrigger()
            + " lower=" + plugin.getPluginConfig().getLowerTrigger()));
        sender.sendMessage(info("Floodgate", String.valueOf(GeyserHook.isFloodgateAvailable())));
        sender.sendMessage(info("Geyser", String.valueOf(GeyserHook.isGeyserAvailable())));
        sender.sendMessage(info("Debug mode", String.valueOf(plugin.getPluginConfig().isDebug())));
        sender.sendMessage(info("Bedrock only", String.valueOf(plugin.getPluginConfig().isBedrockOnly())));
    }

    private void handleOffset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX.append(
                Component.text("Usage: /bho offset <joueur>", NamedTextColor.RED)
            ));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX.append(
                Component.text("Joueur '" + args[1] + "' introuvable.", NamedTextColor.RED)
            ));
            return;
        }

        PlayerOffsetData data = registry.get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(PREFIX.append(
                Component.text(target.getName() + " n'est pas enregistré (Java player ou plugin inactif).",
                    NamedTextColor.YELLOW)
            ));
            return;
        }

        sender.sendMessage(PREFIX.append(
            Component.text("═══ Offset de " + target.getName() + " ═══", NamedTextColor.GOLD)
        ));
        sender.sendMessage(info("Type", data.isBedrockPlayer() ? "§aBEDROCK" : "§9JAVA"));
        sender.sendMessage(info("Offset actuel", data.getOffset() + " blocs"));
        sender.sendMessage(info("Java Y", String.format("%.2f", data.getLastJavaY())));
        sender.sendMessage(info("Bedrock Y", String.format("%.2f", data.toBedrockY(data.getLastJavaY()))));
        sender.sendMessage(info("Recalculs", String.valueOf(data.getOffsetChangeCount())));
        sender.sendMessage(info("Dernier recalcul",
            ((System.currentTimeMillis() - data.getLastOffsetChange()) / 1000) + "s ago"));
    }

    private void handleList(CommandSender sender) {
        Collection<PlayerOffsetData> all = registry.all();
        if (all.isEmpty()) {
            sender.sendMessage(PREFIX.append(
                Component.text("Aucun joueur enregistré.", NamedTextColor.YELLOW)
            ));
            return;
        }

        sender.sendMessage(PREFIX.append(
            Component.text("═══ Joueurs enregistrés (" + all.size() + ") ═══", NamedTextColor.GOLD)
        ));

        for (PlayerOffsetData data : all) {
            String type = data.isBedrockPlayer() ? "§a[BE]" : "§9[JE]";
            sender.sendMessage(Component.text(
                type + " §f" + data.getName()
                + " §7| offset=" + data.getOffset()
                + " | jY=" + String.format("%.0f", data.getLastJavaY())
                + " | bY=" + String.format("%.0f", data.toBedrockY(data.getLastJavaY()))
            ));
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.getPluginConfig().reload();
        sender.sendMessage(PREFIX.append(
            Component.text("Configuration rechargée.", NamedTextColor.GREEN)
        ));
    }

    private void handleDebug(CommandSender sender) {
        // Toggle debug via rechargement config temporaire
        boolean current = plugin.getPluginConfig().isDebug();
        plugin.getConfig().set("debug", !current);
        plugin.saveConfig();
        plugin.getPluginConfig().reload();
        sender.sendMessage(PREFIX.append(
            Component.text("Debug mode : " + (!current ? "§aACTIVÉ" : "§cDÉSACTIVÉ"), NamedTextColor.WHITE)
        ));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX.append(
            Component.text("═══ BedrockHeightOffset Commandes ═══", NamedTextColor.GOLD)
        ));
        sender.sendMessage(Component.text("§6/bho info §7— Informations générales"));
        sender.sendMessage(Component.text("§6/bho offset <joueur> §7— Offset d'un joueur"));
        sender.sendMessage(Component.text("§6/bho list §7— Liste des joueurs"));
        sender.sendMessage(Component.text("§6/bho reload §7— Recharger la config"));
        sender.sendMessage(Component.text("§6/bho debug §7— Toggle debug mode"));
    }

    private Component info(String key, String value) {
        return Component.text("  §7" + key + ": §f" + value);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String label,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            return filterStart(Arrays.asList("info", "offset", "list", "reload", "debug"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("offset")) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return filterStart(names, args[1]);
        }
        return List.of();
    }

    private List<String> filterStart(List<String> list, String prefix) {
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) {
                result.add(s);
            }
        }
        return result;
    }
}
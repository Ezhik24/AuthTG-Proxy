package org.ezhik.authTGVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "authtgvelocity",
        name = "AuthTGVelocity",
        version = "1.0",
        authors = {"Ezhik24", "ABBAfan", "gas333t"},
        description = "Velocity side for AuthTG"
)
public class AuthTGVelocity {

    private final ProxyServer server;
    private final org.slf4j.Logger logger = LoggerFactory.getLogger(AuthTGVelocity.class);

    private final Set<UUID> authorizingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> hiddenCommands = Set.of(
            "/login", "/register", "/changepassword", "/setpassword", "/l", "/reg", "/code", "/vk"
    );
    private Filter logFilter;

    private final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("authtg", "main");

    @Inject
    public AuthTGVelocity(ProxyServer server) {   // <-- Больше нет параметра Logger
        this.server = server;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("§aAuthTG Velocity запущен!");

        Logger cmdLogger = (Logger) LogManager.getLogger("com.velocitypowered.proxy.command.CommandManager");

        logFilter = new AbstractFilter() {
            @Override
            public Filter.Result filter(LogEvent event) {
                if (event.getMessage() == null) return Result.NEUTRAL;
                String msg = event.getMessage().getFormattedMessage();
                for (String cmd : hiddenCommands) {
                    if (msg.contains("issued command: " + cmd)) {
                        return Result.DENY;
                    }
                }
                return Result.NEUTRAL;
            }
        };

        cmdLogger.addFilter(logFilter);
        logger.info("Log filter enabled for hidden commands.");
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        if (authorizingPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            event.getPlayer().sendMessage(Component.text("§cСначала заверши авторизацию!"));
        }
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) return;

        Player player = (Player) event.getCommandSource();
        if (authorizingPlayers.contains(player.getUniqueId())) {
            String cmd = event.getCommand().toLowerCase().split(" ")[0];
            Set<String> allowedCommands = new LinkedHashSet<>(List.of(
                    "/login", "/register", "/reg", "/l", "/code", "/2fa", "/vk"
            ));
            if (!allowedCommands.contains(cmd)) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                player.sendMessage(Component.text("§cСначала авторизуйтесь!"));
            };
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        if (authorizingPlayers.contains(player.getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text("§cЗаверши авторизацию перед переходом на другие сервера!"));
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;

        try (ByteArrayInputStream bais = new ByteArrayInputStream(event.getData());
             DataInputStream dis = new DataInputStream(bais)) {

            String action = dis.readUTF();
            UUID uuid = UUID.fromString(dis.readUTF());

            if (action.equals("auth_start")) {
                authorizingPlayers.add(uuid);
                logger.info("Игрок " + uuid + " начал авторизацию");
            } else if (action.equals("auth_success")) {
                authorizingPlayers.remove(uuid);
                logger.info("Игрок " + uuid + " успешно авторизовался");
            }
        } catch (Exception e) {
            logger.error("Ошибка обработки пакета от AuthTG", e);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        authorizingPlayers.remove(event.getPlayer().getUniqueId());
    }

    public boolean isAuthorizing(UUID uuid) {
        return authorizingPlayers.contains(uuid);
    }
}
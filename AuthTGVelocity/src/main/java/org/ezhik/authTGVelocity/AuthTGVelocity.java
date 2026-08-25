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
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "authtgvelocity",
        name = "AuthTGVelocity",
        version = "1.1",
        authors = {"Ezhik24", "ABBAfan", "gas333t"},
        description = "Velocity side for AuthTG"
)
public class AuthTGVelocity {

    private final ProxyServer server;
    private final Path dataDirectory;
    private final org.slf4j.Logger logger = LoggerFactory.getLogger(AuthTGVelocity.class);

    private final Set<UUID> authorizingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> hiddenCommands = Set.of(
            "/login", "/register", "/changepassword", "/cp", "/setpassword", "/l", "/reg", "/code", "/2fa", "/vk"
    );
    private final Set<String> routedCommands = Set.of(
            "/login", "/l", "/register", "/reg", "/changepassword", "/cp",
            "/logout", "/mail", "/prefer", "/unlink", "/code", "/2fa", "/vk"
    );
    private final Set<String> initialAuthCommands = Set.of("/login", "/l", "/register", "/reg");
    private String hubServerName = "hub";
    private final Properties messages = new Properties();
    private Filter logFilter;

    private final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("authtg", "main");

    @Inject
    public AuthTGVelocity(ProxyServer server, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        loadConfiguration();
        loadMessages();
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
            sendMessage(event.getPlayer(), "auth-required-chat");
        }
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) return;

        Player player = (Player) event.getCommandSource();
        String rawCommand = event.getCommand().trim();
        if (rawCommand.isEmpty()) return;
        String commandName = rawCommand.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!commandName.startsWith("/")) commandName = "/" + commandName;

        if (!authorizingPlayers.contains(player.getUniqueId())
                && initialAuthCommands.contains(commandName)
                && !isConnectedToHub(player)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            sendMessage(player, "already-authorized");
            return;
        }

        if (authorizingPlayers.contains(player.getUniqueId())) {
            Set<String> allowedCommands = new LinkedHashSet<>(List.of(
                    "/login", "/register", "/reg", "/l", "/code", "/2fa", "/vk"
            ));
            if (!allowedCommands.contains(commandName)) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                sendMessage(player, "auth-required-command");
                return;
            };
        }

        if (routedCommands.contains(commandName) && !isConnectedToHub(player)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            routeCommandToHub(player, rawCommand);
        }
    }

    private boolean isConnectedToHub(Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName().equalsIgnoreCase(hubServerName))
                .orElse(false);
    }

    private void routeCommandToHub(Player player, String rawCommand) {
        Optional<RegisteredServer> hub = server.getServer(hubServerName);
        if (hub.isEmpty()) {
            sendMessage(player, "hub-not-found", "{SERVER}", hubServerName);
            return;
        }

        sendMessage(player, "routing-to-hub");
        player.createConnectionRequest(hub.get()).connectWithIndication().thenAccept(connected -> {
            if (!connected || !player.isActive()) {
                sendMessage(player, "hub-connect-failed");
                return;
            }
            String command = rawCommand.startsWith("/") ? rawCommand : "/" + rawCommand;
            player.spoofChatInput(command);
        });
    }

    private void loadConfiguration() {
        Properties properties = new Properties();
        Path configFile = dataDirectory.resolve("config.properties");
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(configFile)) {
                try (InputStream input = Files.newInputStream(configFile)) {
                    properties.load(input);
                }
            } else {
                properties.setProperty("hub-server", hubServerName);
                try (OutputStream output = Files.newOutputStream(configFile)) {
                    properties.store(output, "AuthTGVelocity settings");
                }
            }
            hubServerName = properties.getProperty("hub-server", hubServerName).trim();
        } catch (IOException e) {
            logger.error("Не удалось загрузить config.properties, используется hub-server=hub", e);
        }
    }

    private void loadMessages() {
        messages.clear();
        messages.setProperty("auth-required-chat", "§cСначала заверши авторизацию!");
        messages.setProperty("already-authorized", "§eВы уже авторизованы.");
        messages.setProperty("auth-required-command", "§cСначала авторизуйтесь!");
        messages.setProperty("hub-not-found", "§cAuthTG: сервер авторизации '{SERVER}' не найден.");
        messages.setProperty("routing-to-hub", "§eПеренаправляем команду AuthTG на сервер авторизации...");
        messages.setProperty("hub-connect-failed", "§cНе удалось подключиться к серверу авторизации.");
        messages.setProperty("auth-required-server-switch", "§cЗаверши авторизацию перед переходом на другие сервера!");

        Path messagesFile = dataDirectory.resolve("messages.properties");
        try {
            if (Files.exists(messagesFile)) {
                try (var reader = Files.newBufferedReader(messagesFile, StandardCharsets.UTF_8)) {
                    messages.load(reader);
                }
            } else {
                try (var writer = Files.newBufferedWriter(messagesFile, StandardCharsets.UTF_8)) {
                    messages.store(writer, "AuthTGVelocity messages; {SERVER} is replaced with the hub server name");
                }
            }
        } catch (IOException e) {
            logger.error("Не удалось загрузить messages.properties, используются стандартные сообщения", e);
        }
    }

    private void sendMessage(Player player, String key, String... replacements) {
        String text = messages.getProperty(key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        player.sendMessage(Component.text(text));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        if (authorizingPlayers.contains(player.getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            sendMessage(player, "auth-required-server-switch");
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

package org.ezhik.authTGVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
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
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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

    private final AuthSessionStore authorizingPlayers = new AuthSessionStore(Duration.ofMinutes(10));
    private final Set<String> hiddenCommands = Set.of(
            "/login", "/register", "/captcha", "/premium", "/changepassword", "/cp", "/setpassword", "/l", "/reg", "/code", "/2fa", "/vk"
    );
    private final Set<String> routedCommands = Set.of(
            "/login", "/l", "/register", "/reg", "/changepassword", "/cp",
            "/logout", "/mail", "/premium", "/captcha", "/prefer", "/unlink", "/code", "/2fa", "/vk"
    );
    private final Set<String> initialAuthCommands = Set.of("/login", "/l", "/register", "/reg");
    private String hubServerName = "hub";
    private int authorizationTimeoutSeconds = 600;
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
        server.getCommandManager().register("authtgvelocity", new AdminCommand());
        server.getScheduler().buildTask(this, this::cleanupExpiredSessions)
                .repeat(30, TimeUnit.SECONDS).schedule();
        logger.info("§aAuthTG Velocity запущен!");

        Logger cmdLogger = (Logger) LogManager.getRootLogger();

        logFilter = new AbstractFilter() {
            @Override
            public Filter.Result filter(LogEvent event) {
                if (event.getMessage() == null) return Result.NEUTRAL;
                String msg = event.getMessage().getFormattedMessage().toLowerCase(Locale.ROOT);
                for (String cmd : hiddenCommands) {
                    if (containsCommandWithArguments(msg, cmd)) {
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
                    "/login", "/register", "/reg", "/l", "/captcha", "/code", "/2fa", "/vk"
            ));
            if (!allowedCommands.contains(commandName)) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                sendMessage(player, "auth-required-command");
                return;
            };
        }

        if (routedCommands.contains(commandName)) {
            if (isConnectedToHub(player)) {
                // Explicitly send AuthTG commands to the backend. Leaving the default
                // result can make Velocity or another proxy plugin consume the command.
                event.setResult(CommandExecuteEvent.CommandResult.forwardToServer(rawCommand));
            } else {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                routeCommandToHub(player, rawCommand);
            }
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
                properties.setProperty("authorization-timeout-seconds", "600");
                try (OutputStream output = Files.newOutputStream(configFile)) {
                    properties.store(output, "AuthTGVelocity settings");
                }
            }
            String configuredHub = properties.getProperty("hub-server", hubServerName).trim();
            if (configuredHub.isEmpty()) {
                logger.warn("hub-server is empty in config.properties; using the default server name '{}'", hubServerName);
            } else {
                hubServerName = configuredHub;
            }

            authorizationTimeoutSeconds = parsePositiveInt(properties.getProperty("authorization-timeout-seconds"), 600);
            properties.setProperty("authorization-timeout-seconds", String.valueOf(authorizationTimeoutSeconds));
            authorizingPlayers.setTtl(Duration.ofSeconds(authorizationTimeoutSeconds));

            if (server.getServer(hubServerName).isPresent()) {
                logger.info("AuthTG authorization server: '{}'", hubServerName);
            } else {
                logger.warn("AuthTG authorization server '{}' is not registered in velocity.toml", hubServerName);
            }
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
        String targetServerName = event.getOriginalServer().getServerInfo().getName();

        // An unauthenticated player must be able to enter the configured auth server.
        // Previously every connection was denied, including the connection initiated by
        // routeCommandToHub(), so /login and /register could never reach a custom lobby.
        if (authorizingPlayers.contains(player.getUniqueId())
                && !targetServerName.equalsIgnoreCase(hubServerName)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            sendMessage(player, "auth-required-server-switch");
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection)) {
            logger.warn("Rejected AuthTG plugin message from a non-server source");
            return;
        }
        ServerConnection source = (ServerConnection) event.getSource();
        if (!source.getServerInfo().getName().equalsIgnoreCase(hubServerName)) {
            logger.warn("Rejected AuthTG plugin message from backend '{}' (expected '{}')",
                    source.getServerInfo().getName(), hubServerName);
            return;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(event.getData());
             DataInputStream dis = new DataInputStream(bais)) {

            String action = dis.readUTF();
            UUID uuid = UUID.fromString(dis.readUTF());

            if (!source.getPlayer().getUniqueId().equals(uuid)) {
                logger.warn("Rejected AuthTG plugin message with mismatched player UUID from '{}'", hubServerName);
                return;
            }

            if (action.equals("auth_start")) {
                authorizingPlayers.start(uuid);
                logger.info("Игрок " + uuid + " начал авторизацию");
            } else if (action.equals("auth_success")) {
                authorizingPlayers.finish(uuid);
                logger.info("Игрок " + uuid + " успешно авторизовался");
            } else {
                logger.warn("Rejected unknown AuthTG plugin message action: '{}'", action);
            }
        } catch (Exception e) {
            logger.error("Ошибка обработки пакета от AuthTG", e);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        authorizingPlayers.finish(event.getPlayer().getUniqueId());
    }

    public boolean isAuthorizing(UUID uuid) {
        return authorizingPlayers.contains(uuid);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    private void cleanupExpiredSessions() {
        int removed = authorizingPlayers.cleanup();
        if (removed > 0) logger.info("Removed {} expired AuthTG authorization session(s)", removed);
    }

    private static boolean containsCommandWithArguments(String message, String command) {
        int index = message.indexOf(command);
        while (index >= 0) {
            int end = index + command.length();
            if ((index == 0 || Character.isWhitespace(message.charAt(index - 1)))
                    && end < message.length() && Character.isWhitespace(message.charAt(end))) return true;
            index = message.indexOf(command, index + 1);
        }
        return false;
    }

    private final class AdminCommand implements SimpleCommand {
         public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                loadConfiguration();
                loadMessages();
                invocation.source().sendMessage(Component.text("§aПерезагружено. hub-server=" + hubServerName));
            } else if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
                boolean exists = server.getServer(hubServerName).isPresent();
                invocation.source().sendMessage(Component.text("§6AuthTGVelocity status"));
                invocation.source().sendMessage(Component.text("§7hub-server: §f" + hubServerName + (exists ? " §a[доступен]" : " §c[не найден]")));
                invocation.source().sendMessage(Component.text("§7channel: §f" + CHANNEL.getId()));
                invocation.source().sendMessage(Component.text("§7авторизуются: §f" + authorizingPlayers.size()));
                invocation.source().sendMessage(Component.text("§7TTL: §f" + authorizationTimeoutSeconds + " сек."));
            } else {
                invocation.source().sendMessage(Component.text("§eИспользование: /authtgvelocity <reload|status>"));
            }
        }
         public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("authtgvelocity.admin");
        }
    }
}

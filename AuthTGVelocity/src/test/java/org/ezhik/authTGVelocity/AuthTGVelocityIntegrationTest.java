package org.ezhik.authTGVelocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthTGVelocityIntegrationTest {
    @Test
    void acceptsLobbyMessageAndAllowsOnlyLobbyTransition() throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        AuthTGVelocity plugin = new AuthTGVelocity(proxy, Path.of("target/test-data"));
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);

        ServerConnection lobbyConnection = connection("hub", player);
        PluginMessageEvent message = new PluginMessageEvent(
                lobbyConnection, mock(ChannelMessageSink.class),
                MinecraftChannelIdentifier.create("authtg", "main"), payload("auth_start", uuid));
        plugin.onPluginMessage(message);
        assertTrue(plugin.isAuthorizing(uuid));
        assertFalse(message.getResult().isAllowed());

        ServerPreConnectEvent lobby = new ServerPreConnectEvent(player, registeredServer("hub"));
        plugin.onServerPreConnect(lobby);
        assertTrue(lobby.getResult().isAllowed());

        ServerPreConnectEvent game = new ServerPreConnectEvent(player, registeredServer("survival"));
        plugin.onServerPreConnect(game);
        assertFalse(game.getResult().isAllowed());
    }

    @Test
    void rejectsAuthenticationMessageFromOtherBackend() throws Exception {
        AuthTGVelocity plugin = new AuthTGVelocity(mock(ProxyServer.class), Path.of("target/test-data"));
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        PluginMessageEvent message = new PluginMessageEvent(
                connection("survival", player), mock(ChannelMessageSink.class),
                MinecraftChannelIdentifier.create("authtg", "main"), payload("auth_start", uuid));
        plugin.onPluginMessage(message);
        assertFalse(plugin.isAuthorizing(uuid));
        assertFalse(message.getResult().isAllowed());
    }

    private static ServerConnection connection(String name, Player player) {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo()).thenReturn(info(name));
        when(connection.getPlayer()).thenReturn(player);
        return connection;
    }

    private static RegisteredServer registeredServer(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(info(name));
        return server;
    }

    private static ServerInfo info(String name) {
        return new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    }

    private static byte[] payload(String action, UUID uuid) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(action);
            output.writeUTF(uuid.toString());
        }
        return bytes.toByteArray();
    }
}

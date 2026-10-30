/*
 * Decompiled with CFR 0.152.
 */
package zombie.savefile;

import java.io.File;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.druid.hostunstuck.server.NetworkBufferSize;
import zombie.GameWindow;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.savefile.PlayerDBHelper;

public final class ServerPlayerDB {
    public static final String PLAYER_FALLBACK_USERNAME = "Player%d";
    private static ServerPlayerDB instance;
    private static boolean allow;
    public Connection conn;
    private ConcurrentLinkedQueue<NetworkCharacterData> charactersToSave;

    public static void setAllow(boolean en) {
        allow = en;
    }

    public static boolean isAllow() {
        return allow;
    }

    public static synchronized ServerPlayerDB getInstance() {
        if (instance == null && allow) {
            instance = new ServerPlayerDB();
        }
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public ServerPlayerDB() {
        if (Core.getInstance().isNoSave()) {
            return;
        }
        this.create();
    }

    public void close() {
        instance = null;
        allow = false;
    }

    private void create() {
        this.conn = PlayerDBHelper.create();
        this.charactersToSave = new ConcurrentLinkedQueue();
        try {
            DatabaseMetaData md = this.conn.getMetaData();
            Statement stat = this.conn.createStatement();
            ResultSet rs = md.getColumns(null, null, "networkPlayers", "steamid");
            if (!rs.next()) {
                stat.executeUpdate("ALTER TABLE 'networkPlayers' ADD 'steamid' STRING NULL");
            }
            rs.close();
            stat.close();
        }
        catch (SQLException e) {
            DebugType.General.printException(e, LogSeverity.Error);
        }
    }

    public void process() {
        if (!this.charactersToSave.isEmpty()) {
            NetworkCharacterData characterData = this.charactersToSave.poll();
            while (characterData != null) {
                this.serverUpdateNetworkCharacterInt(characterData);
                characterData = this.charactersToSave.poll();
            }
        }
    }

    @Deprecated
    public void serverUpdateNetworkCharacter(ByteBuffer bb, UdpConnection connection) {
        this.charactersToSave.add(new NetworkCharacterData(bb, connection));
    }

    public void save() {
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            for (IsoPlayer player : connection.players) {
                if (player == null) continue;
                this.serverUpdateNetworkCharacter(player, player.getIndex(), connection);
            }
        }
        DebugLog.log("Saving players");
    }

    public void saveFinishWait() {
        while (!this.charactersToSave.isEmpty()) {
            try {
                Thread.sleep(100L);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void serverUpdateNetworkCharacter(IsoPlayer player, int playerIndex, UdpConnection connection) {
        this.charactersToSave.add(new NetworkCharacterData(player, playerIndex, connection));
    }

    private void serverUpdateNetworkCharacterInt(NetworkCharacterData data) {
        if (data.playerIndex < 0 || data.playerIndex >= 4) {
            return;
        }
        if (this.conn == null) {
            return;
        }
        DebugType.Multiplayer.debugln("Save player \"%s\"", data.username);
        String sqlSelect = GameServer.coop && SteamUtils.isSteamModeEnabled() ? "SELECT id FROM networkPlayers WHERE steamid=? AND world=? AND playerIndex=?" : "SELECT id FROM networkPlayers WHERE username=? AND world=? AND playerIndex=?";
        String sqlInsert = "INSERT INTO networkPlayers(world,username,steamid, playerIndex,name,x,y,z,worldversion,isDead,splitUsername,data) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        String sqlUpdate = "UPDATE networkPlayers SET x=?, y=?, z=?, worldversion = ?, isDead = ?, data = ?, name = ?, splitUsername = ? WHERE id=?";
        try {
            block24: {
                try (PreparedStatement pstmt = this.conn.prepareStatement(sqlSelect);){
                    if (GameServer.coop && SteamUtils.isSteamModeEnabled()) {
                        pstmt.setString(1, data.steamid);
                    } else {
                        pstmt.setString(1, data.username);
                    }
                    pstmt.setString(2, Core.gameSaveWorld);
                    pstmt.setInt(3, data.playerIndex);
                    ResultSet rs = pstmt.executeQuery();
                    if (!rs.next()) break block24;
                    int sqlId = rs.getInt(1);
                    try (PreparedStatement pstmtUpdate = this.conn.prepareStatement("UPDATE networkPlayers SET x=?, y=?, z=?, worldversion = ?, isDead = ?, data = ?, name = ?, splitUsername = ? WHERE id=?");){
                        pstmtUpdate.setFloat(1, data.x);
                        pstmtUpdate.setFloat(2, data.y);
                        pstmtUpdate.setFloat(3, data.z);
                        pstmtUpdate.setInt(4, data.worldVersion);
                        pstmtUpdate.setBoolean(5, data.isDead);
                        pstmtUpdate.setBytes(6, data.buffer);
                        pstmtUpdate.setString(7, data.playerName);
                        pstmtUpdate.setString(8, data.splitUsername);
                        pstmtUpdate.setInt(9, sqlId);
                        int rowAffected = pstmtUpdate.executeUpdate();
                        this.conn.commit();
                    }
                    return;
                }
            }
            try (PreparedStatement pstmtInsert = this.conn.prepareStatement("INSERT INTO networkPlayers(world,username,steamid, playerIndex,name,x,y,z,worldversion,isDead,splitUsername,data) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)");){
                pstmtInsert.setString(1, Core.gameSaveWorld);
                pstmtInsert.setString(2, data.username);
                pstmtInsert.setString(3, data.steamid);
                pstmtInsert.setInt(4, data.playerIndex);
                pstmtInsert.setString(5, data.playerName);
                pstmtInsert.setFloat(6, data.x);
                pstmtInsert.setFloat(7, data.y);
                pstmtInsert.setFloat(8, data.z);
                pstmtInsert.setInt(9, data.worldVersion);
                pstmtInsert.setBoolean(10, data.isDead);
                pstmtInsert.setString(11, data.splitUsername);
                pstmtInsert.setBytes(12, data.buffer);
                int rowAffected = pstmtInsert.executeUpdate();
                this.conn.commit();
            }
        }
        catch (Exception e1) {
            ExceptionLogger.logException(e1);
            PlayerDBHelper.rollback(this.conn);
        }
    }

    public void serverConvertNetworkCharacter(String username, String steamIdStr) {
        try {
            String sqlUpdate = "UPDATE networkPlayers SET steamid=? WHERE username=? AND world=? AND (steamid is null or steamid = '')";
            try (PreparedStatement pstmt = this.conn.prepareStatement("UPDATE networkPlayers SET steamid=? WHERE username=? AND world=? AND (steamid is null or steamid = '')");){
                pstmt.setString(1, steamIdStr);
                pstmt.setString(2, username);
                pstmt.setString(3, Core.gameSaveWorld);
                int rowAffected = pstmt.executeUpdate();
                if (rowAffected > 0) {
                    DebugType.DetailedInfo.warn("serverConvertNetworkCharacter: The steamid was set for the '" + username + "' for " + rowAffected + " players. ");
                }
                this.conn.commit();
            }
        }
        catch (SQLException e) {
            ExceptionLogger.logException(e);
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public IsoPlayer serverLoadNetworkCharacter(int playerIndex, String idStr) {
        if (playerIndex < 0) return null;
        if (playerIndex >= 4) {
            return null;
        }
        if (this.conn == null) {
            return null;
        }
        String sqlSelect = GameServer.coop && SteamUtils.isSteamModeEnabled() ? "SELECT id, x, y, z, data, worldversion, isDead FROM networkPlayers WHERE steamid=? AND world=? AND playerIndex=?" : "SELECT id, x, y, z, data, worldversion, isDead FROM networkPlayers WHERE username=? AND world=? AND playerIndex=?";
        try (PreparedStatement pstmt = this.conn.prepareStatement(sqlSelect);){
            pstmt.setString(1, idStr);
            pstmt.setString(2, Core.gameSaveWorld);
            pstmt.setInt(3, playerIndex);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                IsoPlayer player;
                block24: {
                    int sqlId = rs.getInt(1);
                    float x = rs.getFloat(2);
                    float y = rs.getFloat(3);
                    float z = rs.getFloat(4);
                    byte[] data = rs.getBytes(5);
                    int worldversion = rs.getInt(6);
                    boolean isDead = rs.getBoolean(7);
                    try {
                        ByteBuffer bufferForLoadPlayer = ByteBuffer.allocate(data.length);
                        bufferForLoadPlayer.rewind();
                        bufferForLoadPlayer.put(data);
                        bufferForLoadPlayer.rewind();
                        player = new IsoPlayer(IsoWorld.instance.currentCell);
                        player.serverPlayerIndex = playerIndex;
                        try {
                            player.load(bufferForLoadPlayer, worldversion);
                        }
                        catch (Exception e) {
                            DebugType.General.printException(e, "The server cannot load player data.", LogSeverity.Error);
                            rs.close();
                            pstmt.close();
                            sqlSelect = GameServer.coop && SteamUtils.isSteamModeEnabled() ? "DELETE FROM networkPlayers WHERE steamid=? AND world=? AND playerIndex=?" : "DELETE FROM networkPlayers WHERE username=? AND world=? AND playerIndex=?";
                            try (PreparedStatement pstmt2 = this.conn.prepareStatement(sqlSelect);){
                                pstmt2.setString(1, idStr);
                                pstmt2.setString(2, Core.gameSaveWorld);
                                pstmt2.setInt(3, playerIndex);
                                pstmt2.executeUpdate();
                                pstmt2.close();
                            }
                            IsoPlayer isoPlayer = null;
                            if (pstmt == null) return isoPlayer;
                            pstmt.close();
                            return isoPlayer;
                        }
                        if (!isDead) break block24;
                        player.getBodyDamage().setOverallBodyHealth(0.0f);
                    }
                    catch (Exception ex) {
                        ExceptionLogger.logException(ex);
                        return null;
                    }
                    player.setHealth(0.0f);
                }
                player.remote = true;
                IsoPlayer isoPlayer = player;
                return isoPlayer;
            }
            IsoPlayer isoPlayer = null;
            return isoPlayer;
        }
        catch (SQLException e) {
            ExceptionLogger.logException(e);
        }
        return null;
    }

    public String getNetworkUserSteamID(String saveDir, String name, String world) throws SQLException {
        String string;
        block11: {
            if (this.conn == null) {
                return null;
            }
            File dbFile = new File(saveDir + File.separator + "players.db");
            if (!dbFile.exists()) {
                return null;
            }
            dbFile.setReadable(true, false);
            String steamID = null;
            String sql = "SELECT steamid FROM networkPlayers WHERE world = ? and username = ?";
            PreparedStatement pstmt = this.conn.prepareStatement("SELECT steamid FROM networkPlayers WHERE world = ? and username = ?");
            try {
                pstmt.setString(1, world);
                pstmt.setString(2, name);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    steamID = rs.getString(1);
                }
                this.conn.commit();
                string = steamID;
                if (pstmt == null) break block11;
            }
            catch (Throwable throwable) {
                try {
                    if (pstmt != null) {
                        try {
                            pstmt.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (Exception ex) {
                    DebugType.Multiplayer.printException(ex, "Query execution failed", LogSeverity.Error);
                    return null;
                }
            }
            pstmt.close();
        }
        return string;
    }

    private static final class NetworkCharacterData {
        byte[] buffer;
        String username;
        String steamid;
        int playerIndex;
        String playerName;
        float x;
        float y;
        float z;
        boolean isDead;
        String splitUsername;
        int worldVersion;

        public NetworkCharacterData(IsoPlayer player, int playerIndex, UdpConnection connection) {
            this.playerIndex = playerIndex;
            this.playerName = player.getDescriptor().getForename() + " " + player.getDescriptor().getSurname();
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.isDead = player.isDead();
            this.splitUsername = player.username;
            this.worldVersion = IsoWorld.getWorldVersion();
            int bufferSize = 32768;
            while (true) {
                try {
                    ByteBuffer sliceBuffer4NetworkPlayer = ByteBuffer.allocate(bufferSize);
                    player.save(sliceBuffer4NetworkPlayer);
                    this.buffer = new byte[sliceBuffer4NetworkPlayer.position()];
                    sliceBuffer4NetworkPlayer.rewind();
                    sliceBuffer4NetworkPlayer.get(this.buffer);
                }
                catch (BufferOverflowException e) {
                    if (bufferSize >= NetworkBufferSize.bytes()) {
                        DebugType.General.printException(e, LogSeverity.Error);
                        break;
                    }
                    bufferSize += 32768;
                    continue;
                }
                catch (IOException e) {
                    DebugType.General.printException(e, LogSeverity.Error);
                }
                break;
            }
            this.steamid = SteamUtils.isSteamModeEnabled() ? connection.getIDStr() : "";
            this.username = connection.getUserName();
        }

        @Deprecated
        public NetworkCharacterData(ByteBuffer bb, UdpConnection connection) {
            this.playerIndex = bb.get();
            this.playerName = GameWindow.ReadString(bb);
            this.x = bb.getFloat();
            this.y = bb.getFloat();
            this.z = bb.getFloat();
            this.isDead = bb.get() != 0;
            this.splitUsername = String.format(ServerPlayerDB.PLAYER_FALLBACK_USERNAME, this.playerIndex + 1);
            this.worldVersion = bb.getInt();
            int size = bb.getInt();
            this.buffer = new byte[size];
            bb.get(this.buffer);
            this.steamid = GameServer.coop && SteamUtils.isSteamModeEnabled() ? connection.getIDStr() : "";
            this.username = connection.getUserName();
        }
    }
}


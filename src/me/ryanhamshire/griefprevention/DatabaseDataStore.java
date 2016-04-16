/*
 * This file is part of GriefPrevention, licensed under the MIT License (MIT).
 *
 * Copyright (c) Ryan Hamshire
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.ryanhamshire.griefprevention;

import com.flowpowered.math.vector.Vector3i;
import me.ryanhamshire.griefprevention.claim.Claim;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.storage.WorldProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

//manages data stored in the file system
public class DatabaseDataStore extends DataStore {

    private Connection databaseConnection = null;

    private String databaseUrl;
    private String userName;
    private String password;

    DatabaseDataStore(String url, String userName, String password) throws Exception {
        this.databaseUrl = url;
        this.userName = userName;
        this.password = password;

        this.initialize();
    }

    @SuppressWarnings({"unused", "null"})
    @Override
    void initialize() throws Exception {
        try {
            // load the java driver for mySQL
            Class.forName("com.mysql.jdbc.Driver");
        } catch (Exception e) {
            GriefPrevention.addLogEntry("ERROR: Unable to load Java's mySQL database driver.  Check to make sure you've installed it properly.");
            throw e;
        }

        try {
            this.refreshDataConnection();
        } catch (Exception e2) {
            GriefPrevention.addLogEntry("ERROR: Unable to connect to database.  Check your config file settings.");
            throw e2;
        }

        try {
            // ensure the data tables exist
            Statement statement = databaseConnection.createStatement();

            statement.execute("CREATE TABLE IF NOT EXISTS griefprevention_nextclaimid (nextid INT(15));");

            statement.execute(
                    "CREATE TABLE IF NOT EXISTS griefprevention_claimdata (id INT(15), owner VARCHAR(50), lessercorner VARCHAR(100), greatercorner "
                            + "VARCHAR(100), builders VARCHAR(1000), containers VARCHAR(1000), accessors VARCHAR(1000), managers VARCHAR(1000), "
                            + "parentid INT(15));");

            statement.execute(
                    "CREATE TABLE IF NOT EXISTS griefprevention_playerdata (name VARCHAR(50), lastlogin DATETIME, accruedblocks INT(15), "
                            + "bonusblocks INT(15));");

            statement.execute("CREATE TABLE IF NOT EXISTS griefprevention_schemaversion (version INT(15));");

            // if the next claim id table is empty, this is a brand new database
            // which will write using the latest schema
            // otherwise, schema version is determined by schemaversion table
            // (or =0 if table is empty, see getSchemaVersion())
            ResultSet results = statement.executeQuery("SELECT * FROM griefprevention_nextclaimid;");
            if (!results.next()) {
                this.setSchemaVersion(latestSchemaVersion);
            }
        } catch (Exception e3) {
            GriefPrevention.addLogEntry("ERROR: Unable to create the necessary database table.  Details:");
            GriefPrevention.addLogEntry(e3.getMessage());
            e3.printStackTrace();
            throw e3;
        }

        // load group data into memory
        Statement statement = databaseConnection.createStatement();
        ResultSet results = statement.executeQuery("SELECT * FROM griefprevention_playerdata;");

        while (results.next()) {
            String name = results.getString("name");

            // ignore non-groups. all group names start with a dollar sign.
            if (!name.startsWith("$")) {
                continue;
            }

            String groupName = name.substring(1);
            if (groupName == null || groupName.isEmpty()) {
                continue; // defensive coding, avoid unlikely cases
            }

            int groupBonusBlocks = results.getInt("bonusblocks");

            this.permissionToBonusBlocksMap.put(groupName, groupBonusBlocks);
        }

        // load next claim number into memory
        results = statement.executeQuery("SELECT * FROM griefprevention_nextclaimid;");

        // if there's nothing yet, add it
        if (!results.next()) {
            statement.execute("INSERT INTO griefprevention_nextclaimid VALUES(0);");
            //this.nextClaimID = (long) 0;
        }

        // otherwise load it
        else {
            //this.nextClaimID = results.getLong("nextid");
        }

        if (this.getSchemaVersion() == 0) {
            try {
                this.refreshDataConnection();

                // pull ALL player data from the database
                statement = this.databaseConnection.createStatement();
                results = statement.executeQuery("SELECT * FROM griefprevention_playerdata;");

                // make a list of changes to be made
                HashMap<String, UUID> changes = new HashMap<String, UUID>();

                ArrayList<String> namesToConvert = new ArrayList<String>();
                while (results.next()) {
                    // get the id
                    String playerName = results.getString("name");

                    // add to list of names to convert to UUID
                    namesToConvert.add(playerName);
                }

                // reset results cursor
                results.beforeFirst();

                // for each result
                while (results.next()) {
                    // get the id
                    String playerName = results.getString("name");

                    // try to convert player name to UUID
                    try {
                        Optional<User> user = Sponge.getGame().getServiceManager().provide(UserStorageService.class).get().get(playerName);

                        // if successful, update the playerdata row by replacing
                        // the player's name with the player's UUID
                        if (user.isPresent()) {
                            changes.put(playerName, user.get().getUniqueId());
                        }
                    }
                    // otherwise leave it as-is. no harm done - it won't be
                    // requested by name, and this update only happens once.
                    catch (Exception ex) {
                    }
                }

                // refresh data connection in case data migration took a long time
                this.refreshDataConnection();

                for (String name : changes.keySet()) {
                    try {
                        statement = this.databaseConnection.createStatement();
                        statement.execute(
                                "UPDATE griefprevention_playerdata SET name = '" + changes.get(name).toString() + "' WHERE name = '" + name + "';");
                    } catch (SQLException e) {
                        GriefPrevention.addLogEntry("Unable to convert player data for " + name + ".  Skipping.");
                        GriefPrevention.addLogEntry(e.getMessage());
                    }
                }
            } catch (SQLException e) {
                GriefPrevention.addLogEntry("Unable to convert player data.  Details:");
                GriefPrevention.addLogEntry(e.getMessage());
                e.printStackTrace();
            }
        }

        // load claims data into memory
        results = statement.executeQuery("SELECT * FROM griefprevention_claimdata;");

        ArrayList<Claim> claimsToRemove = new ArrayList<Claim>();
        ArrayList<Claim> subdivisionsToLoad = new ArrayList<Claim>();
        List<World> validWorlds = (List<World>) Sponge.getGame().getServer().getWorlds();

        while (results.next()) {
            try {
                // problematic claims will be removed from secondary storage,
                // and never added to in-memory data store
                boolean removeClaim = false;

                long parentId = results.getLong("parentid");
                long claimID = results.getLong("id");

                Location<World> lesserBoundaryCorner = null;
                Location<World> greaterBoundaryCorner = null;
                String lesserCornerString = "(location not available)";
                try {
                    lesserCornerString = results.getString("lessercorner");
                    Vector3i lesserPos = positionFromString(lesserCornerString);
                    // lesserBoundaryCorner = positionFromString(lesserCornerString);

                    String greaterCornerString = results.getString("greatercorner");
                    //greaterBoundaryCorner = positionFromString(greaterCornerString);
                } catch (Exception e) {
                    if (e.getMessage().contains("World not found")) {
                        removeClaim = true;
                        GriefPrevention.addLogEntry("Removing a claim in a world which does not exist: " + lesserCornerString);
                        continue;
                    } else {
                        throw e;
                    }
                }

                String ownerName = results.getString("owner");
                Optional<User> owner = Optional.empty();
                if (ownerName.isEmpty() || ownerName.startsWith("--")) {
                    owner = Optional.empty(); // administrative land claim or subdivision
                } else if (this.getSchemaVersion() < 1) {
                    try {
                        owner = Sponge.getGame().getServiceManager().provide(UserStorageService.class).get().get(ownerName);
                    } catch (Exception ex) {
                        GriefPrevention.addLogEntry("This owner name did not convert to a UUID: " + ownerName + ".");
                        GriefPrevention.addLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
                    }
                }

                String managersString = results.getString("managers");
                List<String> managerNames = Arrays.asList(managersString.split(";"));
                managerNames = this.convertNameListToUUIDList(managerNames);

                Claim claim = new Claim(lesserBoundaryCorner, greaterBoundaryCorner);
                claim.ownerID = owner.get().getUniqueId();

                if (removeClaim) {
                    claimsToRemove.add(claim);
                } else if (parentId == -1) {
                    // top level claim
                    this.addClaim(claim, false);
                } else {
                    // subdivision
                    subdivisionsToLoad.add(claim);
                }
            } catch (SQLException e) {
                GriefPrevention.addLogEntry("Unable to load a claim.  Details: " + e.getMessage() + " ... " + results.toString());
                e.printStackTrace();
            }
        }

        // add subdivisions to their parent claims
        for (Claim childClaim : subdivisionsToLoad) {
            // find top level claim parent
            Claim topLevelClaim = this.getClaimAt(childClaim.getLesserBoundaryCorner(), true, null);

            if (topLevelClaim == null) {
                claimsToRemove.add(childClaim);
                GriefPrevention.addLogEntry("Removing orphaned claim subdivision: " + childClaim.getLesserBoundaryCorner().toString());
                continue;
            }

            // add this claim to the list of children of the current top level
            // claim
            childClaim.parent = topLevelClaim;
            topLevelClaim.children.add(childClaim);
            childClaim.inDataStore = true;
        }

        for (int i = 0; i < claimsToRemove.size(); i++) {
            this.deleteClaimFromSecondaryStorage(claimsToRemove.get(i));
        }

        if (this.getSchemaVersion() <= 2) {
            this.refreshDataConnection();
            statement = this.databaseConnection.createStatement();
            statement.execute("DELETE FROM griefprevention_claimdata WHERE id='-1';");
        }

        super.initialize();
    }

    // see datastore.cs. this will ALWAYS be a top level claim
    @Override
    synchronized void writeClaimToStorage(Claim claim) {
        try {
            this.refreshDataConnection();

            // wipe out any existing data about this claim
            this.deleteClaimFromSecondaryStorage(claim);

            // write claim data to the database
            this.writeClaimData(claim);
        } catch (SQLException e) {
            //GriefPrevention.AddLogEntry("Unable to save data for claim at " + this.locationToString(claim.lesserBoundaryCorner) + ".  Details:");
            GriefPrevention.addLogEntry(e.getMessage());
        }
    }

    // actually writes claim data to the database
    synchronized private void writeClaimData(Claim claim) throws SQLException {
        /*String lesserCornerString = this.locationToString(claim.getLesserBoundaryCorner());
        String greaterCornerString = this.locationToString(claim.getGreaterBoundaryCorner());
        String owner = "";
        if (claim.ownerID != null)
            owner = claim.ownerID.toString();

        ArrayList<String> builders = new ArrayList<String>();
        ArrayList<String> containers = new ArrayList<String>();
        ArrayList<String> accessors = new ArrayList<String>();
        ArrayList<String> managers = new ArrayList<String>();

        claim.getPermissions(builders, containers, accessors, managers);

        String buildersString = "";
        for (int i = 0; i < builders.size(); i++) {
            buildersString += builders.get(i) + ";";
        }

        String containersString = "";
        for (int i = 0; i < containers.size(); i++) {
            containersString += containers.get(i) + ";";
        }

        String accessorsString = "";
        for (int i = 0; i < accessors.size(); i++) {
            accessorsString += accessors.get(i) + ";";
        }

        String managersString = "";
        for (int i = 0; i < managers.size(); i++) {
            managersString += managers.get(i) + ";";
        }

        long parentId;
        if (claim.parent == null) {
            parentId = -1;
        } else {
            parentId = claim.parent.id;
        }

        try {
            this.refreshDataConnection();

            Statement statement = databaseConnection.createStatement();
            statement.execute(
                    "INSERT INTO griefprevention_claimdata (id, owner, lessercorner, greatercorner, builders, containers, accessors, managers,
                    parentid) VALUES("
                            +
                            claim.id + ", '" +
                            owner + "', '" +
                            lesserCornerString + "', '" +
                            greaterCornerString + "', '" +
                            buildersString + "', '" +
                            containersString + "', '" +
                            accessorsString + "', '" +
                            managersString + "', " +
                            parentId +
                            ");");
        } catch (SQLException e) {
            GriefPrevention.AddLogEntry("Unable to save data for claim at " + this.locationToString(claim.lesserBoundaryCorner) + ".  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
        }*/
    }

    // deletes a claim from the database
    @Override
    synchronized void deleteClaimFromSecondaryStorage(Claim claim) {
        try {
            this.refreshDataConnection();

            Statement statement = this.databaseConnection.createStatement();
            statement.execute("DELETE FROM griefprevention_claimdata WHERE id='" + claim.id + "';");
        } catch (SQLException e) {
            GriefPrevention.addLogEntry("Unable to delete data for claim " + claim.id + ".  Details:");
            GriefPrevention.addLogEntry(e.getMessage());
            e.printStackTrace();
        }
    }

    /*@Override
    synchronized PlayerData getPlayerDataFromStorage(UUID playerID) {
        PlayerData playerData = new PlayerData();
        playerData.playerID = playerID;

        try {
            this.refreshDataConnection();

            Statement statement = this.databaseConnection.createStatement();
            ResultSet results = statement.executeQuery("SELECT * FROM griefprevention_playerdata WHERE name='" + playerID.toString() + "';");

            // if data for this player exists, use it
            if (results.next()) {
                playerData.setLastLogin(results.getTimestamp("lastlogin"));
                playerData.setAccruedClaimBlocks(results.getInt("accruedblocks"));
                playerData.setBonusClaimBlocks(results.getInt("bonusblocks"));
            }
        } catch (SQLException e) {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            GriefPrevention.AddLogEntry(playerID + " " + errors.toString(), CustomLogEntryTypes.Exception);
        }

        return playerData;
    }*/

    // saves changes to player data. MUST be called after you're done making
    // changes, otherwise a reload will lose them
    @Override
    public void overrideSavePlayerData(UUID playerID, PlayerData playerData) {
        // never save data for the "administrative" account. an empty string for
        // player name indicates administrative account
        if (playerID == null) {
            return;
        }

        //this.savePlayerData(playerID.toString(), playerData);
    }

   /* private void savePlayerData(String playerID, PlayerData playerData) {
        try {
            this.refreshDataConnection();

            SimpleDateFormat sqlFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String dateString = sqlFormat.format(playerData.getLastLogin());

            Statement statement = databaseConnection.createStatement();
            statement.execute("DELETE FROM griefprevention_playerdata WHERE name='" + playerID.toString() + "';");
            statement = databaseConnection.createStatement();
            statement.execute("INSERT INTO griefprevention_playerdata (name, lastlogin, accruedblocks, bonusblocks) VALUES ('" + playerID.toString()
                    + "', '" + dateString + "', " + playerData.getAccruedClaimBlocks() + ", " + playerData.getBonusClaimBlocks() + ");");
        } catch (SQLException e) {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            GriefPrevention.AddLogEntry(playerID + " " + errors.toString(), CustomLogEntryTypes.Exception);
        }
    }*/

    @Override
    synchronized void incrementNextClaimID() {
        //this.setNextClaimID(this.nextClaimID + 1);
    }

    // sets the next claim ID. used by incrementNextClaimID() above, and also
    // while migrating data from a flat file data store
    synchronized void setNextClaimID(long nextID) {
        // this.nextClaimID = nextID;

        try {
            this.refreshDataConnection();

            Statement statement = databaseConnection.createStatement();
            statement.execute("DELETE FROM griefprevention_nextclaimid;");
            statement.execute("INSERT INTO griefprevention_nextclaimid VALUES (" + nextID + ");");
        } catch (SQLException e) {
            GriefPrevention.addLogEntry("Unable to set next claim ID to " + nextID + ".  Details:");
            GriefPrevention.addLogEntry(e.getMessage());
        }
    }

    // updates the database with a group's bonus blocks
    @Override
    synchronized void saveGroupBonusBlocks(String groupName, int currentValue) {
        // group bonus blocks are stored in the player data table, with player
        // name = $groupName
        try {
            this.refreshDataConnection();

            SimpleDateFormat sqlFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String dateString = sqlFormat.format(new Date());

            Statement statement = databaseConnection.createStatement();
            statement.execute("DELETE FROM griefprevention_playerdata WHERE name='$" + groupName + "';");
            statement = databaseConnection.createStatement();
            statement.execute("INSERT INTO griefprevention_playerdata (name, lastlogin, accruedblocks, bonusblocks) VALUES ('$" + groupName + "', '"
                    + dateString + "', " + "0" + ", " + String.valueOf(currentValue) + ");");
        } catch (SQLException e) {
            GriefPrevention.addLogEntry("Unable to save data for group " + groupName + ".  Details:");
            GriefPrevention.addLogEntry(e.getMessage());
        }
    }

    synchronized void close() {
        if (this.databaseConnection != null) {
            try {
                if (!this.databaseConnection.isClosed()) {
                    this.databaseConnection.close();
                }
            } catch (SQLException e) {
            }
            ;
        }

        this.databaseConnection = null;
    }

    private synchronized void refreshDataConnection() throws SQLException {
        if (this.databaseConnection == null || !this.databaseConnection.isValid(3)) {
            // set username/pass properties
            Properties connectionProps = new Properties();
            connectionProps.put("user", this.userName);
            connectionProps.put("password", this.password);
            connectionProps.put("autoReconnect", "true");
            connectionProps.put("maxReconnects", String.valueOf(Integer.MAX_VALUE));

            // establish connection
            this.databaseConnection = DriverManager.getConnection(this.databaseUrl, connectionProps);
        }
    }

    @Override
    protected int getSchemaVersionFromStorage() {
        try {
            this.refreshDataConnection();

            Statement statement = this.databaseConnection.createStatement();
            ResultSet results = statement.executeQuery("SELECT * FROM griefprevention_schemaversion;");

            // if there's nothing yet, assume 0 and add it
            if (!results.next()) {
                this.setSchemaVersion(0);
                return 0;
            }

            // otherwise return the value that's in the table
            else {
                return results.getInt("version");
            }

        } catch (SQLException e) {
            GriefPrevention.addLogEntry("Unable to retrieve schema version from database.  Details:");
            GriefPrevention.addLogEntry(e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    @Override
    protected void updateSchemaVersionInStorage(int versionToSet) {
        try {
            this.refreshDataConnection();

            Statement statement = databaseConnection.createStatement();
            statement.execute("DELETE FROM griefprevention_schemaversion;");
            statement.execute("INSERT INTO griefprevention_schemaversion VALUES (" + versionToSet + ");");
        } catch (SQLException e) {
            GriefPrevention.addLogEntry("Unable to set next schema version to " + versionToSet + ".  Details:");
            GriefPrevention.addLogEntry(e.getMessage());
        }
    }

    @Override
    PlayerData getPlayerDataFromStorage(UUID playerID) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PlayerData createPlayerWorldStorageData(WorldProperties worldProperties, UUID playerUniqueId) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PlayerData getPlayerData(WorldProperties worldProperties, UUID playerUniqueId) {
        // TODO Auto-generated method stub
        return null;
    }

}

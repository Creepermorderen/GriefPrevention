package me.ryanhamshire.griefprevention.command;

import me.ryanhamshire.griefprevention.CustomLogEntryTypes;
import me.ryanhamshire.griefprevention.GPPermissions;
import me.ryanhamshire.griefprevention.GriefPrevention;
import me.ryanhamshire.griefprevention.Messages;
import me.ryanhamshire.griefprevention.PlayerData;
import me.ryanhamshire.griefprevention.TextMode;
import me.ryanhamshire.griefprevention.claim.Claim;
import me.ryanhamshire.griefprevention.claim.ClaimWorldManager;
import me.ryanhamshire.griefprevention.claim.ClaimWorldManager.NoTransferException;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;

public class CommandClaimTransfer implements CommandExecutor {

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) {
        Player player;
        try {
            player = GriefPrevention.checkPlayer(src);
        } catch (CommandException e1) {
            src.sendMessage(e1.getText());
            return CommandResult.success();
        }

        User targetPlayer = args.<User>getOne("user").orElse(null);
        if (targetPlayer == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, "No user found.");
            return CommandResult.success();
        }

        // which claim is the user in?
        PlayerData playerData = GriefPrevention.instance.dataStore.getOrCreatePlayerData(player.getWorld(), player.getUniqueId());
        Claim claim = GriefPrevention.instance.dataStore.getClaimAtPlayer(playerData, player.getLocation(), true);
        if (claim == null || claim.isWildernessClaim()) {
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.TransferClaimMissing);
            return CommandResult.empty();
        }

        // check additional permission for admin claims
        if (claim.isAdminClaim()) {
            if (!player.hasPermission(GPPermissions.COMMAND_ADMIN_CLAIMS)) {
                try {
                    throw new CommandException(GriefPrevention.getMessage(Messages.TransferClaimPermission));
                } catch (CommandException e1) {
                    src.sendMessage(e1.getText());
                    return CommandResult.success();
                }
            }
        }

        // change ownership
        try {
            ClaimWorldManager claimWorldManager = GriefPrevention.instance.dataStore.getClaimWorldManager(claim.world.getProperties());
            claimWorldManager.transferClaimOwner(claim, targetPlayer.getUniqueId());
        } catch (NoTransferException e) {
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.TransferTopLevel);
            return CommandResult.empty();
        }

        // confirm
        GriefPrevention.sendMessage(player, TextMode.Success, Messages.TransferSuccess);
        GriefPrevention.addLogEntry(player.getName() + " transferred a claim at "
                        + GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()) + " to " + targetPlayer.getName() + ".",
                CustomLogEntryTypes.AdminActivity);

        return CommandResult.success();

    }
}

package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import org.apache.commons.lang3.RandomStringUtils;
import zgoly.meteorist.Meteorist;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DmSpam extends Module {
    private final SettingGroup sgCommand = settings.createGroup("Command");
    private final SettingGroup sgSelection = settings.createGroup("Selection");
    private final SettingGroup sgDelay = settings.createGroup("Delay");
    private final SettingGroup sgDisableSettings = settings.createGroup("Disable Settings");
    private final SettingGroup sgSpecialCases = settings.createGroup("Special Cases");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    private final Setting<String> messageCommand = sgCommand.add(new StringSetting.Builder()
            .name("message-command")
            .description("Specified command to direct message a player.")
            .defaultValue("/msg {player} {message}")
            .build()
    );

    private final Setting<List<String>> spamMessages = sgSelection.add(new StringListSetting.Builder()
            .name("spam-messages")
            .description("List of messages that can be sent to the players.")
            .defaultValue(List.of("Meteorist :handshake: Meteor"))
            .build()
    );
    private final Setting<Mode> messageMode = sgSelection.add(new EnumSetting.Builder<Mode>()
            .name("message-mode")
            .description("'Sequential' - send messages in the order they appear in the list; 'Random' - pick a random message from the list.")
            .defaultValue(Mode.Sequential)
            .build()
    );
    private final Setting<Mode> playerMode = sgSelection.add(new EnumSetting.Builder<Mode>()
            .name("player-mode")
            .description("'Sequential' - select players in the order they appear; 'Random' - pick a random player.")
            .defaultValue(Mode.Sequential)
            .build()
    );

    private final Setting<Integer> delayBetweenMessages = sgDelay.add(new IntSetting.Builder()
            .name("delay-between-messages")
            .description("Time delay in ticks between the sending of individual messages.")
            .defaultValue(20)
            .min(1)
            .sliderMax(1200)
            .build()
    );
    private final Setting<Integer> delayBetweenPlayers = sgDelay.add(new IntSetting.Builder()
            .name("delay-between-players")
            .description("Time delay in ticks after all messages have been sent to all players.")
            .defaultValue(100)
            .min(1)
            .sliderMax(1200)
            .build()
    );

    private final Setting<DisableTrigger> disableTrigger = sgDisableSettings.add(new EnumSetting.Builder<DisableTrigger>()
            .name("disable-trigger")
            .description("'None' - never; 'NoMoreMessages' - no more messages to send; 'NoMorePlayers' - no more players to send messages to.")
            .defaultValue(DisableTrigger.None)
            .build()
    );
    private final Setting<Boolean> disableOnExit = sgDisableSettings.add(new BoolSetting.Builder()
            .name("disable-on-exit")
            .description("Stops the spam message flow when you leave a server.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> disableOnDisconnect = sgDisableSettings.add(new BoolSetting.Builder()
            .name("disable-on-disconnect")
            .description("Stops the spam message flow if you are disconnected from the server (eg. kicked).")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> excludeSelf = sgSpecialCases.add(new BoolSetting.Builder()
            .name("exclude-self")
            .description("If set to 'true', the system will not send messages to yourself.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> antiSpamBypass = sgSpecialCases.add(new BoolSetting.Builder()
            .name("anti-spam-bypass")
            .description("Adds random text at the end of each message to avoid spam detection.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> bypassTextLength = sgSpecialCases.add(new IntSetting.Builder()
            .name("bypass-text-length")
            .description("Defines the number of characters used to bypass spam detection.")
            .visible(antiSpamBypass::get)
            .defaultValue(16)
            .sliderRange(1, 256)
            .build()
    );
    private final Setting<PlayerMode> playerModeSetting = sgSpecialCases.add(new EnumSetting.Builder<PlayerMode>()
            .name("player-mode")
            .description("Choose between Blacklist (exclude players) or Whitelist (only include players).")
            .defaultValue(PlayerMode.Blacklist)
            .build()
    );
    private final Setting<List<String>> playerWhitelist = sgSpecialCases.add(new StringListSetting.Builder()
            .name("player-whitelist")
            .description("List of player names to include when in Whitelist mode.")
            .build()
    );
    private final Setting<List<String>> playerBlacklist = sgSpecialCases.add(new StringListSetting.Builder()
            .name("player-blacklist")
            .description("List of player names to exclude from receiving messages when in Blacklist mode.")
            .build()
    );

    private final Setting<Boolean> printDebugInfo = sgDebug.add(new BoolSetting.Builder()
            .name("print-debug-info")
            .description("Logs debug information in the chat.")
            .defaultValue(false)
            .build()
    );

    private final List<UUID> usedPlayerUUIDs = new ArrayList<>();
    private final List<Integer> usedMessageIds = new ArrayList<>();
    private long currentTick;

    public DmSpam() {
        super(Meteorist.CATEGORY, "dm-spam", "Spams messages in players direct messages.");
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (disableOnDisconnect.get() && event.screen instanceof DisconnectedScreen) toggle();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (disableOnExit.get()) toggle();
    }

    @Override
    public void onActivate() {
        currentTick = mc.world.getTime();
    }

    @EventHandler
    public void onDeactivate() {
        usedPlayerUUIDs.clear();
        usedMessageIds.clear();
    }

    // I'm not sure if this is the best way to do it, but it seems to work like a charm
    @EventHandler
    private void onTick(TickEvent.Post event) {
        List<UUID> allPlayerUUIDs = new ArrayList<>(mc.getNetworkHandler().getPlayerUuids());
        if (allPlayerUUIDs.isEmpty() || messageCommand.get().isEmpty()) return;

        List<UUID> unusedPlayerUUIDs = new ArrayList<>(allPlayerUUIDs);
        unusedPlayerUUIDs.removeAll(usedPlayerUUIDs);

        if (excludeSelf.get()) unusedPlayerUUIDs.remove(mc.player.getUuid());

        long currentWorldTime = mc.world.getTime();

        if (!unusedPlayerUUIDs.isEmpty() && currentTick <= currentWorldTime) {
            UUID selectedPlayerUUID = playerMode.get() == Mode.Sequential ? unusedPlayerUUIDs.getFirst() : unusedPlayerUUIDs.get(new Random().nextInt(unusedPlayerUUIDs.size()));

            String playerName = mc.getNetworkHandler().getPlayerList().stream()
                    .filter(player -> player.getProfile().getId().equals(selectedPlayerUUID))
                    .map(player -> player.getProfile().getName())
                    .findFirst()
                    .orElse("");

            if (playerModeSetting.get() == PlayerMode.Blacklist) {
                if (playerBlacklist.get().contains(playerName)) {
                    if (printDebugInfo.get()) info("Skipping blacklisted player: " + playerName);
                    usedPlayerUUIDs.add(selectedPlayerUUID);
                    return;
                }
            } else if (playerModeSetting.get() == PlayerMode.Whitelist) {
                if (!playerWhitelist.get().contains(playerName)) {
                    if (printDebugInfo.get()) info("Skipping non-whitelisted player: " + playerName);
                    usedPlayerUUIDs.add(selectedPlayerUUID);
                    return;
                }
            }

            List<Integer> allMessageIds = IntStream.rangeClosed(0, spamMessages.get().size() - 1).boxed().collect(Collectors.toCollection(LinkedList::new));
            allMessageIds.removeAll(usedMessageIds);

            if (allMessageIds.isEmpty()) {
                if (disableTrigger.get() == DisableTrigger.NoMoreMessages) {
                    toggle();
                    return;
                }
                usedMessageIds.clear();
                allMessageIds = IntStream.rangeClosed(0, spamMessages.get().size() - 1).boxed().collect(Collectors.toCollection(LinkedList::new));
            }

            int selectedMessageId = messageMode.get() == Mode.Sequential ? allMessageIds.getFirst() : allMessageIds.get(new Random().nextInt(allMessageIds.size()));
            String selectedMessage = spamMessages.get().get(selectedMessageId);
            if (antiSpamBypass.get())
                selectedMessage += " " + RandomStringUtils.insecure().nextAlphabetic(bypassTextLength.get()).toLowerCase();

            ChatUtils.sendPlayerMsg(messageCommand.get().replace("{player}", playerName).replace("{message}", selectedMessage));
            if (printDebugInfo.get())
                info("Sent '" + selectedMessage + "' to '" + playerName + "'. Handling a delay of " + delayBetweenMessages.get() + " ticks.");

            usedMessageIds.add(selectedMessageId);
            usedPlayerUUIDs.add(selectedPlayerUUID);

            currentTick = currentWorldTime + delayBetweenMessages.get();
        } else if (unusedPlayerUUIDs.isEmpty() && disableTrigger.get() == DisableTrigger.NoMorePlayers) {
            toggle();
        } else if (currentTick <= currentWorldTime) {
            currentTick = currentWorldTime + delayBetweenPlayers.get();
            if (printDebugInfo.get())
                info("The players ended, handling a delay of " + delayBetweenPlayers.get() + " ticks.");
            usedPlayerUUIDs.clear();
        }
    }

    public enum Mode {
        Sequential,
        Random
    }

    public enum DisableTrigger {
        None,
        NoMoreMessages,
        NoMorePlayers
    }

    public enum PlayerMode {
        Blacklist,
        Whitelist
    }
}
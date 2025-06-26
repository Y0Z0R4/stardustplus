package dev.stardust.commands;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.text.Text;
import dev.stardust.Stardust;
import dev.stardust.util.StardustUtil;
import net.minecraft.command.CommandSource;
import net.minecraft.client.MinecraftClient;
import dev.stardust.util.commands.ApiHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import meteordevelopment.meteorclient.commands.Command;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;

public class Queue extends Command {
    private static final String QUEUE_PATH = "/queue";
    private static final String ETA_PATH = "/queue/eta-equation";
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final WaitTimeFormula BASE_REGULAR = new WaitTimeFormula(25.0, 1.15);
    private static final WaitTimeFormula BASE_PRIORITY = new WaitTimeFormula(12.0, 1.05);

    private static WaitTimeFormula currentRegular = BASE_REGULAR;
    private static WaitTimeFormula currentPriority = BASE_PRIORITY;
    private static Instant lastUpdateTime = Instant.EPOCH;

    public Queue() {
        super("GetQueue", "Displays 2b2t queue info", "q", "queue", "2b2tqueue");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            MeteorExecutor.execute(() -> {
                ClientPlayerEntity mcPlayer = MinecraftClient.getInstance().player;
                if (mcPlayer == null) return;

                if (Duration.between(lastUpdateTime, Instant.now()).toMinutes() >= 15) {
                    refreshEtaData();
                }

                String fullUrl = ApiHandler.API_2B2T_URL + QUEUE_PATH;
                String apiResult = new ApiHandler().fetchResponse(fullUrl);
                if (apiResult == null) return;

                try {
                    sendStatusMessage(mcPlayer, apiResult);
                } catch (Exception exception) {
                    Stardust.LOG.error("[CheckLine] Error parsing queue response: " + exception.getMessage());
                    mcPlayer.sendMessage(Text.of("§c[CheckLine] Could not retrieve queue data."), false);
                }
            });
            return SINGLE_SUCCESS;
        });
    }

    private void sendStatusMessage(ClientPlayerEntity entity, String json) throws Exception {
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        String serverTime = parsed.get("time").getAsString();
        int prioPos = parsed.get("prio").getAsInt();
        int regPos = parsed.get("regular").getAsInt();

        OffsetDateTime dateTime = OffsetDateTime.parse(serverTime);
        String formatted = dateTime.format(CLOCK_FORMAT);

        String colorCode = StardustUtil.rCC();
        String prioEstimate = calculateEta(prioPos, true);
        String regEstimate = calculateEta(regPos, false);

        String queueInfo = "§7[2b2t Queue] §8(" + formatted + ")\n" +
            "§7Priority: §f" + prioPos + " §8| §7ETA: §f" + prioEstimate + "\n" +
            "§7Regular: §f" + regPos + " §8| §7ETA: §f" + regEstimate;

        entity.sendMessage(Text.of(queueInfo), false);
    }

    private void refreshEtaData() {
        ApiHandler handler = new ApiHandler();
        String result = handler.fetchResponse(ApiHandler.API_2B2T_URL + ETA_PATH);

        if (result == null || result.equals("204")) {
            lastUpdateTime = Instant.now();
            return;
        }

        try {
            JsonObject obj = JsonParser.parseString(result).getAsJsonObject();

            double rf = getDouble(obj, "regularFactor", BASE_REGULAR.factor());
            double rp = getDouble(obj, "regularPow", BASE_REGULAR.pow());
            double pf = getDouble(obj, "priorityFactor", BASE_PRIORITY.factor());
            double pp = getDouble(obj, "priorityPow", BASE_PRIORITY.pow());

            currentRegular = new WaitTimeFormula(rf, rp);
            currentPriority = new WaitTimeFormula(pf, pp);

            lastUpdateTime = Instant.now();
        } catch (Exception ex) {
            Stardust.LOG.error("[QueueCalc] ETA fetch failure: " + ex.getMessage());
            currentRegular = BASE_REGULAR;
            currentPriority = BASE_PRIORITY;
            lastUpdateTime = Instant.now();
        }
    }

    private double getDouble(JsonObject json, String field, double fallback) {
        return json.has(field) ? json.get(field).getAsDouble() : fallback;
    }

    private long estimateWaitTime(int pos, boolean priority) {
        WaitTimeFormula formula = priority ? currentPriority : currentRegular;
        return (long) (formula.factor() * Math.pow(pos, formula.pow()));
    }

    private String formatEta(long seconds) {
        if (seconds <= 0) return "00:00:00";
        int hrs = (int) (seconds / 3600);
        int mins = (int) ((seconds / 60) % 60);
        int secs = (int) (seconds % 60);
        return String.format("%02d:%02d:%02d", hrs, mins, secs);
    }

    private String calculateEta(int pos, boolean prio) {
        if (pos <= 0) return "Now";
        return formatEta(estimateWaitTime(pos, prio));
    }

    private record WaitTimeFormula(double factor, double pow) {}
}

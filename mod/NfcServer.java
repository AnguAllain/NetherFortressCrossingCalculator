package nfc;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;

/** 每 tick 推进当前的 clear / place / check 任务。 */
public final class NfcServer {
    public static final NfcState STATE = new NfcState();
    public static NfcJob running = null;

    private NfcServer() {
    }

    public static void startJob(NfcJob job) {
        if (running != null) {
            running.stop();
        }
        running = job;
        job.start();
    }

    public static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (running == null) {
                return;
            }
            if (running.tick()) {
                Component msg = running.stop();
                if (running.player() != null) {
                    running.player().sendSystemMessage(msg);
                }
                Runnable next = running.after;
                running = null;
                if (next != null) {
                    next.run();
                }
            }
        });
    }
}

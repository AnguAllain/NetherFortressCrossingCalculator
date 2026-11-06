package nfc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NfcMod implements ModInitializer {
    public static final String MOD_ID = "nfc";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> NfcCommands.register(dispatcher));
        NfcServer.registerEvents();
        LOG.info("[nfc] loaded");
    }
}

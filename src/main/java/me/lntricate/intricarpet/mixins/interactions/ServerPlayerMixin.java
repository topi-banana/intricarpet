package me.lntricate.intricarpet.mixins.interactions;

import java.util.Map;
import me.lntricate.intricarpet.interactions.Interaction;
import me.lntricate.intricarpet.interfaces.IServerPlayer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin implements IServerPlayer {
    private Map<Interaction, Boolean> interactions;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo _ci) {
        this.interactions = Interaction.get(((ServerPlayer) (Object) this).getUUID());
    }

    @Override
    public boolean getInteraction(Interaction key) {
        return this.interactions.get(key);
    }

    @Override
    public Map<Interaction, Boolean> getInteractions() {
        return this.interactions;
    }

    @Override
    public void setInteraction(Interaction key, boolean value) {
        this.interactions.put(key, value);
        Interaction.set(((ServerPlayer) (Object) this).getUUID(), key, value);
    }
}
